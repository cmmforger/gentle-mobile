//! End-to-end transport check against the throwaway sshd of the spike recipe:
//! `/mnt/WD-Black/test_tmp/gentle-companion/ssh-spike/` (config, host key,
//! client key). Start that sshd first, then run this example. It connects,
//! prints the snapshot summary, and disconnects cleanly.
//!
//! ```sh
//! GENTLE_SSH_PORT=2424 cargo run -p gentlecompanion-core --example connect
//! ```

use std::{env, path::PathBuf, time::{Duration, Instant}};

use gentlecompanion_core::{
    Connection, Error, Event, HostProfile, PinDecision, accept_host_key, fingerprint_of,
};
use herdr_client::ConnectOptions;
use russh::keys::{parse_public_key_base64, PublicKey};

fn main() -> anyhow::Result<()> {
    let profile = HostProfile {
        host: env::var("GENTLE_SSH_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned()),
        port: env::var("GENTLE_SSH_PORT")
            .ok()
            .and_then(|port| port.parse().ok())
            .unwrap_or(2424),
        user: env::var("GENTLE_SSH_USER")
            .ok()
            .unwrap_or_else(|| env::var("USER").unwrap_or_default()),
        identity_path: PathBuf::from(
            env::var("GENTLE_SSH_KEY")
                .unwrap_or_else(|_| "/mnt/WD-Black/test_tmp/gentle-companion/ssh-spike/client_ed25519".to_owned()),
        ),
        known_hosts_path: PathBuf::from(
            env::var("GENTLE_KNOWN_HOSTS")
                .unwrap_or_else(|_| "/mnt/WD-Black/test_tmp/gentle-companion/t8-known-hosts".to_owned()),
        ),
    };
    let session = env::var("GENTLE_SESSION").unwrap_or_else(|_| "default".to_owned());
    let window = Duration::from_secs(
        env::var("GENTLE_EVENT_SECS").ok().and_then(|secs| secs.parse().ok()).unwrap_or(5),
    );

    let connection = match connect(&profile, &session) {
        Ok(connection) => connection,
        Err(Error::HostKeyUnknown { fingerprint, .. }) => {
            // The typed error is what a real UI would render as a question.
            println!("unknown host key, fingerprint {fingerprint} — accepting for this throwaway host");
            accept_fingerprint(&profile, &fingerprint)?;
            connect(&profile, &session)?
        }
        Err(error) => return Err(error.into()),
    };

    let mut counts = [0usize; 4]; // Snapshot, Surface, Message, Disconnected
    let start = Instant::now();
    let mut snapshot_summary = None;
    while start.elapsed() < window {
        let Some(events) = connection.events() else { break };
        match events.recv_timeout(window - start.elapsed()) {
            Ok(event) => match &event {
                Event::Snapshot(snapshot) => {
                    counts[0] += 1;
                    if snapshot_summary.is_none() {
                        snapshot_summary = Some(format!(
                            "workspaces={} tabs={} panes={} agents={} focused_pane_id={}",
                            snapshot.workspaces.len(),
                            snapshot.tabs.len(),
                            snapshot.panes.len(),
                            snapshot.agents.len(),
                            snapshot.focused_pane_id.as_deref().unwrap_or("<none>"),
                        ));
                    }
                }
                Event::Surface(_) => counts[1] += 1,
                Event::Message(_) => counts[2] += 1,
                Event::Disconnected { reason } => {
                    counts[3] += 1;
                    println!("disconnected: {reason}");
                }
            },
            Err(_) => break,
        }
    }
    if let Some(summary) = snapshot_summary {
        println!("snapshot: {summary}");
    } else if let Some(snapshot) = connection.snapshot() {
        println!(
            "snapshot: workspaces={} tabs={} panes={} agents={}",
            snapshot.workspaces.len(),
            snapshot.tabs.len(),
            snapshot.panes.len(),
            snapshot.agents.len(),
        );
    }
    println!(
        "events in {:.1}s: snapshot×{}, surface×{}, message×{}, disconnected×{}",
        start.elapsed().as_secs_f32(),
        counts[0], counts[1], counts[2], counts[3],
    );
    connection.disconnect();
    println!("disconnected cleanly");
    Ok(())
}

fn connect(profile: &HostProfile, session: &str) -> Result<Connection, Error> {
    Connection::connect(profile, session, ConnectOptions::default())
}

fn accept_fingerprint(profile: &HostProfile, fingerprint: &str) -> anyhow::Result<()> {
    // Rebuild a key from the pinned fingerprint path is unnecessary: the pin
    // store records OpenSSH blobs, and the example already knows the spike
    // host key. Verify the fingerprint matches the key on file before
    // accepting, then persist that key.
    let host_key_blob = std::fs::read_to_string(
        "/mnt/WD-Black/test_tmp/gentle-companion/ssh-spike/host_ed25519.pub",
    )?;
    let blob = host_key_blob.split_whitespace().nth(1).unwrap_or_default().to_owned();
    let key: PublicKey = parse_public_key_base64(&blob).map_err(|e| anyhow::anyhow!("{e}"))?;
    if fingerprint_of(&key) != fingerprint {
        anyhow::bail!("refusing to accept: presented fingerprint does not match the spike host key");
    }
    match gentlecompanion_core::decide(
        &gentlecompanion_core::parse_entries(&std::fs::read_to_string(&profile.known_hosts_path).unwrap_or_default()),
        &profile.host,
        profile.port,
        &key,
    ) {
        PinDecision::Match => {}
        PinDecision::Unknown { .. } => accept_host_key(&profile.known_hosts_path, &profile.host, profile.port, &key)?,
        PinDecision::Mismatch { .. } => anyhow::bail!("host key changed; refusing"),
    }
    Ok(())
}
