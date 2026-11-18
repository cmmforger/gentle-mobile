//! Live check for the SFTP upload lane.
//!
//!     cargo run -p gentlecompanion-core --example upload -- <file> [host:port] [user]
//!
//! Reads the identity and known_hosts from the environment so nothing secret is
//! baked in:
//!   GENTLE_IDENTITY     path to the client private key (default: ~/.ssh/id_ed25519)
//!   GENTLE_KNOWN_HOSTS  path to the pinned hosts file  (default: ~/.ssh/known_hosts)
//!
//! Prints the absolute remote path and the prompt suffix the composer would add.
use std::path::PathBuf;

use anyhow::{Context, Result};
use gentlecompanion_core::uploads::{stored_name, upload_over_sftp};
use gentlecompanion_core::HostProfile;

fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let file = args.next().context("usage: upload <file> [host:port] [user]")?;
    let target = args.next().unwrap_or_else(|| "127.0.0.1:2222".to_owned());
    let user = args.next().unwrap_or_else(whoami);

    let (host, port) = match target.rsplit_once(':') {
        Some((h, p)) => (h.to_owned(), p.parse::<u16>().context("port")?),
        None => (target.clone(), 22),
    };

    let data = std::fs::read(&file).with_context(|| format!("reading {file}"))?;
    let name = std::path::Path::new(&file)
        .file_name()
        .and_then(|n| n.to_str())
        .context("file name")?;
    let stored = stored_name(name, std::time::SystemTime::now())
        .map_err(|e| anyhow::anyhow!("stored name rejected: {e:?}"))?;

    let profile = HostProfile {
        host,
        port,
        user,
        identity_path: std::env::var("GENTLE_IDENTITY")
            .unwrap_or_else(|_| format!("{}/.ssh/id_ed25519", home()))
            .into(),
        known_hosts_path: std::env::var("GENTLE_KNOWN_HOSTS")
            .unwrap_or_else(|_| format!("{}/.ssh/known_hosts", home()))
            .into(),
    };

    println!("uploading {name} ({} bytes) as {stored}", data.len());
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .context("building the upload runtime")?;
    let outcome = runtime
        .block_on(upload_over_sftp(&profile, &stored, &data, &|sent, total| {
            println!("  progress {sent}/{total}");
        }))
        .map_err(|e| anyhow::anyhow!("upload failed: {e:?}"))?;

    println!("remote_path: {}", outcome.remote_path);
    println!("prompt_suffix: {}", outcome.prompt_suffix);
    Ok(())
}

fn home() -> String {
    std::env::var("HOME").unwrap_or_else(|_| "/".to_owned())
}

fn whoami() -> String {
    std::env::var("USER").unwrap_or_else(|_| "root".to_owned())
}

#[allow(dead_code)]
fn unused(_: PathBuf) {}
