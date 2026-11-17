//! The reconnect loop: bounded attempts on the pure [`ReconnectPolicy`]
//! schedule, with a full-frame baseline on every reconnect.
//!
//! The full-frame baseline is structural: every (re)connection in
//! [`Supervisor::run`] builds a brand-new [`Connection`], and a fresh vendored
//! client performs a fresh handshake starting from a full snapshot. Delta
//! surfaces from the previous connection are never reused, because the delta
//! stream is useless without the baseline it extends.

use std::{
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant},
};

use herdr_client::ConnectOptions;

use crate::{
    backoff::{Jitter, ReconnectPolicy},
    connection::{Connection, Event},
    error::{Error, Result},
    profile::HostProfile,
};

/// One supervised endpoint: profile, session name, connect options, and the
/// reconnect policy.
#[derive(Debug, Clone)]
pub struct Supervisor {
    pub profile: HostProfile,
    pub session: String,
    pub options: ConnectOptions,
    pub policy: ReconnectPolicy,
}

impl Supervisor {
    /// Run in the caller's thread (off the UI thread). Forwards every product
    /// event to `on_event`; reconnects with the policy after each unrequested
    /// disconnect, checking `stop` between sleep slices and at every step.
    ///
    /// Pin refusals are terminal for the run: they need a human decision
    /// ([`crate::pin::accept_host_key`]), not a retry. When the attempt budget
    /// is spent, the run fails with [`Error::ReconnectExhausted`].
    pub fn run(
        &self,
        mut on_event: impl FnMut(Event),
        stop: &AtomicBool,
        jitter: &mut Jitter,
    ) -> Result<()> {
        let mut failed = 0u32;
        loop {
            if stop.load(Ordering::Acquire) {
                return Ok(());
            }
            match Connection::connect(&self.profile, &self.session, self.options) {
                Ok(connection) => {
                    failed = 0;
                    loop {
                        if stop.load(Ordering::Acquire) {
                            drop(connection);
                            return Ok(());
                        }
                        // Slices, not a bare recv: a quiet session must still
                        // observe a stop request.
                        let received = match connection.events() {
                            Some(events) => events.recv_timeout(Duration::from_millis(200)),
                            None => break,
                        };
                        match received {
                            Ok(event) => {
                                let over = matches!(event, Event::Disconnected { .. });
                                on_event(event);
                                if over {
                                    break;
                                }
                            }
                            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {}
                            Err(crossbeam_channel::RecvTimeoutError::Disconnected) => break,
                        }
                    }
                    connection.disconnect();
                    if stop.load(Ordering::Acquire) {
                        return Ok(());
                    }
                }
                Err(error @ (Error::HostKeyUnknown { .. } | Error::HostKeyMismatch { .. })) => {
                    return Err(error);
                }
                Err(error) => {
                    failed += 1;
                    if self.policy.exhausted(failed) {
                        return Err(Error::ReconnectExhausted {
                            attempts: failed,
                            last: error.to_string(),
                        });
                    }
                    self.sleep_backoff(failed, jitter, stop);
                }
            }
        }
    }

    /// Wait for the policy delay in `stop`-checking slices.
    fn sleep_backoff(&self, failed: u32, jitter: &mut Jitter, stop: &AtomicBool) {
        let deadline = Instant::now() + self.policy.delay(failed, jitter.sample());
        while Instant::now() < deadline && !stop.load(Ordering::Acquire) {
            thread::sleep(Duration::from_millis(50).min(deadline - Instant::now()));
        }
    }
}
