//! Typed crate errors. Pinning failures are first-class variants because the
//! UI must be able to ask the user for a decision from the error alone.

use std::result::Result as StdResult;

/// Crate-root error.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// The host was never pinned. Drop-in replacement for a question to the
    /// user: it carries the presented fingerprint to offer for acceptance.
    #[error("unknown host key for {host}:{port}; fingerprint {fingerprint}")]
    HostKeyUnknown {
        host: String,
        port: u16,
        fingerprint: String,
    },
    /// An established pin does not match the presented key. Never retried and
    /// never auto-accepted; names both fingerprints.
    #[error("host key mismatch for {host}:{port}: pinned {pinned}, presented {presented}")]
    HostKeyMismatch {
        host: String,
        port: u16,
        pinned: String,
        presented: String,
    },
    /// Reconnect gave up after the policy's attempt budget.
    #[error("reconnect exhausted after {attempts} attempts; last state: {last}")]
    ReconnectExhausted { attempts: u32, last: String },
    #[error("timed out: {0}")]
    Timeout(&'static str),
    #[error("ssh transport: {0}")]
    Ssh(#[from] russh::Error),
    #[error("herdr client: {0}")]
    Client(#[from] herdr_client::Error),
    #[error("i/o: {0}")]
    Io(#[from] std::io::Error),
}

/// Crate-root result.
pub type Result<T> = StdResult<T, Error>;
