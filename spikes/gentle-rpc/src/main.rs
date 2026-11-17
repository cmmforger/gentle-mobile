//! Spike: prove or disprove lane B-i — the companion app opens its **own**
//! Gentle Shell session by spawning `gentle-shell --mode rpc` and speaking
//! Pi's strict JSONL RPC protocol over the child's stdin/stdout.
//!
//! Two transports, one driver:
//! - `local`: spawn the child directly (pipes; proves no TTY is required).
//! - `ssh`: open a russh session channel to 127.0.0.1:2323, exec the same
//!   command remotely (no PTY requested) and pump the channel through a
//!   socketpair so the same driver runs over SSH (pattern from the
//!   `herdr-ssh` spike; host-key pinning stays T8 work).
//!
//! Driver sequence: `get_state` → `get_messages` → optional `prompt`
//! ("say ok") → stream events until `agent_settled`. Prints the decoded
//! reply shapes, event-type counts, whether `extension_ui_request`
//! appeared, and verbatim wire samples (first 5 per direction, trimmed).

use anyhow::{bail, Context, Result};
use russh::{
    Disconnect,
    client::{self, Handle},
    keys::{HashAlg, PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate, decode_secret_key},
};
use tokio::io::AsyncWriteExt;
use std::{
    env,
    io::{BufRead, BufReader, Read, Write},
    net::{Shutdown, TcpStream},
    os::unix::net::UnixStream,
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::{Arc, mpsc},
    thread,
    time::{Duration, Instant},
};

/// Connecting, authenticating and getting the first bytes together.
const SETUP_TIMEOUT: Duration = Duration::from_secs(30);
/// How long an orderly shutdown (stdin EOF) may take before we kill the child.
const EXIT_GRACE: Duration = Duration::from_secs(20);
const TRIM: usize = 300;

fn main() -> Result<()> {
    let raw: Vec<String> = env::args().skip(1).collect();
    let mut raw = raw.into_iter();
    let mode = raw.next().unwrap_or_default();
    let mut flags = Flags::default();
    let mut command: Vec<String> = Vec::new();
    let mut past_dashdash = false;
    while let Some(arg) = raw.next() {
        if past_dashdash {
            command.push(arg);
            continue;
        }
        match arg.as_str() {
            "--" => past_dashdash = true,
            "--no-prompt" => flags.no_prompt = true,
            "--prompt" => flags.prompt = Some(next_value(&mut raw, "--prompt")?),
            "--window" => {
                flags.window =
                    Duration::from_secs(next_value(&mut raw, "--window")?.parse().context("--window wants seconds")?)
            }
            "--cwd" => flags.cwd = Some(PathBuf::from(next_value(&mut raw, "--cwd")?)),
            "--hold" => {
                flags.hold =
                    Duration::from_secs(next_value(&mut raw, "--hold")?.parse().context("--hold wants seconds")?)
            }
            "--host" => flags.host = next_value(&mut raw, "--host")?,
            "--port" => flags.port = next_value(&mut raw, "--port")?.parse().context("--port wants a number")?,
            "--user" => flags.user = next_value(&mut raw, "--user")?,
            "--key" => flags.key = PathBuf::from(next_value(&mut raw, "--key")?),
            other => bail!("unknown flag {other}; expected local|ssh, flags, then `-- <command...>`"),
        }
    }
    if command.is_empty() {
        bail!("missing `-- <command...>` (the remote/local command to run, e.g. gentle-shell --mode rpc)");
    }

    let report = match mode.as_str() {
        "local" => run_local(&flags, &command)?,
        "ssh" => run_ssh(&flags, &command)?,
        other => bail!("first argument must be `local` or `ssh`, got `{other}`"),
    };
    report.print();
    Ok(())
}

#[derive(Default)]
struct Flags {
    // Defaults to the task's tiny probe prompt; `--no-prompt` skips the turn.
    prompt: Option<String>,
    no_prompt: bool,
    // After the stages complete, keep the child alive this long before closing
    // stdin (used to hold one RPC process open on a session file while a
    // second one opens the same file).
    hold: Duration,
    window: Duration,
    cwd: Option<PathBuf>,
    host: String,
    port: u16,
    user: String,
    key: PathBuf,
}

fn next_value(argv: &mut impl Iterator<Item = String>, flag: &str) -> Result<String> {
    argv.next().with_context(|| format!("{flag} needs a value"))
}


fn run_local(flags: &Flags, command: &[String]) -> Result<Report> {
    let (bin, args) = command.split_first().context("empty command")?;
    let mut spawn = Command::new(bin);
    spawn.args(args).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
    if let Some(cwd) = &flags.cwd {
        spawn.current_dir(cwd);
    }
    let mut child = spawn.spawn().with_context(|| format!("spawning {bin}"))?;
    let stderr = child.stderr.take().context("child stderr")?;
    // Diagnostics only, never protocol data; print trimmed with an E> prefix.
    thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            println!("E> {}", trim(&line));
        }
    });
    let stdin = child.stdin.take().context("child stdin")?;
    let stdout = child.stdout.take().context("child stdout")?;
    let started = Instant::now();
    let result = drive(stdin, stdout, flags, started);
    finish_child(&mut child);
    result
}

fn run_ssh(flags: &Flags, command: &[String]) -> Result<Report> {
    let (ours, theirs) = UnixStream::pair().context("creating the pump socketpair")?;
    let remote = shell_quote(command);
    let host = Arc::new(flags.host.clone());
    let port = flags.port;
    let user = Arc::new(if flags.user.is_empty() {
        env::var("USER").unwrap_or_default()
    } else {
        flags.user.clone()
    });
    let key_path = flags.key.clone();
    let remote = Arc::new(remote);
    let pump = thread::Builder::new()
        .name("spike-ssh".to_owned())
        .spawn(move || {
            if let Err(error) = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(anyhow::Error::from)
                .and_then(|runtime| runtime.block_on(pump_channel(theirs, key_path, host, port, user, remote)))
            {
                println!("E> ssh pump error: {error:#}");
            }
        })
        .context("spawning the SSH runtime thread")?;
    wait_for_sshd(&flags.host, flags.port)?;
    // The writer half is moved into the driver (dropped at the end → stdin EOF
    // → orderly child shutdown → channel EOF → pump ends); only the reader
    // half needs a clone. A try_clone dup keeps the socketpair open even after
    // the writer is dropped, so the driver end is shut down explicitly before
    // joining the pump.
    let reader = ours.try_clone().context("cloning the pump socket")?;
    let killer = ours.try_clone().context("cloning the pump socket")?;
    let started = Instant::now();
    let result = drive(ours, reader, flags, started);
    let _ = killer.shutdown(Shutdown::Both);
    let _ = pump.join();
    result
}

async fn pump_channel(
    theirs: UnixStream,
    key_path: PathBuf,
    host: Arc<String>,
    port: u16,
    user: Arc<String>,
    command: Arc<String>,
) -> Result<()> {
    let secret = std::fs::read_to_string(&key_path).with_context(|| format!("reading {}", key_path.display()))?;
    let key = Arc::new(decode_secret_key(&secret, None).context("decoding the client key")?);
    let config = Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(5)),
        keepalive_max: 2,
        nodelay: true,
        ..client::Config::default()
    });
    let mut ssh: Handle<Verifier> = client::connect(config, (host.as_str(), port), Verifier::default())
        .await
        .context("connecting over SSH")?;
    let hash = ssh.best_supported_rsa_hash().await.ok().flatten().flatten();
    if !ssh
        .authenticate_publickey(user.as_str(), PrivateKeyWithHashAlg::new(key, hash))
        .await
        .context("authenticating with the client key")?
        .success()
    {
        bail!("public key authentication rejected");
    }
    println!("[ssh] connected; exec: {command}");
    let channel = ssh.channel_open_session().await.context("opening a session channel")?;
    channel.exec(true, command.as_str()).await.context("execing the remote command")?;
    let mut theirs = {
        theirs.set_nonblocking(true).context("making the socket nonblocking")?;
        tokio::net::UnixStream::from_std(theirs).context("converting the socket to tokio")?
    };
    let mut channel = channel.into_stream();
    let pumped = tokio::io::copy_bidirectional(&mut channel, &mut theirs).await;
    let _ = AsyncWriteExt::shutdown(&mut theirs).await;
    let _ = ssh.disconnect(Disconnect::ByApplication, "", "en").await;
    match pumped {
        Ok((to_ssh, from_ssh)) => println!("[ssh] pump closed: {to_ssh} bytes sent, {from_ssh} bytes received"),
        Err(error) => println!("[ssh] pump closed with error: {error}"),
    }
    Ok(())
}

#[derive(Default)]
struct Verifier;

impl client::Handler for Verifier {
    type Error = russh::Error;

    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> std::result::Result<bool, Self::Error> {
        let key: PublicKey = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(certificate) => {
                PublicKey::from(certificate.public_key().clone())
            }
        };
        println!("[ssh] accepted host key {}", key.fingerprint(HashAlg::Sha256));
        Ok(true)
    }
}

fn wait_for_sshd(host: &str, port: u16) -> Result<()> {
    let deadline = Instant::now() + SETUP_TIMEOUT;
    let address = (host, port);
    while Instant::now() < deadline {
        if TcpStream::connect(address).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(100));
    }
    bail!("sshd at {host}:{port} never accepted a connection");
}

fn shell_quote(command: &[String]) -> String {
    command
        .iter()
        .map(|part| format!("'{}'", part.replace('\'', r"'\''")))
        .collect::<Vec<_>>()
        .join(" ")
}

fn finish_child(child: &mut Child) {
    let _ = child.stdin.take();
    let deadline = Instant::now() + EXIT_GRACE;
    while Instant::now() < deadline {
        match child.try_wait() {
            Ok(Some(status)) => {
                println!("[child] exited: {status}");
                return;
            }
            Ok(None) => thread::sleep(Duration::from_millis(100)),
            Err(error) => {
                println!("[child] wait error: {error}");
                return;
            }
        }
    }
    println!("[child] did not exit within {EXIT_GRACE:?}; killing");
    let _ = child.kill();
    let _ = child.wait();
}

struct Report {
    samples_out: Vec<String>,
    samples_in: Vec<String>,
    event_counts: Vec<(String, usize)>,
    ui_methods: Vec<(String, usize)>,
    state_shape: Vec<String>,
    message_count: Option<usize>,
    prompt_response: Option<String>,
    settled_line: Option<String>,
    window: Duration,
}

impl Report {
    fn print(&self) {
        println!("\n=== top-level shape of get_state data ===");
        for entry in &self.state_shape {
            println!("  {entry}");
        }
        println!("=== get_messages ===");
        match self.message_count {
            Some(count) => println!("  messages: {count}"),
            None => println!("  no get_messages response captured"),
        }
        println!("=== prompt ===");
        match &self.prompt_response {
            Some(line) => println!("  response: {}", trim(line)),
            None => println!("  no prompt response captured"),
        }
        println!("=== events (by type) ===");
        for (name, count) in &self.event_counts {
            println!("  {name}: {count}");
        }
        println!("=== extension_ui_request methods ===");
        match self.ui_methods.is_empty() {
            true => println!("  none"),
            false => {
                for (method, count) in &self.ui_methods {
                    println!("  {method}: {count}");
                }
            }
        }
        println!("=== wire samples, client → pi (verbatim, trimmed to {TRIM}) ===");
        for line in &self.samples_out {
            println!("C> {}", trim(line));
        }
        println!("=== wire samples, pi → client (verbatim, trimmed to {TRIM}) ===");
        for line in &self.samples_in {
            println!("S> {}", trim(line));
        }
        if let Some(settled) = &self.settled_line {
            println!("S(settled)> {}", trim(settled));
        }
        println!("=== timing ===");
        println!("  event window: {:?}", self.window);
    }
}

fn trim(line: &str) -> String {
    let trimmed: String = line.chars().take(TRIM).collect();
    if line.chars().count() > TRIM {
        format!("{trimmed}… [{} chars total]", line.chars().count())
    } else {
        trimmed
    }
}

fn drive<W: Write>(writer: W, reader: impl Read + Send + 'static, flags: &Flags, started: Instant) -> Result<Report> {
    // Reader thread: blocking LF-only line reads (Rust read_line never splits
    // on U+2028/U+2029, unlike Node's readline). It reads until stdout EOF and
    // ignores a closed channel instead of dropping the pipe early — pi keeps
    // writing records after the last consumed event, and closing the read end
    // first makes the child crash with an unhandled EPIPE (exit 1).
    const EOF: &str = "\u{0}__EOF__";
    let (tx, rx) = mpsc::channel::<String>();
    thread::spawn(move || {
        let mut lines = BufReader::new(reader);
        let mut buf = String::new();
        loop {
            buf.clear();
            match lines.read_line(&mut buf) {
                Ok(0) | Err(_) => {
                    let _ = tx.send(EOF.to_string());
                    break;
                }
                Ok(_) => {
                    let line = buf.trim_end_matches(['\n', '\r']).to_string();
                    if tx.send(line).is_err() {
                        continue; // keep draining so the child never sees EPIPE
                    }
                }
            }
        }
    });

    let mut writer = writer;
    let mut sent: Vec<String> = Vec::new();
    let send = |writer: &mut W, sent: &mut Vec<String>, value: &serde_json::Value| -> Result<()> {
        let line = value.to_string();
        if sent.len() < 5 {
            sent.push(line.clone());
            println!("C> {}", trim(&line));
        }
        writeln!(writer, "{line}").context("writing to the child's stdin")?;
        writer.flush().context("flushing the child's stdin")
    };

    send(&mut writer, &mut sent, &serde_json::json!({ "id": "s1", "type": "get_state" }))?;

    let mut report = Report {
        samples_out: sent,
        samples_in: Vec::new(),
        event_counts: Vec::new(),
        ui_methods: Vec::new(),
        state_shape: Vec::new(),
        message_count: None,
        prompt_response: None,
        settled_line: None,
        window: flags.window,
    };
    let record = |counts: &mut Vec<(String, usize)>, key: &str| {
        match counts.iter_mut().find(|(existing, _)| existing == key) {
            Some((_, count)) => *count += 1,
            None => counts.push((key.to_string(), 1)),
        }
    };
    let mut stage = Stage::GetState;
    let deadline = started + flags.window;
    loop {
        let now = Instant::now();
        if now >= deadline {
            bail!("event window {:?} elapsed without reaching the end of stage {stage:?}", flags.window);
        }
        let line = match rx.recv_timeout(deadline - now) {
            Ok(line) => line,
            Err(_) => bail!("no more output before the deadline (stage {stage:?}); child likely exited"),
        };
        if line == EOF {
            bail!("child closed stdout (EOF) while the driver was in stage {stage:?}");
        }
        if report.samples_in.len() < 5 {
            println!("S> {}", trim(&line));
            report.samples_in.push(line.clone());
        }
        let value: serde_json::Value = match serde_json::from_str(&line) {
            Ok(value) => value,
            Err(error) => {
                println!("S! unparseable line ({error}): {}", trim(&line));
                continue;
            }
        };
        let kind = value.get("type").and_then(serde_json::Value::as_str).unwrap_or("<no type>");
        record(&mut report.event_counts, kind);
        match kind {
            "response" => {
                let id = value.get("id").and_then(serde_json::Value::as_str).unwrap_or("");
                let success = value.get("success").and_then(serde_json::Value::as_bool).unwrap_or(false);
                let error = value.get("error").and_then(serde_json::Value::as_str).unwrap_or("");
                match (stage, id) {
                    (Stage::GetState, "s1") | (Stage::GetMessages, "m1") if !success => {
                        bail!("command {id} failed: {error}");
                    }
                    (Stage::GetState, "s1") => {
                        report.state_shape = shape(value.get("data").unwrap_or(&serde_json::Value::Null));
                        stage = Stage::GetMessages;
                        send(&mut writer, &mut report.samples_out, &serde_json::json!({ "id": "m1", "type": "get_messages" }))?;
                    }
                    (Stage::GetMessages, "m1") => {
                        report.message_count = Some(
                            value
                                .pointer("/data/messages")
                                .and_then(serde_json::Value::as_array)
                                .map(Vec::len)
                                .unwrap_or(0),
                        );
                        if flags.no_prompt {
                            break;
                        }
                        let prompt_text = flags.prompt.clone().unwrap_or_else(|| "say ok".to_owned());
                        stage = Stage::Prompt;
                        send(
                            &mut writer,
                            &mut report.samples_out,
                            &serde_json::json!({ "id": "p1", "type": "prompt", "message": prompt_text }),
                        )?;
                    }
                    (Stage::Prompt, "p1") => {
                        report.prompt_response = Some(line.clone());
                        println!("[driver] prompt accepted: {}", trim(&line));
                    }
                    _ => record(&mut report.event_counts, "response(other)"),
                }
            }
            "extension_ui_request" => {
                let method = value.get("method").and_then(serde_json::Value::as_str).unwrap_or("<none>").to_string();
                record(&mut report.ui_methods, &method);
            }
            "agent_settled" => {
                report.settled_line = Some(line.clone());
                println!("[driver] agent_settled after {:.1}s", started.elapsed().as_secs_f32());
                break;
            }
            "message_update" => {
                let update = value
                    .get("assistantMessageEvent")
                    .and_then(|event| event.get("type"))
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("?");
                record(&mut report.event_counts, &format!("message_update/{update}"));
            }
            _ => {}
        }
    }
    // Orderly shutdown: closing stdin asks Pi to dispose and exit. Hold first
    // when asked, so a second process can open the same session file while
    // this one is alive.
    if !flags.hold.is_zero() {
        println!("[driver] holding the child for {:?}", flags.hold);
        thread::sleep(flags.hold);
    }
    drop(writer);
    Ok(report)
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Stage {
    GetState,
    GetMessages,
    Prompt,
}

fn shape(value: &serde_json::Value) -> Vec<String> {
    match value {
        serde_json::Value::Object(map) => map
            .iter()
            .map(|(key, value)| {
                let kind = match value {
                    serde_json::Value::Null => "null".to_owned(),
                    serde_json::Value::Bool(b) => format!("bool ({b})"),
                    serde_json::Value::Number(n) => format!("number ({n})"),
                    serde_json::Value::String(s) => format!("string ({})", trim(&format!("\"{s}\""))),
                    serde_json::Value::Array(a) => format!("array [{}]", a.len()),
                    serde_json::Value::Object(o) => format!("object {{{}}}", o.keys().count()),
                };
                format!("{key}: {kind}")
            })
            .collect(),
        other => vec![format!("<not an object: {}>", type_name(other))],
    }
}

fn type_name(value: &serde_json::Value) -> &'static str {
    match value {
        serde_json::Value::Null => "null",
        serde_json::Value::Bool(_) => "bool",
        serde_json::Value::Number(_) => "number",
        serde_json::Value::String(_) => "string",
        serde_json::Value::Array(_) => "array",
        serde_json::Value::Object(_) => "object",
    }
}
