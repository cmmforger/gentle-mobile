//! The S24 listing measurement: times `list_sessions_over_sftp` against a
//! real SSH + SFTP transport (the throwaway sshd of the spike recipe),
//! reproducing the old serial cost (limit 1) and the new bounded window
//! (limit 16) on the same host, the same files and the same code path.
//!
//! ```sh
//! GENTLE_SESSIONS_DIR=/mnt/WD-Black/test_tmp/gentle-mobile/s24-sessions \
//! cargo run -p gentle-mobile-core --example list_sessions
//! ```

use std::{env, path::PathBuf};

use gentle_mobile_core::{sessions::HostSession, HostProfile};

fn profile() -> HostProfile {
    HostProfile {
        host: env::var("GENTLE_SSH_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned()),
        port: env::var("GENTLE_SSH_PORT")
            .ok()
            .and_then(|port| port.parse().ok())
            .unwrap_or(2222),
        user: env::var("GENTLE_SSH_USER")
            .ok()
            .unwrap_or_else(|| env::var("USER").unwrap_or_default()),
        identity_path: PathBuf::from(
            env::var("GENTLE_SSH_KEY").unwrap_or_else(|_| {
                "/mnt/WD-Black/test_tmp/gentle-mobile/ssh-spike/client_ed25519".to_owned()
            }),
        ),
        known_hosts_path: PathBuf::from(
            env::var("GENTLE_KNOWN_HOSTS").unwrap_or_else(|_| {
                "/mnt/WD-Black/test_tmp/gentle-mobile/ssh-spike/known_hosts".to_owned()
            }),
        ),
    }
}

async fn timed_listing(
    max_in_flight: usize,
) -> anyhow::Result<(std::time::Duration, usize, usize)> {
    let start = std::time::Instant::now();
    let sessions: Vec<HostSession> =
        gentle_mobile_core::sessions::list_sessions_over_sftp_with_limit(
            &profile(),
            &env::var("GENTLE_SESSIONS_DIR").unwrap_or_default(),
            "dm-measurement",
            max_in_flight,
        )
        .await
        .map_err(|error| anyhow::anyhow!("{error}"))?;
    let elapsed = start.elapsed();
    let counted = sessions
        .iter()
        .filter(|s| s.summary.message_count.is_some())
        .count();
    Ok((elapsed, sessions.len(), counted))
}

fn main() -> anyhow::Result<()> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let order: Vec<usize> = env::var("GENTLE_LIST_LIMITS")
        .ok()
        .map(|limits| {
            limits
                .split(',')
                .filter_map(|limit| limit.trim().parse().ok())
                .collect()
        })
        .unwrap_or_else(|| vec![16, 1, 1, 16, 16]);
    for max_in_flight in order {
        let (elapsed, listed, counted) =
            runtime.block_on(timed_listing(max_in_flight))?;
        println!(
            "limit={max_in_flight:>2}  {elapsed:>8.3?}  listed={listed}  with_count={counted}"
        );
    }
    Ok(())
}
