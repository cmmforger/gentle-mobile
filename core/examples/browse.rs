//! The T27 browsing measurement over a real host: the machine's disks
//! (parsed from `/proc/mounts` on the far side) and one directory's
//! folders, exactly what the `+` picker will show.
//!
//! Read-only: it lists, it never creates anything.
//!
//! ```sh
//! GENTLE_SSH_HOST=192.168.18.249 \
//! GENTLE_SSH_PORT=22 \
//! GENTLE_SSH_USER=erickfm \
//! GENTLE_SSH_KEY=… \
//! GENTLE_KNOWN_HOSTS=… \
//! GENTLE_BROWSE_DIR=/mnt \
//! cargo run -p gentle-mobile-core --example browse
//! ```
//!
//! Without `GENTLE_BROWSE_DIR` only the disk list is printed. The SSH
//! defaults follow the throwaway-sshd recipe the other examples use
//! (`127.0.0.1:2222`), so a real host needs the variables above.

use std::{env, path::PathBuf};

use gentle_mobile_core::{HostProfile, paths};

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

fn main() -> anyhow::Result<()> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async {
        let disks = paths::list_host_disks_over_sftp(&profile())
            .await
            .map_err(|error| anyhow::anyhow!("{error}"))?;
        println!("disks:");
        for disk in &disks {
            let kind = match disk.device_type {
                Some(paths::DeviceType::LocalDisk) => "disk",
                Some(paths::DeviceType::NetworkShare) => "network",
                None => "?",
            };
            println!("  {:<20} {:<12} ({kind})", disk.mount_point, disk.label);
        }
        if let Ok(dir) = env::var("GENTLE_BROWSE_DIR") {
            let folders = paths::list_host_folders_over_sftp(&profile(), &dir)
                .await
                .map_err(|error| anyhow::anyhow!("{error}"))?;
            println!("folders of {dir}:");
            for folder in &folders {
                if folder.is_symlink {
                    let target = folder.target.as_deref().unwrap_or("<unreadable>");
                    println!("  {} -> {target}", folder.path);
                } else if folder.is_git_repo {
                    println!("  {}  [git]", folder.path);
                } else {
                    println!("  {}", folder.path);
                }
            }
        }
        Ok(())
    })
}
