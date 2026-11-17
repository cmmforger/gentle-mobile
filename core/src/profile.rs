//! The per-host connection profile.

use std::path::PathBuf;

/// Everything the transport needs to reach one SSH host and pin its key.
///
/// `identity_path` is a scratch or device-owned key file — this crate reads the
/// private key it names. `known_hosts_path` holds the pins for this profile's
/// hosts in OpenSSH known_hosts format (`[host]:port` for non-default ports).
#[derive(Debug, Clone)]
pub struct HostProfile {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub identity_path: PathBuf,
    pub known_hosts_path: PathBuf,
}
