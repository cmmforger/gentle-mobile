//! Spike: prove the phone's real transport — in-process `russh` plus the
//! vendored `herdr-client` remote bridge — against a real SSH server
//! (`127.0.0.1:2222`). Mirrors Bellterm's `native/src/ssh`: a russh client on
//! a dedicated runtime thread opens a session channel, execs the vendored
//! discovery bridge (`remote_bridge_command`), and pumps that channel through
//! a Unix socketpair so `herdr-client` reads it like the `ssh` child it
//! spawns on the desktop. Spike accepts the server host key (printing its
//! fingerprint); pinning comes later in T8.

use anyhow::{Context, Result};
use herdr_client::{
    ClientEvent, ConnectOptions, ConnectTarget, Transport, answer_remote_bridge,
    connect_with_connector, remote_bridge_command,
};
use russh::{
    Disconnect,
    client::{self, Handle},
    keys::{HashAlg, PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate, decode_secret_key},
};
use std::{
    env, io, net::TcpStream, os::unix::net::UnixStream, path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};
use tokio::io::AsyncWriteExt;

const POLL: Duration = Duration::from_millis(100);
/// Connecting, authenticating, discovery, and the bridge handshake together.
const SETUP_TIMEOUT: Duration = Duration::from_secs(30);

fn main() -> Result<()> {
    let host = env::var("SPIKE_SSH_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned());
    let port: u16 = env::var("SPIKE_SSH_PORT")
        .ok()
        .and_then(|port| port.parse().ok())
        .unwrap_or(2222);
    let user =
        env::var("SPIKE_SSH_USER").unwrap_or_else(|_| env::var("USER").unwrap_or_default());
    let session = env::var("SPIKE_SESSION").unwrap_or_else(|_| "default".to_owned());
    let key_path = env::var("SPIKE_SSH_KEY").map(PathBuf::from).unwrap_or_else(|_| {
        PathBuf::from("/mnt/WD-Black/test_tmp/gentle-mobile/ssh-spike/client_ed25519")
    });
    let window = Duration::from_secs(
        env::var("SPIKE_EVENT_SECS")
            .ok()
            .and_then(|secs| secs.parse().ok())
            .unwrap_or(5),
    );
    let host = Arc::new(host);
    let user = Arc::new(user);
    let session = Arc::new(session);

    let stop = Arc::new(AtomicBool::new(false));
    let (ours, theirs) = UnixStream::pair().context("creating the bridge socketpair")?;
    let accepted = Arc::new(Mutex::new(None::<String>));
    let pump = {
        let host = host.clone();
        let user = user.clone();
        let session = session.clone();
        let accepted = accepted.clone();
        thread::Builder::new()
            .name("spike-ssh".to_owned())
            .spawn(move || {
                match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                    Ok(runtime) => {
                        let _ = runtime.block_on(run_bridge(
                            theirs, key_path, host, port, user, session, accepted,
                        ));
                    }
                    Err(error) => eprintln!("runtime: {error}"),
                }
            })
            .context("spawning the SSH runtime thread")?
    };

    // The pump thread connects while this thread answers the discovery banner
    // on our end of the pair, exactly as the desktop bridge's handshake does.
    ours.set_read_timeout(Some(POLL))
        .context("setting the bridge read timeout")?;
    ours.set_write_timeout(Some(Duration::from_secs(1)))
        .context("setting the bridge write timeout")?;
    wait_for_sshd(&host, port)?;
    let started = Instant::now();
    answer_remote_bridge(&mut &ours, &stop)
        .context("answering the remote bridge discovery handshake")?;
    let fingerprint = accepted.lock().ok().and_then(|seen| seen.clone());
    match fingerprint {
        Some(fingerprint) => println!("Accepted host key fingerprint: {fingerprint}"),
        None => println!("host key fingerprint not recorded"),
    }
    println!("bridge answered in {:.1}s", started.elapsed().as_secs_f32());

    // The client owns the other end of the pump like any remote bridge.
    let client = connect_with_connector(
        ConnectTarget::Socket(PathBuf::from("ssh")),
        ConnectOptions::default(),
        true,
        move |_, _| Ok::<Transport, io::Error>(Transport::Remote(ours)),
    )
    .context("starting the herdr client over the SSH bridge")?;

    let mut event_count = 0usize;
    let mut variants: Vec<(&'static str, usize)> = Vec::new();
    let start = Instant::now();
    while start.elapsed() < window {
        match client.events.recv_timeout(window.saturating_sub(start.elapsed())) {
            Ok(event) => {
                event_count += 1;
                match event {
                    ClientEvent::Connected(welcome) => {
                        record(&mut variants, "Connected");
                        println!(
                            "Connected: protocol {} (product {})",
                            welcome.generation, welcome.server_version
                        );
                    }
                    ClientEvent::Snapshot(snapshot) => {
                        record(&mut variants, "Snapshot");
                        if variants_summary(&variants, "Snapshot") == 1 {
                            println!(
                                "snapshot: workspaces={} tabs={} panes={} agents={} focused_pane_id={}",
                                snapshot.workspaces.len(),
                                snapshot.tabs.len(),
                                snapshot.panes.len(),
                                snapshot.agents.len(),
                                snapshot
                                    .focused_pane_id
                                    .as_deref()
                                    .unwrap_or("<none>")
                            );
                        }
                    }
                    ClientEvent::Surface(surface) => {
                        record(&mut variants, "Surface");
                        println!(
                            "Surface: surface_revision {} ({} panes)",
                            surface.surface_revision,
                            surface.panes.len()
                        );
                    }
                    ClientEvent::Disconnected { reason, .. } => {
                        record(&mut variants, "Disconnected");
                        println!("Disconnected: {reason}");
                        break;
                    }
                    _ => {}
                }
            }
            Err(_) => break,
        }
    }
    println!(
        "events in {:.1}s: {event_count} total — {}",
        start.elapsed().as_secs_f32(),
        variants
            .iter()
            .map(|(name, count)| format!("{name}×{count}"))
            .collect::<Vec<_>>()
            .join(", "),
    );
    // Closing the client closes our end of the pump, which ends
    // copy_bidirectional and lets the runtime thread finish.
    client.handle.disconnect();
    stop.store(true, Ordering::Release);
    let _ = pump.join();
    Ok(())
}

/// Block until the spike's sshd accepts TCP connections, so a slow start does
/// not read as a missing host.
fn wait_for_sshd(host: &str, port: u16) -> Result<()> {
    let deadline = Instant::now() + SETUP_TIMEOUT;
    let address = (host.to_owned(), port);
    while Instant::now() < deadline {
        if TcpStream::connect(&address).is_ok() {
            return Ok(());
        }
        thread::sleep(POLL);
    }
    anyhow::bail!("sshd at {host}:{port} never accepted a connection");
}

/// Connect, authenticate, exec the discovery bridge, and pump the channel
/// through `theirs` until either side closes.
async fn run_bridge(
    theirs: UnixStream,
    key_path: PathBuf,
    host: Arc<String>,
    port: u16,
    user: Arc<String>,
    session: Arc<String>,
    accepted: Arc<Mutex<Option<String>>>,
) -> Result<()> {
    let secret = std::fs::read_to_string(&key_path)
        .with_context(|| format!("reading {}", key_path.display()))?;
    let key = Arc::new(decode_secret_key(&secret, None).context("decoding the client key")?);
    // The keepalive is what surfaces a dead link: without it russh's channel
    // stream does not notice the closed TCP connection and the pump leaves the
    // socketpair half-open. Production tuning stays T8 work.
    let config = Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(5)),
        keepalive_max: 2,
        inactivity_timeout: None,
        nodelay: true,
        ..client::Config::default()
    });
    let mut ssh: Handle<Verifier> = client::connect(config, (host.as_str(), port), Verifier {
        accepted: accepted.clone(),
    })
    .await
    .context("connecting over SSH")?;
    let hash = ssh
        .best_supported_rsa_hash()
        .await
        .ok()
        .flatten()
        .flatten();
    if !ssh
        .authenticate_publickey(user.as_str(), PrivateKeyWithHashAlg::new(key, hash))
        .await
        .context("authenticating with the client key")?
        .success()
    {
        anyhow::bail!("public key authentication rejected");
    }
    let command = remote_bridge_command(&session).context("building the bridge command")?;
    let channel = ssh.channel_open_session().await.context("opening a session channel")?;
    channel.exec(true, command).await.context("execing the remote bridge")?;

    let mut theirs = {
        theirs.set_nonblocking(true).context("making the socket nonblocking")?;
        tokio::net::UnixStream::from_std(theirs).context("converting the socket to tokio")?
    };
    let mut channel = channel.into_stream();
    let pump_started = Instant::now();
    let pumped = tokio::io::copy_bidirectional(&mut channel, &mut theirs).await;
    let elapsed = pump_started.elapsed();
    let _ = theirs.shutdown().await;
    let _ = ssh.disconnect(Disconnect::ByApplication, "", "en").await;
    match pumped {
        Ok((to_ssh, from_ssh)) => {
            println!(
                "bridge pump closed after {:.1}s: {to_ssh} bytes to client, {from_ssh} bytes to server",
                elapsed.as_secs_f32()
            );
            Ok(())
        }
        Err(error) => {
            println!("bridge pump closed after {:.1}s with error: {error}", elapsed.as_secs_f32());
            Ok(())
        }
    }
}

/// Accepts the server's key and records its fingerprint. Pinning comes later
/// in T8.
struct Verifier {
    accepted: Arc<Mutex<Option<String>>>,
}

impl client::Handler for Verifier {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> std::result::Result<bool, Self::Error> {
        let key: PublicKey = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(certificate) => {
                PublicKey::from(certificate.public_key().clone())
            }
        };
        let fingerprint = key.fingerprint(HashAlg::Sha256).to_string();
        if let Ok(mut seen) = self.accepted.lock() {
            *seen = Some(fingerprint);
        }
        Ok(true)
    }
}

fn record(counts: &mut Vec<(&'static str, usize)>, name: &'static str) {
    match counts.iter_mut().find(|(existing, _)| *existing == name) {
        Some((_, count)) => *count += 1,
        None => counts.push((name, 1)),
    }
}

fn variants_summary(counts: &[(&'static str, usize)], name: &str) -> usize {
    counts
        .iter()
        .find(|(existing, _)| *existing == name)
        .map(|(_, count)| *count)
        .unwrap_or(0)
}
