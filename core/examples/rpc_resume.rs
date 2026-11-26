//! T17b fixture verification (run by hand, never by the app):
//!
//! ```text
//! cargo run --example rpc_resume -- \
//!     127.0.0.1 2222 erickfm <identity-key> <known-hosts> \
//!     <fixture-session.jsonl> <device-id>
//! ```
//!
//! Drives the full continuity lane against **fixtures only**: takes the
//! lease, spawns `gentle-shell --mode rpc --session <path>` over a fresh
//! channel of the pinned SSH connection, verifies the sessionId matches,
//! reads the transcript, sends one prompt, reads the reply, closes cleanly
//! — and prints the lease file's existence at every stage, plus one
//! deliberate refusal attempt while the lease is live (S18).

use std::time::Duration;

use gentle_mobile_core::rpc::{self, RpcCommand, RpcDeadlines, RpcError, RpcShared};
use gentle_mobile_core::profile::HostProfile;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [ref host, ref port, ref user, ref identity, ref known_hosts, ref session_path, ref device_id] = args[..] else {
        return Err("expected: host port user identity known_hosts session_path device_id".into());
    };
    let profile = HostProfile {
        host: host.clone(),
        port: port.parse()?,
        user: user.clone(),
        identity_path: identity.into(),
        known_hosts_path: known_hosts.into(),
    };
    let deadlines = RpcDeadlines {
        response: Duration::from_secs(60),
        settle: Duration::from_secs(600),
        exit: Duration::from_secs(20),
    };

    // Derive the session id from the fixture file's header — the same parse
    // the listing uses.
    let raw = std::fs::read_to_string(session_path)?;
    let header = raw.lines().next().unwrap_or("");
    let header: serde_json::Value = serde_json::from_str(header)?;
    let session_id = header["id"].as_str().expect("fixture header id").to_owned();
    println!("fixture session id: {session_id}");

    // The lease file this session must take and release (S18).
    let lease_path = format!(
        "{}/.local/state/gentle-mobile/leases/{}.json",
        std::env::var("HOME").unwrap_or_default(),
        session_id
    );
    let lease_state = || -> String {
        match std::fs::read_to_string(&lease_path) {
            Ok(text) => format!("LEASE PRESENT ({})", text.trim().chars().take(60).collect::<String>()),
            Err(_) => "LEASE ABSENT".to_owned(),
        }
    };
    println!("before resume: {}", lease_state());

    // 1. Lease first (S18). A fresh token every resume.
    let token = format!("t28-{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_millis());
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let taken = runtime.block_on(rpc::take_lease_for_resume(
        &profile, &session_path, &session_id, &device_id, &token,
    ))?;
    println!("lease take: {taken:?}");
    println!("after take:  {}", lease_state());

    // 2. The pump thread owns the connection, child and handshake.
    let shared = std::sync::Arc::new(std::sync::Mutex::new(RpcShared::default()));
    let (commands_tx, commands_rx) = crossbeam_channel::bounded::<RpcCommand>(8);
    let (announced_tx, announced_rx) = crossbeam_channel::bounded(1);
    let lease = rpc::LeaseGuard {
        profile: profile.clone(),
        session_id: session_id.clone(),
        device_id: device_id.to_owned(),
        token: token.clone(),
    };
    let shared_for_thread = shared.clone();
    let thread_profile = profile.clone();
    let thread_session_path = session_path.to_owned();
    let thread_session_id = session_id.clone();
    let handle = std::thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| Box::new(e) as Box<dyn std::error::Error + Send>)?;
        runtime.block_on(rpc::run_resumed_session(
            thread_profile,
            thread_session_path,
            thread_session_id,
            lease,
            commands_rx,
            shared_for_thread,
            announced_tx,
            deadlines,
        ));
        Ok::<(), Box<dyn std::error::Error + Send>>(())
    });
    let handshake = announced_rx.recv_timeout(Duration::from_secs(90))??;
    println!("handshake: sessionId={} messageCount={}", handshake.session_id, handshake.message_count);
    assert_eq!(handshake.session_id, session_id, "the child must report the fixture's own session id");
    println!("after spawn: {}", lease_state());
    println!("transcript at resume ({} entries):", handshake.transcript.len());
    for entry in &handshake.transcript {
        println!("  [{}] {}", entry.role, entry.text.chars().take(80).collect::<String>());
    }

    // 3. S18 in the other direction: while our lease is live, a second
    //    claimant must be refused typed.
    let refusal = runtime.block_on(async {
        rpc::take_lease_for_resume(&profile, &session_path, &session_id, "dm-other-device", "t28-thief").await
    });
    match refusal {
        Err(RpcError::AlreadyLeased { detail }) => println!("refusal (expected): AlreadyLeased — {detail}"),
        other => println!("UNEXPECTED: the second claim was not refused: {other:?}"),
    }

    // 4. One prompt, read the reply.
    let (reply_tx, reply_rx) = crossbeam_channel::bounded(1);
    commands_tx.send(RpcCommand::Prompt {
        text: "Reply with exactly: t28-ok".to_owned(),
        reply: reply_tx,
    })?;
    let transcript = reply_rx.recv_timeout(Duration::from_secs(600))??;
    println!("after prompt ({} entries):", transcript.len());
    for entry in &transcript {
        println!("  [{}] {}", entry.role, entry.text.chars().take(80).collect::<String>());
    }

    // 5. Orderly close; the driver releases the lease on the way out.
    let (close_tx, close_rx) = crossbeam_channel::bounded(1);
    commands_tx.send(RpcCommand::Close { reply: close_tx })?;
    close_rx.recv_timeout(Duration::from_secs(30))??;
    let _ = handle.join();
    // Give the driver's own release a beat, then check.
    for _ in 0..20 {
        if !std::path::Path::new(&lease_path).exists() {
            break;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    println!("after close: {}", lease_state());
    let shared = shared.lock().unwrap();
    println!("pump closed={}, last_error={:?}", shared.closed, shared.last_error);
    if std::path::Path::new(&lease_path).exists() {
        return Err("the lease file survived the close — S18 violated".into());
    }
    println!("fixture verification: OK");
    Ok(())
}
