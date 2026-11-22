//! gentlecompanion-core: the Gentle Companion transport core.
//!
//! Lane A is SSH: the phone connects to a host, execs
//! `herdr --session <name> remote-client-bridge`, and speaks the vendored
//! generation-1 endpoint through the pinned [`herdr_client`] crate. This crate
//! wraps that pump with the parts the product owes its users:
//!
//! * **Host-key pinning** ([`pin`]): unknown host → typed error carrying the
//!   `SHA256:...` fingerprint so the UI can ask; `accept_host_key` persists the
//!   pin (OpenSSH known_hosts format) and only then connecting succeeds; a
//!   different key is a hard error naming both fingerprints — never auto-accept.
//! * **Liveness** ([`connection`]): russh keepalive (interval and max fails as
//!   named constants) plus a watchdog. An established SSH session can outlive
//!   its usefulness with no EOF — killing an sshd *listener* does not drop
//!   established sessions (T3b finding) — so a quiet link is probed and
//!   surfaced as [`Event::Disconnected`].
//! * **Reconnect** ([`backoff`], [`supervise`]): bounded exponential backoff
//!   with jitter as a pure function, and a full-frame baseline on every
//!   reconnect — each retry builds a fresh connection whose handshake starts
//!   from a full snapshot; delta state from the previous connection is never
//!   reused.

pub mod backoff;
pub mod connection;
pub mod error;
pub mod paths;
pub mod pin;
pub mod profile;
pub mod pty;
pub mod requests;
pub mod rpc;
pub mod sessions;
pub mod supervise;
pub mod surface;
pub mod uniffi_api;
pub mod uploads;

// One scaffolding setup for the crate: the FFI entry points the generated
// Kotlin bindings call.
uniffi::setup_scaffolding!();

pub use backoff::{BASE_DELAY, JITTER_FRACTION, Jitter, MAX_DELAY, ReconnectPolicy};
pub use connection::{
    KEEPALIVE_INTERVAL, KEEPALIVE_MAX, SETUP_TIMEOUT, WATCHDOG_QUIET_AFTER, WATCHDOG_TICK,
    Connection, Event, wait_for_sshd,
};
pub use error::{Error, Result};
pub use pin::{
    PinDecision, accept_host_key, decide, fingerprint_of, forget_host, format_fingerprint,
    parse_entries, refusal_error, spec_for, HostPin,
};
pub use profile::HostProfile;
pub use supervise::Supervisor;
