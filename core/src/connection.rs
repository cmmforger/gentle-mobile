//! The product connection: russh to the host, exec of the vendored discovery
//! bridge, and a [`herdr_client`] session over the pumped socketpair — with
//! keepalive, a routing of [`herdr_client::ClientEvent`] into the product's
//! small [`Event`] enum, and a watchdog.
//!
//! Liveness is load-bearing, not plumbing: killing an sshd *listener* does not
//! drop established sessions (T3b finding), and an SSH link that goes quiet
//! otherwise never surfaces an EOF. [`KEEPALIVE_INTERVAL`] and
//! [`KEEPALIVE_MAX`] keep russh noticing a dead TCP path; the watchdog notices
//! a quiet link and provokes a health probe, so the socket going quiet ends as
//! [`Event::Disconnected`].

use std::{
    io,
    net::TcpStream,
    os::unix::net::UnixStream,
    path::PathBuf,
    sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}, mpsc},
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use herdr_client::{
    Client, ClientEvent, ClientHandle, ConnectOptions, ConnectTarget, Transport,
    answer_remote_bridge, connect_with_connector, remote_bridge_command,
    protocol::{ClientShellSnapshot, PaneSurfaceFrame, ServerMessage},
};
use russh::{
    Disconnect,
    client::{self, Handle},
    keys::{PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate, decode_secret_key},
};
use tokio::io::AsyncWriteExt;

use crate::{
    error::{Error, Result},
    pin::{HostPin, PinDecision, refusal_error},
    profile::HostProfile,
};

/// Russh keepalive probe interval. This is the signal that notices a dead TCP
/// path to the host, since killing an sshd listener does not drop established
/// sessions.
pub const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(5);
/// Russh gives up after this many unanswered keepalive probes.
pub const KEEPALIVE_MAX: usize = 2;
/// Watchdog polling tick.
pub const WATCHDOG_TICK: Duration = Duration::from_secs(1);
/// A link quiet for this long gets an explicit liveness probe; an unanswered
/// probe is failed by the vendored client after a few seconds and surfaces as
/// [`Event::Disconnected`].
pub const WATCHDOG_QUIET_AFTER: Duration = Duration::from_secs(30);
/// Connecting, authenticating, discovery, and the bridge handshake together.
pub const SETUP_TIMEOUT: Duration = Duration::from_secs(30);

const POLL: Duration = Duration::from_millis(100);

/// The product's view of the endpoint stream.
#[derive(Debug, Clone)]
// `ServerMessage` is wide but bounded by the vendored wire contract; boxing
// it would copy for every event on the hot path.
#[allow(clippy::large_enum_variant)]
pub enum Event {
    Snapshot(Arc<ClientShellSnapshot>),
    Surface(Arc<PaneSurfaceFrame>),
    Message(ServerMessage),
    /// Connection over, for good or ill: dead TCP path (keepalive), bridge
    /// exit, protocol refusal, or an explicit local disconnect.
    Disconnected { reason: String },
}

/// A live connection to one host over SSH.
///
/// Event delivery follows the vendored client's rules: drain
/// [`Connection::events`] continuously — a full queue pauses transport
/// processing, liveness included.
pub struct Connection {
    client: Option<Client>,
    events: Option<crossbeam_channel::Receiver<Event>>,
    latest: Arc<Mutex<Option<Arc<ClientShellSnapshot>>>>,
    stop: Arc<AtomicBool>,
    threads: Vec<JoinHandle<()>>,
}

/// Block until the SSH endpoint accepts TCP connections, so a slow sshd start
/// does not read as a missing host.
pub fn wait_for_sshd(host: &str, port: u16) -> Result<()> {
    let deadline = Instant::now() + SETUP_TIMEOUT;
    let address = (host.to_owned(), port);
    while Instant::now() < deadline {
        if TcpStream::connect(&address).is_ok() {
            return Ok(());
        }
        thread::sleep(POLL);
    }
    Err(Error::Timeout("the ssh endpoint never accepted a TCP connection"))
}

impl Connection {
    /// Connect synchronously (call it off the UI thread): pin-check the host
    /// key, authenticate, exec the bridge, answer the discovery handshake, and
    /// hand the pump to the vendored client.
    ///
    /// Unknown or mismatched host keys fail with the typed pin errors before
    /// any channel data flows: nothing is auto-accepted. After the user
    /// consents, persist the pin with [`crate::pin::accept_host_key`] and call
    /// again.
    pub fn connect(profile: &HostProfile, session: &str, options: ConnectOptions) -> Result<Self> {
        let pins = crate::pin::load_entries(&profile.known_hosts_path)?;
        wait_for_sshd(&profile.host, profile.port)?;

        let stop = Arc::new(AtomicBool::new(false));
        // The caller-side end of the pair stays on this thread until the
        // vendored client takes it; the runtime thread owns `theirs`.
        let (ours, theirs) = UnixStream::pair().map_err(Error::Io)?;
        let (setup_tx, setup_rx) = mpsc::channel::<Result<()>>();

        let ssh_thread = {
            let profile = profile.clone();
            let session = session.to_owned();
            let stop = stop.clone();
            thread::Builder::new()
                .name("gentlecompanion-ssh".to_owned())
                .spawn(move || {
                    let report_end = setup_tx.clone();
                    match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                    Ok(runtime) => {
                        let outcome =
                            runtime.block_on(run_bridge(theirs, &profile, &session, pins, setup_tx));
                        // Whatever killed the pump also ends the product
                        // connection: re-report the final outcome so a failed
                        // handshake can prefer this honest cause.
                        if let Err(error) = outcome {
                            let _ = report_end.send(Err(error));
                            stop.store(true, Ordering::Release);
                        }
                    }
                    Err(error) => {
                        let _ = setup_tx.send(Err(Error::Io(error)));
                    }
                    }
                })
                .map_err(Error::Io)?
        };

        // Wait for SSH setup (pin verdict included) before answering the
        // discovery handshake: an unknown or mismatched key must fail here,
        // not as a hang or a confusing bridge error.
        let setup = match setup_rx.recv_timeout(SETUP_TIMEOUT) {
            Ok(outcome) => outcome,
            Err(_) => Err(Error::Timeout("ssh setup produced no verdict in time")),
        };
        if let Err(error) = setup {
            stop.store(true, Ordering::Release);
            let _ = ssh_thread.join();
            return Err(error);
        }

        // Answer the vendored discovery handshake on our end of the pair,
        // bounded by a timer that flips `setup_stop` after [`SETUP_TIMEOUT`].
        let setup_stop = Arc::new(AtomicBool::new(false));
        let answered = Arc::new(AtomicBool::new(false));
        let timer = {
            let setup_stop = setup_stop.clone();
            let answered = answered.clone();
            thread::Builder::new()
                .name("gentlecompanion-setup-timer".to_owned())
                .spawn(move || {
                    let start = Instant::now();
                    while start.elapsed() < SETUP_TIMEOUT && !answered.load(Ordering::Acquire) {
                        thread::sleep(POLL);
                    }
                    if !answered.load(Ordering::Acquire) {
                        setup_stop.store(true, Ordering::Release);
                    }
                })
                .map_err(Error::Io)?
        };
        let mut ours = ours;
        ours.set_read_timeout(Some(POLL)).map_err(Error::Io)?;
        ours.set_write_timeout(Some(Duration::from_secs(1))).map_err(Error::Io)?;
        let answer = answer_remote_bridge(&mut ours, &setup_stop);
        answered.store(true, Ordering::Release);
        let _ = timer.join();

        let setup = match answer {
            Ok(()) => Ok(()),
            Err(answer_error) => match setup_rx.try_recv() {
                Ok(Err(cause)) => Err(cause),
                _ => Err(Error::Client(answer_error)),
            },
        };
        if let Err(error) = setup {
            stop.store(true, Ordering::Release);
            let _ = ssh_thread.join();
            return Err(error);
        }

        // Hand `ours` to the vendored client like any remote bridge stream.
        let client = match connect_with_connector(
            ConnectTarget::Socket(PathBuf::from("ssh")),
            options,
            true,
            move |_, _| Ok::<Transport, io::Error>(Transport::Remote(ours)),
        ) {
            Ok(client) => client,
            Err(error) => {
                stop.store(true, Ordering::Release);
                let _ = ssh_thread.join();
                return Err(Error::Client(error));
            }
        };

        let (events_tx, events_rx) = crossbeam_channel::bounded::<Event>(64);
        let latest = Arc::new(Mutex::new(None::<Arc<ClientShellSnapshot>>));
        let last_event = Arc::new(Mutex::new(Instant::now()));
        let forwarder = spawn_forwarder(
            client.events.clone(),
            events_tx,
            latest.clone(),
            last_event.clone(),
            stop.clone(),
        )?;
        let watchdog = spawn_watchdog(client.handle.clone(), last_event, stop.clone())?;
        Ok(Self {
            client: Some(client),
            events: Some(events_rx),
            latest,
            stop,
            threads: vec![ssh_thread, forwarder, watchdog],
        })
    }

    /// The most recent full snapshot, if any has arrived yet. Every reconnect
    /// starts a fresh baseline, so this is never a stale delta source.
    pub fn snapshot(&self) -> Option<Arc<ClientShellSnapshot>> {
        self.latest.lock().ok().and_then(|guarded| guarded.clone())
    }

    /// The product event stream, until the connection is torn down. Single
    /// consumer by design: do not clone the receiver (crossbeam receivers
    /// compete).
    pub fn events(&self) -> Option<&crossbeam_channel::Receiver<Event>> {
        self.events.as_ref()
    }

    /// The vendored client handle (cloneable) for input, navigation, requests,
    /// and resize.
    pub fn handle(&self) -> Option<ClientHandle> {
        self.client.as_ref().map(|client| client.handle.clone())
    }

    /// True once the connection is over, however it ended.
    pub fn is_disconnected(&self) -> bool {
        self.stop.load(Ordering::Acquire)
    }

    /// Disconnect cleanly: stop the vendored client, close the SSH connection,
    /// and join every thread. The event stream ends with an
    /// [`Event::Disconnected`].
    pub fn disconnect(mut self) {
        self.teardown();
    }

    fn teardown(&mut self) {
        self.stop.store(true, Ordering::Release);
        // Close the product event channel first: a forwarder blocked on a
        // full queue must observe the dropped receiver and exit, not deadlock
        // the join.
        drop(self.events.take());
        if let Some(client) = self.client.take() {
            client.handle.disconnect();
        }
        for thread in self.threads.drain(..) {
            let _ = thread.join();
        }
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        // Never join in drop; threads observe `stop` and exit on their own.
        self.stop.store(true, Ordering::Release);
        if let Some(client) = self.client.take() {
            client.handle.disconnect();
        }
    }
}

/// Map vendored events to the product enum and track the latest snapshot.
fn spawn_forwarder(
    events: crossbeam_channel::Receiver<ClientEvent>,
    out: crossbeam_channel::Sender<Event>,
    latest: Arc<Mutex<Option<Arc<ClientShellSnapshot>>>>,
    last_event: Arc<Mutex<Instant>>,
    stop: Arc<AtomicBool>,
) -> Result<JoinHandle<()>> {
    thread::Builder::new()
        .name("gentlecompanion-events".to_owned())
        .spawn(move || {
            let mut ended = false;
            while !ended {
                match events.recv_timeout(Duration::from_secs(1)) {
                    Ok(event) => {
                        if let Ok(mut guarded) = last_event.lock() {
                            *guarded = Instant::now();
                        }
                        match event {
                            ClientEvent::Snapshot(snapshot) => {
                                if let Ok(mut guarded) = latest.lock() {
                                    *guarded = Some(snapshot.clone());
                                }
                                ended = deliver(&out, Event::Snapshot(snapshot));
                            }
                            ClientEvent::Surface(surface) => {
                                ended = deliver(&out, Event::Surface(surface));
                            }
                            ClientEvent::Message(message) => {
                                ended = deliver(&out, Event::Message(message));
                            }
                            ClientEvent::Disconnected { reason, .. } => {
                                ended = deliver(&out, Event::Disconnected { reason });
                            }
                            // Connected, Response, CommandRejected, SurfaceImages, and
                            // VersionMismatch are vendored-side detail; a refused
                            // version arrives again as a Disconnected reason.
                            _ => {}
                        }
                    }
                    Err(crossbeam_channel::RecvTimeoutError::Timeout) => {
                        if stop.load(Ordering::Acquire) {
                            ended = deliver(
                                &out,
                                Event::Disconnected { reason: "disconnected".to_owned() },
                            );
                        }
                    }
                    Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                        ended = deliver(
                            &out,
                            Event::Disconnected { reason: "connection closed".to_owned() },
                        );
                    }
                }
            }
        })
        .map_err(Error::Io)
}

/// Send one event; a dropped receiver ends delivery. True when the forwarder
/// should stop.
fn deliver(out: &crossbeam_channel::Sender<Event>, event: Event) -> bool {
    out.send(event).is_err()
}

/// Watchdog: notices a dead worker and a quiet link. On a link gone quiet it
/// provokes a vendored health probe — per the T3b finding an established
/// session gets no EOF when only the sshd listener is gone, so probing is the
/// only honest liveness signal.
fn spawn_watchdog(
    handle: ClientHandle,
    last_event: Arc<Mutex<Instant>>,
    stop: Arc<AtomicBool>,
) -> Result<JoinHandle<()>> {
    thread::Builder::new()
        .name("gentlecompanion-watchdog".to_owned())
        .spawn(move || loop {
            if stop.load(Ordering::Acquire) {
                return;
            }
            thread::sleep(WATCHDOG_TICK);
            if stop.load(Ordering::Acquire) {
                return;
            }
            if handle.is_disconnected() {
                // The worker already knows; the forwarder surfaces it.
                return;
            }
            let quiet_for = match last_event.lock() {
                Ok(guarded) => guarded.elapsed(),
                Err(_) => return,
            };
            if quiet_for >= WATCHDOG_QUIET_AFTER {
                handle.check_liveness();
            }
        })
        .map_err(Error::Io)
}

/// Connect, authenticate, exec the discovery bridge, and pump the channel
/// through `theirs` until either side closes. Reports setup success (or the
/// typed failure) on `setup_tx` before pumping.
async fn run_bridge(
    theirs: UnixStream,
    profile: &HostProfile,
    session: &str,
    pins: Vec<HostPin>,
    setup_tx: mpsc::Sender<Result<()>>,
) -> Result<()> {
    let report = |outcome: Result<()>| -> Result<()> {
        let _ = setup_tx.send(outcome);
        Ok(())
    };
    let key = match std::fs::read_to_string(&profile.identity_path)
        .map_err(Error::Io)
        .and_then(|secret| decode_secret_key(&secret, None).map(Arc::new).map_err(russh::Error::from).map_err(Error::Ssh))
    {
        Ok(key) => key,
        Err(error) => {
            report(Err(error))?;
            return Ok(());
        }
    };
    let config = Arc::new(client::Config {
        keepalive_interval: Some(KEEPALIVE_INTERVAL),
        keepalive_max: KEEPALIVE_MAX,
        inactivity_timeout: None,
        nodelay: true,
        ..client::Config::default()
    });
    let refusal = Arc::new(Mutex::new(None::<PinDecision>));
    let connect = client::connect(
        config,
        (profile.host.as_str(), profile.port),
        KeyVerifier {
            host: profile.host.clone(),
            port: profile.port,
            pins,
            refusal: refusal.clone(),
        },
    )
    .await;
    let mut ssh: Handle<KeyVerifier> = match connect {
        Ok(ssh) => ssh,
        Err(error) => {
            let decision = refusal.lock().ok().and_then(|guarded| guarded.clone());
            let outcome = decision
                .and_then(|decision| refusal_error(&profile.host, profile.port, &decision))
                .unwrap_or(Error::Ssh(error));
            report(Err(outcome))?;
            return Ok(());
        }
    };
    let hash = ssh.best_supported_rsa_hash().await.ok().flatten().flatten();
    let auth = ssh
        .authenticate_publickey(profile.user.as_str(), PrivateKeyWithHashAlg::new(key, hash))
        .await;
    match auth {
        Ok(state) if state.success() => {}
        Ok(_) => {
            report(Err(Error::Ssh(russh::Error::NotAuthenticated)))?;
            return Ok(());
        }
        Err(error) => {
            report(Err(Error::Ssh(error)))?;
            return Ok(());
        }
    }
    let command = match remote_bridge_command(session) {
        Ok(command) => command,
        Err(error) => {
            report(Err(error.into()))?;
            return Ok(());
        }
    };
    let channel = match ssh.channel_open_session().await {
        Ok(channel) => channel,
        Err(error) => {
            report(Err(Error::Ssh(error)))?;
            return Ok(());
        }
    };
    if let Err(error) = channel.exec(true, command).await {
        report(Err(Error::Ssh(error)))?;
        return Ok(());
    }
    report(Ok(()))?;

    let mut theirs = match theirs
        .set_nonblocking(true)
        .map_err(Error::Io)
        .and_then(|()| tokio::net::UnixStream::from_std(theirs).map_err(Error::Io))
    {
        Ok(stream) => stream,
        Err(error) => {
            return Err(error);
        }
    };
    let pumped = tokio::io::copy_bidirectional(&mut channel.into_stream(), &mut theirs).await;
    let _ = AsyncWriteExt::shutdown(&mut theirs).await;
    let _ = ssh.disconnect(Disconnect::ByApplication, "", "en").await;
    pumped.map(|_| ()).map_err(Error::Io)
}

/// The SSH host key verifier. It never auto-accepts: an unknown or mismatched
/// key is refused and recorded so [`refusal_error`] can produce the typed
/// product error.
struct KeyVerifier {
    host: String,
    port: u16,
    pins: Vec<HostPin>,
    refusal: Arc<Mutex<Option<PinDecision>>>,
}

impl client::Handler for KeyVerifier {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> std::result::Result<bool, Self::Error> {
        let key: PublicKey = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(certificate) => {
                PublicKey::from(certificate.public_key().clone())
            }
        };
        let decision = crate::pin::decide(&self.pins, &self.host, self.port, &key);
        match decision {
            PinDecision::Match => Ok(true),
            other => {
                if let Ok(mut guarded) = self.refusal.lock() {
                    *guarded = Some(other);
                }
                Ok(false)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    // Deterministic behaviour belongs to `pin` and `backoff`. The connection
    // module is exercised end-to-end by `examples/connect.rs` against the
    // throwaway sshd.
    use super::*;

    #[test]
    fn constants_are_named_not_magic() {
        assert_ne!(KEEPALIVE_INTERVAL, Duration::ZERO);
        assert_ne!(KEEPALIVE_MAX, 0);
        assert!(WATCHDOG_QUIET_AFTER > KEEPALIVE_INTERVAL);
    }
}
