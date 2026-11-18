//! End-to-end request-lane check against the throwaway sshd of the spike
//! recipe: `/mnt/WD-Black/test_tmp/gentle-companion/ssh-spike/`. Start that
//! sshd first, then run this example. It connects, waits for the first
//! snapshot, reads one pane over `pane.read --source visible`, and — only
//! with `--prompt` — submits one prompt over `agent.prompt` to that pane's
//! agent. Honesty by construction: the daemon's refusals print as their
//! typed code.
//!
//! ```sh
//! GENTLE_SSH_PORT=2222 cargo run -p gentlecompanion-core --example requests -- --pane w1:p1 [--prompt "echo t11-ok"]
//! ```

use std::{path::PathBuf, time::Duration};

use gentlecompanion_core::{Connection, Error, HostProfile, accept_host_key, fingerprint_of};
use herdr_client::ConnectOptions;

fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let mut pane = String::new();
    let mut prompt: Option<String> = None;
    // Each `--keys a,b,esc` occurrence is one `pane.send_keys` request, in
    // order — so `--keys c,l,e,a --keys tab` reproduces the key-row presses.
    let mut key_batches: Vec<Vec<String>> = Vec::new();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--pane" => pane = args.next().ok_or(anyhow::anyhow!("--pane needs a value"))?,
            "--prompt" => prompt = Some(args.next().ok_or(anyhow::anyhow!("--prompt needs a value"))?),
            "--keys" => {
                let batch = args
                    .next()
                    .ok_or(anyhow::anyhow!("--keys needs a comma-separated list"))?
                    .split(',')
                    .map(str::to_owned)
                    .collect();
                key_batches.push(batch);
            }
            other => anyhow::bail!("unknown argument {other}"),
        }
    }
    let profile = HostProfile {
        host: std::env::var("GENTLE_SSH_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned()),
        port: std::env::var("GENTLE_SSH_PORT")
            .ok()
            .and_then(|port| port.parse().ok())
            .unwrap_or(2424),
        user: std::env::var("GENTLE_SSH_USER")
            .ok()
            .unwrap_or_else(|| std::env::var("USER").unwrap_or_default()),
        identity_path: PathBuf::from(
            std::env::var("GENTLE_SSH_KEY").unwrap_or_else(|_| {
                "/mnt/WD-Black/test_tmp/gentle-companion/ssh-spike/client_ed25519".to_owned()
            }),
        ),
        known_hosts_path: PathBuf::from(
            std::env::var("GENTLE_KNOWN_HOSTS")
                .unwrap_or_else(|_| "/mnt/WD-Black/test_tmp/gentle-companion/t8-known-hosts".to_owned()),
        ),
    };
    let session = std::env::var("GENTLE_SESSION").unwrap_or_else(|_| "default".to_owned());

    let connection = match Connection::connect(&profile, &session, ConnectOptions::default()) {
        Ok(connection) => connection,
        Err(Error::HostKeyUnknown { fingerprint, .. }) => {
            println!("unknown host key, fingerprint {fingerprint} — accepting for this throwaway host");
            accept_fingerprint(&profile, &fingerprint)?;
            Connection::connect(&profile, &session, ConnectOptions::default())?
        }
        Err(error) => return Err(error.into()),
    };

    // The snapshot lists the agents; the read is addressed by pane id.
    let deadline = std::time::Instant::now() + Duration::from_secs(20);
    while connection.snapshot().is_none() && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(100));
    }
    if let Some(snapshot) = connection.snapshot() {
        for agent in &snapshot.agents {
            println!(
                "agent: name={:?} kind={:?} status={:?} pane={}",
                agent.name, agent.display_agent, agent.agent_status, agent.pane_id,
            );
        }
    }

    println!("pane.read {pane}: requesting…");
    match connection.pane_read(pane.as_str()) {
        Ok(read) => {
            println!("pane.read {pane}: truncated={}", read.truncated);
            println!("--- begin pane text ---");
            println!("{}", read.text);
            println!("--- end pane text ---");
        }
        Err(error) => println!("pane.read failed: {error}"),
    }

    for batch in &key_batches {
        println!("pane.send_keys {pane}: sending {batch:?}…");
        match connection.pane_send_keys(pane.as_str(), batch) {
            Ok(()) => println!("pane.send_keys: the host confirmed delivery (ok)"),
            Err(error) => println!("pane.send_keys failed: {error}"),
        }
    }

    if let Some(prompt) = prompt {
        println!("agent.prompt {pane}: submitting…");
        match connection.agent_prompt(pane.as_str(), prompt.as_str()) {
            Ok(()) => println!("agent.prompt: the host confirmed the submission (agent_prompted)"),
            Err(error) => println!("agent.prompt failed: {error}"),
        }
    }

    connection.disconnect();
    println!("disconnected cleanly");
    Ok(())
}

fn accept_fingerprint(profile: &HostProfile, fingerprint: &str) -> anyhow::Result<()> {
    let host_key_blob = std::fs::read_to_string(
        "/mnt/WD-Black/test_tmp/gentle-companion/ssh-spike/host_ed25519.pub",
    )?;
    let blob = host_key_blob.split_whitespace().nth(1).unwrap_or_default().to_owned();
    use russh::keys::parse_public_key_base64;
    let key: russh::keys::PublicKey =
        parse_public_key_base64(&blob).map_err(|error| anyhow::anyhow!("{error}"))?;
    if fingerprint_of(&key) != fingerprint {
        anyhow::bail!("refusing to accept: presented fingerprint does not match the spike host key");
    }
    accept_host_key(&profile.known_hosts_path, &profile.host, profile.port, &key)
        .map_err(|error| anyhow::anyhow!("{error}"))
}
