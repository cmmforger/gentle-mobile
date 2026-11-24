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
    collections::{HashMap, VecDeque},
    io,
    net::TcpStream,
    os::unix::net::UnixStream,
    path::{Path, PathBuf},
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
    keys::{PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate},
};
use tokio::io::AsyncWriteExt;

use crate::{
    error::{Error, Result},
    pin::{HostPin, PinDecision, refusal_error},
    profile::HostProfile,
    surface::SurfaceStore,
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
/// How long an awaited surface command waits for the daemon's own answer
/// before the wait fails: a confirmed activation is bookkeeping, a missing
/// one is an honest typed failure (T26 rule 3).
pub const SURFACE_ACK_TIMEOUT: Duration = Duration::from_secs(10);

/// The host's answer to one awaited surface command (T26 rules 3–4). The
/// bookkeeping is written only from [`SurfaceAck::Confirmed`]; a refusal
/// reaches the UI in the daemon's own words, and a host without surface
/// support can never look identical to one that accepted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SurfaceAck {
    /// The daemon answered the request with a result.
    Confirmed,
    /// The daemon answered with an error object; `code` and `detail` are its
    /// own words.
    Refused { code: String, detail: String },
    /// The command never made it to the daemon: the vendored client's own
    /// policy refused it (a welcome that advertises no surface interest
    /// rejects `surface.set` before a byte is written).
    Rejected { detail: String },
}

/// Map a daemon response envelope: a result confirms; an error object is the
/// refusal — the same shape the request lane parses.
fn ack_from_response(response: &serde_json::Value) -> SurfaceAck {
    match response.get("error").filter(|error| !error.is_null()) {
        None => SurfaceAck::Confirmed,
        Some(error) => SurfaceAck::Refused {
            code: error
                .get("code")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("unknown")
                .to_owned(),
            detail: error
                .get("message")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("the host gave no reason")
                .to_owned(),
        },
    }
}

/// Routes the answer of one awaited command to the caller that queued it.
/// The forwarder routes; [`AckRouter::register`] waits. An outcome that
/// races ahead of its waiter — the worker's own policy rejections can be
/// delivered before the caller reaches its registration — is buffered
/// briefly and handed over there; the buffer is capped, never unbounded.
#[derive(Default)]
struct AckRouter {
    inner: Mutex<RouterState>,
}

/// Bounded backlog of outcomes delivered before their waiter registered.
const UNCLAIMED_ACK_CAP: usize = 8;

#[derive(Default)]
struct RouterState {
    waiters: HashMap<String, crossbeam_channel::Sender<SurfaceAck>>,
    unclaimed: VecDeque<(String, SurfaceAck)>,
}

impl AckRouter {
    /// Deliver one answer. A registered waiter gets it directly; otherwise
    /// it is buffered for the registration that follows (oldest evicted).
    fn route(&self, request_id: &str, ack: SurfaceAck) {
        if let Ok(mut state) = self.inner.lock() {
            if let Some(waiter) = state.waiters.remove(request_id) {
                let _ = waiter.send(ack);
                return;
            }
            if state.unclaimed.len() >= UNCLAIMED_ACK_CAP {
                state.unclaimed.pop_front();
            }
            state.unclaimed.push_back((request_id.to_owned(), ack));
        }
    }

    /// Register the waiter for one request id, handing over any outcome
    /// that already raced ahead of the registration.
    fn register(&self, request_id: &str) -> crossbeam_channel::Receiver<SurfaceAck> {
        let (sender, receiver) = crossbeam_channel::bounded(1);
        if let Ok(mut state) = self.inner.lock() {
            let raced = state
                .unclaimed
                .iter()
                .position(|(id, _)| id == request_id)
                .map(|index| state.unclaimed.remove(index).expect("index from position"));
            match raced {
                Some((_, ack)) => {
                    let _ = sender.send(ack);
                }
                None => {
                    state.waiters.insert(request_id.to_owned(), sender);
                }
            }
        }
        receiver
    }

    /// Drop a waiter whose wait ran out, so a late outcome buffers (bounded)
    /// instead of leaking a registration.
    fn forget(&self, request_id: &str) {
        if let Ok(mut state) = self.inner.lock() {
            state.waiters.remove(request_id);
        }
    }

    /// Queue the command, then wait for the daemon's own answer.
    fn await_ack(&self, request_id: &str, budget: Duration) -> Result<SurfaceAck> {
        let receiver = self.register(request_id);
        match receiver.recv_timeout(budget) {
            Ok(ack) => Ok(ack),
            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {
                self.forget(request_id);
                Err(Error::Timeout("the surface command was never confirmed"))
            }
            Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                self.forget(request_id);
                Err(Error::Timeout(
                    "the connection ended before the surface command was confirmed",
                ))
            }
        }
    }
}

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
    /// The surface throttle: one latest-wins slot, overwritten in place, so
    /// the UI can never queue a stale frame. A fresh connection constructs
    /// its own empty store — the reconnect baseline is the first full frame
    /// the new session delivers.
    surfaces: Arc<SurfaceStore>,
    stop: Arc<AtomicBool>,
    threads: Vec<JoinHandle<()>>,
    /// The request-only channel for `pane.read` / `agent.prompt`; connects
    /// lazily on the first request, dies with the connection.
    requests: Option<crate::requests::RequestsHub>,
    /// The answer router for awaited surface commands (T26 rules 3–4): the
    /// forwarder routes the daemon's response or rejection here, and the
    /// caller that queued the command observes it — bookkeeping is written
    /// only from a confirmation, and a refusal reaches the UI typed.
    acks: Arc<AckRouter>,
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

/// A key probe: the host's presented key and the pin verdict for it, learned
/// from the SSH handshake alone — no authentication, no channel, no data.
/// This is the challenge step of the UniFFI surface: the UI renders
/// [`PinDecision::Unknown`]/[`PinDecision::Mismatch`] as a question, and only
/// an explicit user consent followed by [`crate::pin::accept_host_key`] lets
/// a later connect proceed.
pub struct HostKeyProbe {
    pub key: PublicKey,
    pub decision: PinDecision,
}

/// The verifier used by [`probe_host_key`]: it records the presented key and
/// refuses to proceed — a probe never exchanges channel data.
struct ProbeVerifier {
    presented: Arc<Mutex<Option<PublicKey>>>,
}

impl client::Handler for ProbeVerifier {
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
        if let Ok(mut guarded) = self.presented.lock() {
            *guarded = Some(key);
        }
        Ok(false)
    }
}

/// Probe the host key without authenticating. Fails only when no key was
/// presented (unreachable host, transport failure); the pin verdict is
/// computed from the pins on file, exactly as a real connection would.
pub fn probe_host_key(profile: &HostProfile) -> Result<HostKeyProbe> {
    let pins = crate::pin::load_entries(&profile.known_hosts_path)?;
    wait_for_sshd(&profile.host, profile.port)?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(Error::Io)?;
    let presented = Arc::new(Mutex::new(None::<PublicKey>));
    let config = Arc::new(client::Config {
        nodelay: true,
        ..client::Config::default()
    });
    let _ = runtime.block_on(client::connect(
        config,
        (profile.host.as_str(), profile.port),
        ProbeVerifier { presented: presented.clone() },
    ));
    let key = presented
        .lock()
        .ok()
        .and_then(|guarded| guarded.clone())
        .ok_or(Error::Timeout("the ssh handshake presented no host key"))?;
    let decision = crate::pin::decide(&pins, &profile.host, profile.port, &key);
    Ok(HostKeyProbe { key, decision })
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
        let surfaces = Arc::new(SurfaceStore::new());
        let acks = Arc::new(AckRouter::default());
        let last_event = Arc::new(Mutex::new(Instant::now()));
        let forwarder = spawn_forwarder(
            client.events.clone(),
            events_tx,
            latest.clone(),
            surfaces.clone(),
            acks.clone(),
            last_event.clone(),
            stop.clone(),
        )?;
        let watchdog = spawn_watchdog(client.handle.clone(), last_event, stop.clone())?;
        let requests = crate::requests::RequestsHub::spawn(profile.clone(), session.to_owned())?;
        Ok(Self {
            client: Some(client),
            events: Some(events_rx),
            latest,
            surfaces,
            stop,
            threads: vec![ssh_thread, forwarder, watchdog],
            requests: Some(requests),
            acks,
        })
    }

    /// The most recent full snapshot, if any has arrived yet. Every reconnect
    /// starts a fresh baseline, so this is never a stale delta source.
    pub fn snapshot(&self) -> Option<Arc<ClientShellSnapshot>> {
        self.latest.lock().ok().and_then(|guarded| guarded.clone())
    }

    /// The latest surface frame, decoded into UI DTOs. Latest-wins: the store
    /// holds exactly one slot, so a burst between polls collapses into the
    /// newest frame and nothing stale is ever handed to the UI. Decoded fresh
    /// on each call — cheap, and it keeps the DTO cache-free.
    pub fn latest_surface(&self) -> Option<crate::surface::SurfaceDto> {
        self.surfaces.latest().as_deref().map(crate::surface::decode_surface)
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

    /// Prove the link is alive now: ping the host immediately instead of
    /// waiting for the routine quiet-link check. Use when the app returns to
    /// the foreground after a sleep — the socket may have died unnoticed, and
    /// an unanswered probe ends the connection so the next liveness poll
    /// reports the interruption (T8c).
    pub fn probe_liveness(&self) {
        if let Some(client) = self.client.as_ref() {
            client.handle.check_liveness();
        }
    }

    /// Read one pane's visible text over the request channel. `source: visible`
    /// and ANSI stripped, so what comes back is exactly what the host's own
    /// `herdr pane read <id> --source visible` shows.
    pub fn pane_read(
        &self,
        pane_id: &str,
    ) -> std::result::Result<crate::requests::PaneRead, crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_PANE_READ,
            crate::requests::pane_read_params(pane_id),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_pane_read(&value).map_err(crate::requests::HubError::Request)
        })
    }

    /// Submit one prompt to an agent, addressed by its pane id. The daemon
    /// itself focuses the pane, writes the text, and presses Enter — a
    /// prompt, not raw keystrokes. Success means the daemon replied
    /// `agent_prompted`; anything else is the honest typed failure.
    pub fn agent_prompt(
        &self,
        target: &str,
        prompt: &str,
    ) -> std::result::Result<(), crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_AGENT_PROMPT,
            crate::requests::prompt_params(target, prompt),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_agent_prompt(&value).map_err(crate::requests::HubError::Request)
        })
    }

    /// Send one batch of key presses to a pane, addressed by its pane id —
    /// the same `pane.send_keys` the host's `herdr pane send-keys` uses.
    /// Key names are the daemon's, measured live: `esc`/`tab`/`enter`,
    /// `up`/`down`/`left`/`right`, `backspace`, `space`, single characters,
    /// and `ctrl+`/`alt+`/`shift+` prefixed compositions. Success means the
    /// daemon replied `ok`; a refusal names the daemon's own code.
    pub fn pane_send_keys(
        &self,
        pane_id: &str,
        keys: &[String],
    ) -> std::result::Result<(), crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_PANE_SEND_KEYS,
            crate::requests::send_keys_params(pane_id, keys),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_send_keys(&value).map_err(crate::requests::HubError::Request)
        })
    }

    /// The host's workspace list over the request channel (`workspace.list`,
    /// S26-2). Shapes measured live on protocol 22.
    pub fn workspace_list(
        &self,
    ) -> std::result::Result<Vec<crate::requests::HostWorkspace>, crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_WORKSPACE_LIST,
            crate::requests::workspace_list_params(),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_workspace_list(&value)
                .map_err(crate::requests::HubError::Request)
        })
    }

    /// The branch the repo behind one workspace is on
    /// (`worktree.list {workspace_id}` — measured read-only without
    /// `trust_repository`). A refusal means "no branch" (`Ok(None)`), never
    /// a screen failure; transport failures still propagate.
    pub fn worktree_branch(
        &self,
        workspace_id: &str,
    ) -> std::result::Result<Option<String>, crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_WORKTREE_LIST,
            crate::requests::worktree_list_params(workspace_id),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_worktree_branch(&value, workspace_id)
                .map_err(crate::requests::HubError::Request)
        })
        .or_else(|error| match error {
            crate::requests::HubError::Request(crate::requests::RequestError::Refused { .. }) => {
                Ok(None)
            }
            other => Err(other),
        })
    }

    /// Create a workspace on the host (`workspace.create`, S26-3). The
    /// caller must validate the path first: measured live, the daemon falls
    /// back to `$HOME` for a missing directory instead of refusing.
    pub fn workspace_create(
        &self,
        cwd: &str,
        label: Option<&str>,
    ) -> std::result::Result<crate::requests::WorkspaceCreated, crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_WORKSPACE_CREATE,
            crate::requests::workspace_create_params(cwd, label),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_workspace_created(&value)
                .map_err(crate::requests::HubError::Request)
        })
    }

    /// Focus one workspace (`workspace.focus`, S26-2): the daemon's own
    /// switch, exactly what tapping the workspace in the herdr TUI does.
    pub fn workspace_focus(&self, workspace_id: &str) -> std::result::Result<(), crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_WORKSPACE_FOCUS,
            crate::requests::workspace_focus_params(workspace_id),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_workspace_focus(&value)
                .map_err(crate::requests::HubError::Request)
        })
    }

    /// Focus one tab (`tab.focus {tab_id}`, S28-1, on the same request lane
    /// as `workspace.focus` — measured from `herdr api schema --json`,
    /// protocol 22, and verified live: a tab with no agent is focusable).
    /// The daemon's own switch: the focused pane changes, and the surface
    /// stream — which renders the focused pane — follows, so the phone's
    /// visible pane follows too.
    pub fn tab_focus(&self, tab_id: &str) -> std::result::Result<(), crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_TAB_FOCUS,
            crate::requests::tab_focus_params(tab_id),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_tab_focus(&value).map_err(crate::requests::HubError::Request)
        })
    }

    /// Create a tab in one workspace (`tab.create {workspace_id, focus}`,
    /// T47): the daemon's own creation on the request lane, focused so the
    /// new tab is where the human lands. The reply is the created tab's
    /// `tab_info`.
    pub fn tab_create(
        &self,
        workspace_id: &str,
        focus: bool,
    ) -> std::result::Result<(), crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_TAB_CREATE,
            crate::requests::tab_create_params(workspace_id, focus),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_tab_create(&value).map_err(crate::requests::HubError::Request)
        })
    }

    /// Focus one pane (`pane.focus {pane_id}`, the pane switcher's own
    /// switch, T36): the daemon's `PaneTarget` on the same request lane —
    /// measured from `herdr api schema --json`, protocol 22, and verified
    /// live: a shell pane is focusable, and the surface stream — which
    /// renders the focused pane — follows, so the phone's visible pane
    /// follows too.
    pub fn pane_focus(&self, pane_id: &str) -> std::result::Result<(), crate::requests::HubError> {
        let hub = self
            .requests
            .as_ref()
            .ok_or(crate::requests::HubError::Request(crate::requests::RequestError::Disconnected))?;
        hub.request(
            crate::requests::METHOD_PANE_FOCUS,
            crate::requests::pane_focus_params(pane_id),
            crate::requests::REQUEST_TIMEOUT,
        )
        .and_then(|value| {
            crate::requests::parse_pane_focus(&value).map_err(crate::requests::HubError::Request)
        })
    }

    /// Re-render at a new surface size (S28-3): the grid the card's own
    /// geometry derived (cols × rows in cells) and the cell size it renders
    /// at, in pixels — the daemon needs the cell dimensions to reflow the
    /// pane it presents (measured: a resize with zero cell dimensions only
    /// reshapes the projection grid, never the pane's own width). Rides the
    /// vendored client's resize message — the same lane the connect
    /// handshake uses — and the client's own validation refuses a size it
    /// would never render (0, > 4096 per side, area > 1M cells), so a bad
    /// derivation can never ask the daemon for a surface bigger than it
    /// renders back.
    pub fn resize_surface(
        &self,
        cols: u16,
        rows: u16,
        cell_width_px: u32,
        cell_height_px: u32,
    ) -> Result<()> {
        let handle = self
            .handle()
            .ok_or_else(|| Error::Timeout("the connection is closed"))?;
        let boot_id = self
            .snapshot()
            .map(|snapshot| snapshot.boot_id.clone())
            .ok_or_else(|| Error::Timeout("no snapshot has arrived yet"))?;
        handle
            .resize(
                &boot_id,
                ConnectOptions {
                    surface_size: herdr_client::protocol::ClientSurfaceSize { cols, rows },
                    cell_width_px,
                    cell_height_px,
                },
            )
            .map_err(Error::Client)
    }

    /// Activate (or release) this client's surface control (`client_shell
    /// .surface.set {active}`) — measured in the daemon's own source: a
    /// client-shell resize only reshapes the client's projection; the
    /// daemon reflows the tab's real layout (and with it the pane's PTY,
    /// so lines wrap at the new width) only for the client that activated
    /// its surface and thereby holds the tab's geometry control. Without
    /// the activation, a resize paints a narrow frame over a desktop-sized
    /// pane and whole words disappear off the right edge.
    ///
    /// Release (`active = false`) hands the geometry control back, so the
    /// desktop's own view reflows to its size again.
    ///
    /// The call is confirmed, not queued (T26 rule 3): it returns only once
    /// the daemon answered the request. [`SurfaceAck::Confirmed`] is the
    /// only acceptance; [`SurfaceAck::Refused`] carries the daemon's own
    /// refusal and [`SurfaceAck::Rejected`] the vendored client's — a host
    /// without surface support can never look identical to one that
    /// accepted (T26 rule 4).
    pub fn set_surface_active(&self, active: bool) -> Result<SurfaceAck> {
        let handle = self
            .handle()
            .ok_or_else(|| Error::Timeout("the connection is closed"))?;
        let boot_id = self
            .snapshot()
            .filter(|snapshot| !snapshot.boot_id.is_empty())
            .map(|snapshot| snapshot.boot_id.clone())
            .ok_or_else(|| Error::Timeout("no snapshot has arrived yet"))?;
        let request_id = handle
            .set_surface_active(&boot_id, active)
            .map_err(Error::Client)?;
        self.acks.await_ack(&request_id, SURFACE_ACK_TIMEOUT)
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
        if let Some(requests) = self.requests.as_mut() {
            requests.shutdown();
        }
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
        if let Some(requests) = self.requests.as_mut() {
            requests.shutdown();
        }
        if let Some(client) = self.client.take() {
            client.handle.disconnect();
        }
    }
}

/// Map vendored events to the product enum and track the latest snapshot.
/// Surfaces additionally land in the latest-wins [`SurfaceStore`]: the
/// throttle lives here, at the source, not in the UI.
fn spawn_forwarder(
    events: crossbeam_channel::Receiver<ClientEvent>,
    out: crossbeam_channel::Sender<Event>,
    latest: Arc<Mutex<Option<Arc<ClientShellSnapshot>>>>,
    surfaces: Arc<SurfaceStore>,
    acks: Arc<AckRouter>,
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
                                surfaces.store(surface.clone());
                                // Non-blocking on purpose: surfaces arrive at
                                // the daemon's redraw rate, far faster than a
                                // channel consumer may drain, and a full
                                // product queue must never pause the vendored
                                // pump (liveness included). The UI reads the
                                // store, not the channel.
                                ended = matches!(
                                    out.try_send(Event::Surface(surface)),
                                    Err(crossbeam_channel::TrySendError::Disconnected(_)),
                                );
                            }
                            ClientEvent::Message(message) => {
                                ended = deliver(&out, Event::Message(message));
                            }
                            ClientEvent::Disconnected { reason, .. } => {
                                // The connection is over for the product too:
                                // flip the stop flag so `is_disconnected()` —
                                // the only liveness word the UI polls — stops
                                // lying about a dead link (T8c). The forwarder
                                // ends with the connection: there is nothing
                                // left to forward.
                                stop.store(true, Ordering::Release);
                                let _ = deliver(&out, Event::Disconnected { reason });
                                ended = true;
                            }
                            ClientEvent::Response { request_id, response } => {
                                // Routed to the caller awaiting the command
                                // this answers (T26 rules 3–4); the UI's
                                // event channel stays untouched.
                                acks.route(&request_id, ack_from_response(&response));
                            }
                            ClientEvent::CommandRejected { request_id, reason } => {
                                if let Some(request_id) = request_id {
                                    acks.route(
                                        &request_id,
                                        SurfaceAck::Rejected { detail: reason.to_string() },
                                    );
                                }
                            }
                            // Connected, SurfaceImages, and VersionMismatch
                            // remain vendored-side detail; a refused version
                            // arrives again as a Disconnected reason.
                            _ => {}
                        }
                    }
                    Err(crossbeam_channel::RecvTimeoutError::Timeout) => {
                        if stop.load(Ordering::Acquire) {
                            stop.store(true, Ordering::Release);
                            let _ = deliver(
                                &out,
                                Event::Disconnected { reason: "disconnected".to_owned() },
                            );
                            ended = true;
                        }
                    }
                    Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                        // The vendored client is gone; the connection is over
                        // for the product too.
                        stop.store(true, Ordering::Release);
                        let _ = deliver(
                            &out,
                            Event::Disconnected { reason: "connection closed".to_owned() },
                        );
                        ended = true;
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
                // The worker already knows; flip the product stop flag so the
                // UI's liveness polls see it, then let the forwarder surface
                // the typed reason.
                stop.store(true, Ordering::Release);
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
/// The SSH client config both product lanes share: the main pump and the
/// request-only channel. Keepalive is load-bearing on both (see module docs).
pub(crate) fn ssh_client_config() -> Arc<client::Config> {
    Arc::new(client::Config {
        keepalive_interval: Some(KEEPALIVE_INTERVAL),
        keepalive_max: KEEPALIVE_MAX,
        inactivity_timeout: None,
        nodelay: true,
        ..client::Config::default()
    })
}

/// Read and decode the device identity key.
pub(crate) fn load_identity_key(path: &Path) -> Result<Arc<russh::keys::PrivateKey>> {
    std::fs::read_to_string(path)
        .map_err(Error::Io)
        .and_then(|secret| {
            russh::keys::decode_secret_key(&secret, None)
                .map(Arc::new)
                .map_err(russh::Error::from)
                .map_err(Error::Ssh)
        })
}

/// Authenticate `ssh` as `user` with `key`, negotiating the best RSA hash.
pub(crate) async fn authenticate_publickey(
    ssh: &mut Handle<KeyVerifier>,
    user: &str,
    key: Arc<russh::keys::PrivateKey>,
) -> Result<()> {
    let hash = ssh.best_supported_rsa_hash().await.ok().flatten().flatten();
    let state = ssh
        .authenticate_publickey(user, PrivateKeyWithHashAlg::new(key, hash))
        .await;
    match state {
        Ok(state) if state.success() => Ok(()),
        Ok(_) => Err(Error::Ssh(russh::Error::NotAuthenticated)),
        Err(error) => Err(Error::Ssh(error)),
    }
}

/// Open a session channel and exec the remote bridge for `session`.
pub(crate) async fn exec_remote_bridge(
    ssh: &mut Handle<KeyVerifier>,
    session: &str,
) -> Result<russh::Channel<client::Msg>> {
    let command = remote_bridge_command(session).map_err(Error::Client)?;
    let channel = ssh.channel_open_session().await.map_err(Error::Ssh)?;
    channel.exec(true, command).await.map_err(Error::Ssh)?;
    Ok(channel)
}

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
    let key = match load_identity_key(&profile.identity_path) {
        Ok(key) => key,
        Err(error) => {
            report(Err(error))?;
            return Ok(());
        }
    };
    let refusal = Arc::new(Mutex::new(None::<PinDecision>));
    let connect = client::connect(
        ssh_client_config(),
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
    if let Err(error) = authenticate_publickey(&mut ssh, profile.user.as_str(), key.clone()).await {
        report(Err(error))?;
        return Ok(());
    }
    let channel = match exec_remote_bridge(&mut ssh, session).await {
        Ok(channel) => channel,
        Err(error) => {
            report(Err(error))?;
            return Ok(());
        }
    };
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
/// product error. Shared by the main pump and the request-only channel.
pub struct KeyVerifier {
    host: String,
    port: u16,
    pins: Vec<HostPin>,
    refusal: Arc<Mutex<Option<PinDecision>>>,
}

impl KeyVerifier {
    /// A fresh verifier with its refusal slot: the slot is read after a
    /// failed connect to produce the typed pin error.
    pub fn new(
        host: String,
        port: u16,
        pins: Vec<HostPin>,
    ) -> (Self, Arc<Mutex<Option<PinDecision>>>) {
        let refusal = Arc::new(Mutex::new(None::<PinDecision>));
        (
            Self { host, port, pins, refusal: Arc::clone(&refusal) },
            refusal,
        )
    }
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

    use herdr_client::protocol::ClientShellSnapshot;

    // --- T26 rules 3–4: the surface command's answer reaches the caller ------

    /// A result envelope confirms; an error object is the refusal, in the
    /// daemon's own words — never a silent `_ => {}`.
    #[test]
    fn a_result_response_confirms_and_an_error_response_refuses() {
        assert_eq!(
            ack_from_response(&serde_json::json!({"result": {"type": "ok"}})),
            SurfaceAck::Confirmed,
        );
        assert_eq!(
            ack_from_response(&serde_json::json!({"id": "gpui-1"})),
            SurfaceAck::Confirmed,
        );
        assert_eq!(
            ack_from_response(&serde_json::json!({
                "error": {"code": "no_such_surface", "message": "the tab has no surface"},
            })),
            SurfaceAck::Refused {
                code: "no_such_surface".to_owned(),
                detail: "the tab has no surface".to_owned(),
            },
        );
    }

    #[test]
    fn a_routed_outcome_reaches_its_waiter_in_order() {
        let acks = AckRouter::default();
        let receiver = acks.register("gpui-7");
        acks.route("gpui-7", SurfaceAck::Confirmed);
        assert_eq!(receiver.recv_timeout(Duration::from_secs(1)).unwrap(), SurfaceAck::Confirmed);
    }

    /// The worker's own policy rejection can be delivered while the caller is
    /// still walking from the queued command to its registration — the
    /// buffered outcome must still reach it (a host without surface support
    /// must never look identical to one that accepted).
    #[test]
    fn an_outcome_that_races_ahead_of_its_waiter_is_buffered_for_it() {
        let acks = AckRouter::default();
        acks.route("gpui-1", SurfaceAck::Rejected { detail: "surface interest capabilities not advertised".to_owned() });
        let receiver = acks.register("gpui-1");
        assert_eq!(
            receiver.recv_timeout(Duration::from_secs(1)).unwrap(),
            SurfaceAck::Rejected { detail: "surface interest capabilities not advertised".to_owned() },
        );
    }

    #[test]
    fn an_outcome_for_an_unknown_command_is_buffered_bounded() {
        let acks = AckRouter::default();
        for n in 0..12u32 {
            acks.route(&format!("gpui-{n}"), SurfaceAck::Confirmed);
        }
        let state = acks.inner.lock().unwrap();
        assert_eq!(state.unclaimed.len(), 8, "the buffer is capped, never unbounded");
    }

    /// The product stop flag must flip when the vendored client reports the
    /// connection over: `is_disconnected()` is the only liveness word the UI
    /// reads (T8c), and a dead link that keeps it false would leave the app
    /// rendering the last snapshot as if it were live.
    #[test]
    fn forwarder_marks_the_connection_over_when_the_host_reports_it() {
        let (client_tx, client_rx) = crossbeam_channel::bounded::<ClientEvent>(8);
        let (out_tx, out_rx) = crossbeam_channel::bounded::<Event>(8);
        let latest = Arc::new(Mutex::new(None::<Arc<ClientShellSnapshot>>));
        let surfaces = Arc::new(SurfaceStore::new());
        let last_event = Arc::new(Mutex::new(Instant::now()));
        let stop = Arc::new(AtomicBool::new(false));
        let forwarder = spawn_forwarder(
            client_rx,
            out_tx,
            latest,
            surfaces,
            Arc::new(AckRouter::default()),
            last_event,
            stop.clone(),
        )
        .unwrap();

        client_tx
            .send(ClientEvent::Disconnected {
                reason: "keepalive gave up".to_owned(),
                ssh: None,
            })
            .unwrap();
        let event = out_rx.recv_timeout(Duration::from_secs(2)).expect("the drop must be delivered");
        assert!(matches!(event, Event::Disconnected { .. }), "{event:?}");
        assert!(
            stop.load(Ordering::Acquire),
            "a reported disconnection must flip the product stop flag"
        );

        drop(client_tx);
        let _ = forwarder.join();
    }
}
