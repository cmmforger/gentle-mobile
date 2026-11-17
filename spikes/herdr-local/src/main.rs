//! Spike: speak Herdr's generation 1 endpoint protocol from Rust, using the
//! vendored `herdr-client` crate, against the local `herdr` daemon's default
//! session.
//!
//! Prints a one-line snapshot summary, then stays on the event receiver for
//! ~5 seconds printing each event's variant name, then exits 0.

use anyhow::{Context, Result};
use herdr_client::{connect, ClientEvent, ConnectOptions, ConnectTarget};
use std::time::{Duration, Instant};

const EVENT_WINDOW: Duration = Duration::from_secs(5);

fn main() -> Result<()> {
    let client = connect(ConnectTarget::Local, ConnectOptions::default())
        .context("connecting to the local herdr daemon (session default)")?;
    println!("connected; waiting for snapshot");

    let mut event_count = 0usize;
    let mut variant_counts: Vec<(&'static str, usize)> = Vec::new();
    let start = Instant::now();
    while start.elapsed() < EVENT_WINDOW {
        match client.events.recv_timeout(EVENT_WINDOW.saturating_sub(start.elapsed())) {
            Ok(event) => {
                event_count += 1;
                match event {
                    ClientEvent::Connected(welcome) => {
                        record(&mut variant_counts, "Connected");
                        println!(
                            "Connected: protocol {} (product {})",
                            welcome.generation, welcome.server_version
                        );
                    }
                    ClientEvent::Snapshot(snapshot) => {
                        record(&mut variant_counts, "Snapshot");
                        if event_count == 1 || variant_summary(&variant_counts, "Snapshot") == 1 {
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
                        record(&mut variant_counts, "Surface");
                        println!(
                            "Surface: surface_revision {} ({} panes)",
                            surface.surface_revision,
                            surface.panes.len()
                        );
                    }
                    ClientEvent::SurfaceImages(_) => {
                        record(&mut variant_counts, "SurfaceImages");
                    }
                    ClientEvent::Response { request_id, .. } => {
                        record(&mut variant_counts, "Response");
                        println!("Response: {request_id}");
                    }
                    ClientEvent::CommandRejected { reason, .. } => {
                        record(&mut variant_counts, "CommandRejected");
                        println!("CommandRejected: {reason}");
                    }
                    ClientEvent::Message(message) => {
                        record(&mut variant_counts, "Message");
                        println!("Message: {message:?}");
                    }
                    ClientEvent::VersionMismatch(mismatch) => {
                        record(&mut variant_counts, "VersionMismatch");
                        println!("VersionMismatch: {mismatch:?}");
                    }
                    ClientEvent::Disconnected { reason, .. } => {
                        record(&mut variant_counts, "Disconnected");
                        println!("Disconnected: {reason}");
                        break;
                    }
                }
            }
            Err(_timeout) => break,
        }
    }

    println!(
        "events in {:.1}s: {event_count} total — {}",
        start.elapsed().as_secs_f32(),
        variant_counts
            .iter()
            .map(|(name, n)| format!("{name}×{n}"))
            .collect::<Vec<_>>()
            .join(", "),
    );
    Ok(())
}

fn record(counts: &mut Vec<(&'static str, usize)>, name: &'static str) {
    match counts.iter_mut().find(|(n, _)| *n == name) {
        Some((_, n)) => *n += 1,
        None => counts.push((name, 1)),
    }
}

fn variant_summary(counts: &[(&'static str, usize)], name: &str) -> usize {
    counts
        .iter()
        .find(|(n, _)| *n == name)
        .map(|(_, n)| *n)
        .unwrap_or(0)
}
