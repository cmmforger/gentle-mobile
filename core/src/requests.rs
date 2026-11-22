//! Lane A request path: `pane.read` and `agent.prompt` over a second,
//! request-only `remote-client-bridge` exec.
//!
//! The vendored client can only send the methods baked into its closed
//! `Method` enum — `pane.read` and `agent.prompt` are not among them and the
//! vendored crates must not change — so the product speaks the vendored
//! generation-1 wire itself, read-only: length-prefixed bincode frames, an
//! endpoint hello/welcome handshake, a boot-fenced JSON request envelope, and
//! `ClientShellEndpointResponseChunk` responses. Everything is drained from
//! the same vendored protocol types, so field and variant order stays the
//! vendored wire contract.
//!
//! Honesty rules this module enforces:
//!
//! * a method the daemon does not advertise is refused locally
//!   ([`RequestError::UnsupportedMethod`]) instead of sent;
//! * a daemon error reply is carried typed (`code` + `message`) so the UI can
//!   say why;
//! * `agent.prompt` is “sent” only when the daemon replied `agent_prompted` —
//!   any other reply shape is a [`RequestError::BadResponse`].
//!
//! Reads use `source: "visible"` so what the phone shows can be compared
//! against `herdr pane read <id> --source visible` on the host.

use std::{
    path::PathBuf,
    sync::{Arc, atomic::{AtomicBool, Ordering}, mpsc},
    thread::JoinHandle,
    time::{Duration, Instant},
};

use serde_json::{Value, json};

use crate::{
    error::{Error, Result},
    profile::HostProfile,
};

/// A request-path failure, typed for the UI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RequestError {
    /// The daemon answered with an error object; `code` names the reason.
    Refused { code: String, detail: String },
    /// The daemon's advertised method list does not include this method.
    UnsupportedMethod { method: String },
    /// The reply arrived but its shape is not what this method promises.
    BadResponse { detail: String },
    /// No reply within the per-request deadline.
    Timeout { detail: String },
    /// The request channel itself is gone (never connected, or died).
    Disconnected,
    /// Anything else on the wire or the socket.
    Transport { detail: String },
}

impl std::fmt::Display for RequestError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RequestError::Refused { code, detail } => {
                write!(formatter, "the host refused the request ({code}): {detail}")
            }
            RequestError::UnsupportedMethod { method } => {
                write!(formatter, "the host does not offer {method}")
            }
            RequestError::BadResponse { detail } => {
                write!(formatter, "unexpected reply from the host: {detail}")
            }
            RequestError::Timeout { detail } => write!(formatter, "timed out: {detail}"),
            RequestError::Disconnected => write!(formatter, "the request channel is not connected"),
            RequestError::Transport { detail } => write!(formatter, "{detail}"),
        }
    }
}

impl std::error::Error for RequestError {}

/// A hub failure: either a product transport error (pin verdicts in
/// particular must keep their typed shape for the challenge UI), or a
/// request-path failure.
#[derive(Debug)]
pub enum HubError {
    Core(Error),
    Request(RequestError),
}

impl std::fmt::Display for HubError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HubError::Core(error) => write!(formatter, "{error}"),
            HubError::Request(error) => write!(formatter, "{error}"),
        }
    }
}

impl From<Error> for HubError {
    fn from(error: Error) -> Self {
        HubError::Core(error)
    }
}

impl From<RequestError> for HubError {
    fn from(error: RequestError) -> Self {
        HubError::Request(error)
    }
}

/// The methods this screen needs, in their wire spelling.
pub const METHOD_PANE_READ: &str = "pane.read";
pub const METHOD_AGENT_PROMPT: &str = "agent.prompt";
pub const METHOD_PANE_SEND_KEYS: &str = "pane.send_keys";

// --- Workspace request mappings (S26-2, S26-3) ------------------------------

/// `workspace.list` takes no parameters (the daemon's `EmptyParams`).
pub fn workspace_list_params() -> Value {
    Value::Object(serde_json::Map::new())
}

/// Map a `workspace.list` reply to the rows the screen shows. A reply
/// without its `workspaces` array is a [`RequestError::BadResponse`], never
/// an empty list (an empty list must come from the daemon saying so).
pub fn parse_workspace_list(
    response: &Value,
) -> std::result::Result<Vec<HostWorkspace>, RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let result = response
        .get("result")
        .ok_or_else(|| RequestError::BadResponse { detail: "reply has no result".to_owned() })?;
    let rows = result
        .get("workspaces")
        .and_then(Value::as_array)
        .ok_or_else(|| RequestError::BadResponse {
            detail: "the workspace list has no workspaces array".to_owned(),
        })?;
    rows.iter()
        .map(|row| {
            Ok(HostWorkspace {
                id: string_field(row, "workspace_id", "workspace row")?,
                number: row
                    .get("number")
                    .and_then(Value::as_u64)
                    .ok_or_else(|| bad_row("workspace row", "number"))?,
                label: string_field(row, "label", "workspace row")?,
                focused: row.get("focused").and_then(Value::as_bool).unwrap_or(false),
                pane_count: row
                    .get("pane_count")
                    .and_then(Value::as_u64)
                    .ok_or_else(|| bad_row("workspace row", "pane_count"))?,
                agent_status: row
                    .get("agent_status")
                    .and_then(Value::as_str)
                    .unwrap_or("unknown")
                    .to_owned(),
            })
        })
        .collect()
}

fn string_field(row: &Value, name: &str, what: &str) -> std::result::Result<String, RequestError> {
    row.get(name)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| bad_row(what, name))
}

fn bad_row(what: &str, field: &str) -> RequestError {
    RequestError::BadResponse { detail: format!("{what} has no {field}") }
}

/// `workspace.create` parameters: the absolute host directory and the
/// label. Measured live: a bad `cwd` does **not** fail — the daemon falls
/// back to `$HOME` — so the caller must validate the path (over SFTP)
/// before ever sending this request.
pub fn workspace_create_params(cwd: &str, label: Option<&str>) -> Value {
    let mut params = serde_json::Map::new();
    params.insert("cwd".to_owned(), json!(cwd));
    if let Some(label) = label {
        params.insert("label".to_owned(), json!(label));
    }
    Value::Object(params)
}

/// Map a `workspace.create` reply: `workspace_created` carries the
/// workspace and its root pane.
pub fn parse_workspace_created(
    response: &Value,
) -> std::result::Result<WorkspaceCreated, RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let workspace = response
        .get("result")
        .and_then(|result| result.get("workspace"))
        .ok_or_else(|| {
            RequestError::BadResponse { detail: "the create reply has no workspace".to_owned() }
        })?;
    Ok(WorkspaceCreated {
        workspace: parse_workspace_row(workspace)?,
        root_pane_id: response
            .get("result")
            .and_then(|result| result.get("root_pane"))
            .and_then(|pane| pane.get("pane_id"))
            .and_then(Value::as_str)
            .map(str::to_owned),
    })
}

fn parse_workspace_row(row: &Value) -> std::result::Result<HostWorkspace, RequestError> {
    Ok(HostWorkspace {
        id: string_field(row, "workspace_id", "workspace")?,
        number: row.get("number").and_then(Value::as_u64).ok_or_else(|| bad_row("workspace", "number"))?,
        label: string_field(row, "label", "workspace")?,
        focused: row.get("focused").and_then(Value::as_bool).unwrap_or(false),
        pane_count: row.get("pane_count").and_then(Value::as_u64).ok_or_else(|| bad_row("workspace", "pane_count"))?,
        agent_status: row.get("agent_status").and_then(Value::as_str).unwrap_or("unknown").to_owned(),
    })
}

/// `workspace.focus` parameters: the workspace id (the daemon's
/// `WorkspaceTarget`).
pub fn workspace_focus_params(workspace_id: &str) -> Value {
    json!({ "workspace_id": workspace_id })
}

/// Map a `workspace.focus` reply: the daemon answers `workspace_info`.
pub fn parse_workspace_focus(response: &Value) -> std::result::Result<(), RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let result_type = response
        .get("result")
        .and_then(|result| result.get("type"))
        .and_then(Value::as_str);
    match result_type {
        Some("workspace_info") => Ok(()),
        Some(other) => Err(RequestError::BadResponse {
            detail: format!("the focus reply was `{other}`, not `workspace_info`"),
        }),
        None => Err(RequestError::BadResponse {
            detail: "the focus reply has no result type".to_owned(),
        }),
    }
}

/// `tab.focus` parameters: the tab id (the daemon's `TabTarget`, measured
/// from `herdr api schema --json`, protocol 22).
pub fn tab_focus_params(tab_id: &str) -> Value {
    json!({ "tab_id": tab_id })
}

/// Map a `tab.focus` reply: the daemon answers `tab_info`, the focused
/// tab's own row — the same shape `tab.get` returns.
pub fn parse_tab_focus(response: &Value) -> std::result::Result<(), RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let result_type = response
        .get("result")
        .and_then(|result| result.get("type"))
        .and_then(Value::as_str);
    match result_type {
        Some("tab_info") => Ok(()),
        Some(other) => Err(RequestError::BadResponse {
            detail: format!("the tab focus reply was `{other}`, not `tab_info`"),
        }),
        None => Err(RequestError::BadResponse {
            detail: "the tab focus reply has no result type".to_owned(),
        }),
    }
}

/// `pane.focus` parameters: the pane id (the daemon's `PaneTarget`, same
/// schema as `pane.get`, protocol 22).
pub fn pane_focus_params(pane_id: &str) -> Value {
    json!({ "pane_id": pane_id })
}

/// Map a `pane.focus` reply: the daemon answers `pane_info`, the focused
/// pane's own row — the same shape `pane.get` returns.
pub fn parse_pane_focus(response: &Value) -> std::result::Result<(), RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let result_type = response
        .get("result")
        .and_then(|result| result.get("type"))
        .and_then(Value::as_str);
    match result_type {
        Some("pane_info") => Ok(()),
        Some(other) => Err(RequestError::BadResponse {
            detail: format!("the pane focus reply was `{other}`, not `pane_info`"),
        }),
        None => Err(RequestError::BadResponse {
            detail: "the pane focus reply has no result type".to_owned(),
        }),
    }
}

/// `worktree.list` parameters: one workspace id — the daemon inspects the
/// git repository behind that workspace's panes. Sent **without**
/// `trust_repository`: measured read-only, and the app must never silently
/// trust the human's repositories.
pub fn worktree_list_params(workspace_id: &str) -> Value {
    json!({ "workspace_id": workspace_id })
}

/// The branch a workspace's repository is on, from the `worktree.list`
/// reply. The worktree the workspace itself opened wins; with exactly one
/// worktree in the reply the repo root's branch stands in. `Ok(None)` when
/// the reply names no branch for this workspace.
pub fn parse_worktree_branch(
    response: &Value,
    workspace_id: &str,
) -> std::result::Result<Option<String>, RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let worktrees = response
        .get("result")
        .and_then(|result| result.get("worktrees"))
        .and_then(Value::as_array)
        .ok_or_else(|| {
            RequestError::BadResponse { detail: "the worktree list has no worktrees".to_owned() }
        })?;
    let ours = |worktree: &Value| {
        worktree
            .get("open_workspace_id")
            .and_then(Value::as_str)
            .is_some_and(|opened| opened == workspace_id)
    };
    if let Some(own) = worktrees.iter().find(|worktree| ours(worktree)) {
        return Ok(own.get("branch").and_then(Value::as_str).map(str::to_owned));
    }
    if worktrees.len() == 1 {
        return Ok(worktrees[0].get("branch").and_then(Value::as_str).map(str::to_owned));
    }
    Ok(None)
}

impl RequestError {
    /// From a JSON reply envelope: `Some` when the daemon answered with an
    /// error object.
    fn refused_from(response: &Value) -> Option<RequestError> {
        let error = response.get("error")?;
        let code = error
            .get("code")
            .and_then(Value::as_str)
            .unwrap_or("unknown")
            .to_owned();
        let detail = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("the host gave no reason")
            .to_owned();
        Some(RequestError::Refused { code, detail })
    }
}

/// A successful `pane.read`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PaneRead {
    pub pane_id: String,
    pub text: String,
    pub truncated: bool,
}

/// The workspace methods the workspaces screen needs, in their wire
/// spelling (S26-2, S26-3). All live on the daemon's API socket, the same
/// streamlocal request lane as `pane.read` and `agent.prompt`.
pub const METHOD_WORKSPACE_LIST: &str = "workspace.list";
pub const METHOD_WORKSPACE_CREATE: &str = "workspace.create";
pub const METHOD_WORKSPACE_FOCUS: &str = "workspace.focus";
pub const METHOD_TAB_FOCUS: &str = "tab.focus";
pub const METHOD_PANE_FOCUS: &str = "pane.focus";
pub const METHOD_WORKTREE_LIST: &str = "worktree.list";

/// One workspace as `workspace.list` reports it. Shapes measured live on
/// protocol 22 (`herdr api schema --json` + a raw socket call): every field
/// except `worktree`/`tokens` is required; `agent_status` is the daemon's
/// aggregate word for the workspace (`idle`/`working`/`blocked`/`done`/
/// `unknown`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostWorkspace {
    pub id: String,
    pub number: u64,
    pub label: String,
    pub focused: bool,
    pub pane_count: u64,
    pub agent_status: String,
}

/// A created workspace: the workspace itself plus its root pane's id (the
/// pane `agent.list` and the inbox address agents by).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceCreated {
    pub workspace: HostWorkspace,
    pub root_pane_id: Option<String>,
}

/// `pane.read` parameters: the pane's visible text, plain, ANSI stripped, so
/// the app shows what the host's own `herdr pane read --source visible` shows.
pub fn pane_read_params(pane_id: &str) -> Value {
    json!({"pane_id": pane_id, "source": "visible", "format": "text", "strip_ansi": true})
}

/// `agent.prompt` parameters: one prompt addressed by pane id, submitted by
/// the daemon itself — focus, text, and Enter — never raw keystrokes.
pub fn prompt_params(target: &str, text: &str) -> Value {
    json!({"target": target, "text": text})
}

/// Refuse a method the daemon's welcome does not advertise. An empty list
/// also refuses: sending into an unchecked dark would violate R1.
pub fn ensure_advertised(method: &str, methods: &[String]) -> std::result::Result<(), RequestError> {
    if methods.iter().any(|offered| offered == method) {
        return Ok(());
    }
    Err(RequestError::UnsupportedMethod { method: method.to_owned() })
}

/// Map a `pane.read` reply envelope to a [`PaneRead`]. The daemon's typed
/// error answers stay refused-with-code; a well-formed reply that is missing
/// the fields this method promises is a [`RequestError::BadResponse`].
pub fn parse_pane_read(response: &Value) -> std::result::Result<PaneRead, RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let result = response
        .get("result")
        .ok_or_else(|| RequestError::BadResponse { detail: "reply has no result".to_owned() })?;
    // `ResponseResult::PaneRead` carries the body inside `result.read`; a
    // flat body is accepted too, so a future refactor cannot silently change
    // the shape the UI depends on.
    let read = result.get("read").unwrap_or(result);
    let pane_id = read
        .get("pane_id")
        .and_then(Value::as_str)
        .ok_or_else(|| RequestError::BadResponse { detail: "pane read has no pane_id".to_owned() })?
        .to_owned();
    let text = read
        .get("text")
        .and_then(Value::as_str)
        .ok_or_else(|| RequestError::BadResponse { detail: "pane read has no text".to_owned() })?
        .to_owned();
    let truncated = read.get("truncated").and_then(Value::as_bool).unwrap_or(false);
    Ok(PaneRead { pane_id, text, truncated })
}

/// `pane.send_keys` parameters: one request carrying the key list, addressed
/// by pane id. Key names are the daemon's own, measured live: `esc` (also
/// `escape`), `tab`, `enter`, `up`/`down`/`left`/`right`, `backspace`,
/// `space`, `f1`–`f12`, and single characters — with `ctrl+`/`alt+`/`shift+`
/// prefixes composing modifiers (`ctrl+c`).
pub fn send_keys_params(pane_id: &str, keys: &[String]) -> Value {
    json!({"pane_id": pane_id, "keys": keys})
}

/// Map a `pane.send_keys` reply envelope: “sent” only on the daemon's `ok`
/// result — the measured success shape is `{"result":{"type":"ok"}}`.
pub fn parse_send_keys(response: &Value) -> std::result::Result<(), RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let result_type = response
        .get("result")
        .and_then(|result| result.get("type"))
        .and_then(Value::as_str);
    match result_type {
        Some("ok") => Ok(()),
        Some(other) => Err(RequestError::BadResponse {
            detail: format!("the send-keys reply was `{other}`, not `ok`"),
        }),
        None => Err(RequestError::BadResponse {
            detail: "the send-keys reply has no result type".to_owned(),
        }),
    }
}

/// Map an `agent.prompt` reply envelope: “sent” only on the daemon's
/// `agent_prompted` result.
pub fn parse_agent_prompt(response: &Value) -> std::result::Result<(), RequestError> {
    if let Some(refused) = RequestError::refused_from(response) {
        return Err(refused);
    }
    let result_type = response
        .get("result")
        .and_then(|result| result.get("type"))
        .and_then(Value::as_str);
    match result_type {
        Some("agent_prompted") => Ok(()),
        Some(other) => Err(RequestError::BadResponse {
            detail: format!("the prompt reply was `{other}`, not `agent_prompted`"),
        }),
        None => Err(RequestError::BadResponse {
            detail: "the prompt reply has no result type".to_owned(),
        }),
    }
}

// --- The request hub -------------------------------------------------------
// Below the pure mappings: the worker that owns the request channel. The
// daemon's `pane.read` and `agent.prompt` live on its API socket (protocol
// 22, newline-delimited JSON — one request line, one response line), which
// the phone reaches over SSH with a `direct-streamlocal` channel: same pins,
// same key, same keepalive as the main pump, but a separate session.
//
// No vendor wrapping for this lane: the vendored client crate can only send
// its closed `Method` enum, and the endpoint (client-shell) lane does not
// offer these methods — measured: its advertised list refused `pane.read`.
// The JSON envelope shapes here are the daemon's own (`Request`/`Success`/
// `ErrorResponse`), which the mapping functions above consume.

/// One request/response cycle over the API socket. Generous because a
/// refused method must not be mistaken for a dead link.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Idle cadence of the worker between jobs.
const IDLE_TICK: Duration = Duration::from_millis(200);

struct Job {
    method: String,
    params: Value,
    timeout: Duration,
    reply: crossbeam_channel::Sender<std::result::Result<Value, HubError>>,
}

fn reply(job: &Job, outcome: std::result::Result<Value, HubError>) {
    let _ = job.reply.send(outcome);
}

/// A request-only link to the host. Owns one worker thread; the worker owns
/// at most one live SSH channel and re-establishes it on demand.
pub struct RequestsHub {
    jobs: Option<mpsc::Sender<Job>>,
    stop: Option<Arc<AtomicBool>>,
    worker: Option<JoinHandle<()>>,
}

impl RequestsHub {
    /// Spawn the worker. It connects lazily: no SSH session until the first
    /// request actually needs one.
    pub fn spawn(profile: HostProfile, session: String) -> Result<Self> {
        let (jobs, receiver) = mpsc::channel::<Job>();
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let worker = std::thread::Builder::new()
            .name("gentlecompanion-requests".to_owned())
            .spawn(move || worker_loop(receiver, worker_stop, profile, session))
            .map_err(Error::Io)?;
        Ok(Self { jobs: Some(jobs), stop: Some(stop), worker: Some(worker) })
    }

    /// Send one request and wait for the correlated reply envelope.
    pub fn request(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> std::result::Result<Value, HubError> {
        let jobs = self.jobs.as_ref().ok_or(HubError::Request(RequestError::Disconnected))?;
        let (reply_tx, reply_rx) = crossbeam_channel::bounded(1);
        jobs.send(Job { method: method.to_owned(), params, timeout, reply: reply_tx })
            .map_err(|_| HubError::Request(RequestError::Disconnected))?;
        // Every job bounds itself through the worker's own timeouts; this
        // outer bound only covers a wedged worker.
        match reply_rx.recv_timeout(timeout + crate::connection::SETUP_TIMEOUT) {
            Ok(outcome) => outcome,
            Err(_) => Err(HubError::Request(RequestError::Timeout {
                detail: "the request hub never answered".to_owned(),
            })),
        }
    }

    /// Stop the worker and tear down any live request channel.
    pub fn shutdown(&mut self) {
        if let Some(stop) = self.stop.as_ref() {
            stop.store(true, Ordering::Release);
        }
        self.jobs.take();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        self.stop = None;
    }
}

impl Drop for RequestsHub {
    fn drop(&mut self) {
        self.shutdown();
    }
}

/// Serialized request jobs, one live channel at a time.
fn worker_loop(
    jobs: mpsc::Receiver<Job>,
    stop: Arc<AtomicBool>,
    profile: HostProfile,
    session: String,
) {
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(error) => {
            // No runtime means no request can ever run: drain and refuse.
            for job in jobs.try_iter() {
                reply(&job, Err(HubError::Request(RequestError::Transport {
                    detail: format!("the request worker could not start: {error}"),
                })));
            }
            return;
        }
    };
    let mut link: Option<ApiLink> = None;
    loop {
        if stop.load(Ordering::Acquire) {
            break;
        }
        match jobs.recv_timeout(IDLE_TICK) {
            Ok(job) => {
                if stop.load(Ordering::Acquire) {
                    reply(&job, Err(HubError::Request(RequestError::Disconnected)));
                    break;
                }
                if link.is_none() {
                    link = match runtime.block_on(ApiLink::connect(&profile, &session, &stop)) {
                        Ok(link) => Some(link),
                        Err(error) => {
                            // Establishment failures answer this job only;
                            // the next job gets its own fresh attempt.
                            reply(&job, Err(error));
                            continue;
                        }
                    };
                }
                let outcome = match link.as_mut() {
                    Some(link) => runtime.block_on(self_request(link, &job)),
                    None => Err(HubError::Request(RequestError::Disconnected)),
                };
                let keeps_session = matches!(
                    &outcome,
                    Ok(_)
                        | Err(HubError::Request(
                            RequestError::Refused { .. } | RequestError::BadResponse { .. },
                        )),
                );
                if !keeps_session {
                    // A broken link is torn down so the next request
                    // reconnects; a refusal or a well-formed odd reply keeps it.
                    link = None;
                }
                reply(&job, outcome);
            }
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    // Dropping the link closes the channel and the SSH session.
}

/// One request over the daemon's API socket: a fresh streamlocal channel
/// (the daemon serves one request per connection), the request line out, and
/// exactly one reply line back.
async fn self_request(link: &mut ApiLink, job: &Job) -> std::result::Result<Value, HubError> {
    let id = link.next_id();
    let envelope = json!({"id": id, "method": job.method, "params": job.params}).to_string();
    let socket_path = api_socket_path(&link.user, &link.session);
    let channel = link
        .ssh
        .channel_open_direct_streamlocal(socket_path.to_string_lossy().as_ref())
        .await
        .map_err(|error| {
            HubError::Request(RequestError::Transport {
                detail: format!("the request channel could not open: {error}"),
            })
        })?;
    let outcome = exchange(&tokio::sync::Mutex::new((channel, Vec::new())), id.as_str(), &envelope, job.timeout).await;
    drop_channel_link(outcome)
}

/// Exchange one envelope on an owned channel, then drop the channel.
#[allow(clippy::type_complexity)]
async fn exchange(
    owned: &tokio::sync::Mutex<(russh::Channel<russh::client::Msg>, Vec<u8>)>,
    id: &str,
    envelope: &str,
    timeout: Duration,
) -> std::result::Result<Value, HubError> {
    let deadline = Instant::now() + timeout;
    let mut guard = owned.lock().await;
    let (channel, line_buffer) = &mut *guard;
    write_line(channel, envelope.as_bytes()).await?;
    let line = read_line_until(channel, line_buffer, deadline).await?;
    let response: Value = serde_json::from_str(line.as_str()).map_err(|error| {
        HubError::Request(RequestError::BadResponse {
            detail: format!("the reply was not JSON: {error}"),
        })
    })?;
    if response.get("id").and_then(Value::as_str) != Some(id) {
        return Err(HubError::Request(RequestError::BadResponse {
            detail: "the reply names another request".to_owned(),
        }));
    }
    Ok(response)
}

/// The channel is per-request, so its drop always ends the daemon's
/// connection; wire failures are mapped before that.
#[allow(clippy::needless_pass_by_value)]
fn drop_channel_link(outcome: std::result::Result<Value, HubError>) -> std::result::Result<Value, HubError> {
    outcome
}

/// One live SSH session for the request lane. The daemon's API server
/// serves exactly one request per socket connection (its own `ApiClient`
/// does the same locally), so each request opens a fresh `direct-streamlocal`
/// channel and the session stays alive between them.
struct ApiLink {
    /// Held, not read: dropping it would close the SSH session the
    /// per-request streamlocal channels ride on.
    #[allow(dead_code)]
    ssh: russh::client::Handle<crate::connection::KeyVerifier>,
    user: String,
    session: String,
    request_counter: u64,
}

impl ApiLink {
    /// Connect over SSH (same pins, same key, same keepalive) and open the
    /// streamlocal channel to the daemon's API socket.
    async fn connect(
        profile: &HostProfile,
        session: &str,
        stop: &AtomicBool,
    ) -> std::result::Result<Self, HubError> {
        let ssh = connect_pinned_ssh(profile, stop).await?;
        Ok(Self { ssh, user: profile.user.clone(), session: session.to_owned(), request_counter: 0 })
    }

    fn next_id(&mut self) -> String {
        self.request_counter += 1;
        format!("companion-{}", self.request_counter)
    }
}

/// One pinned, authenticated SSH session — the handshake every lane shares
/// (requests and the SFTP upload lane alike): TCP wait, loaded pins, the
/// device key, and the same [`KeyVerifier`]. Unknown or mismatched host keys
/// are typed pin errors, never auto-accepted.
pub(crate) async fn connect_pinned_ssh(
    profile: &HostProfile,
    stop: &AtomicBool,
) -> std::result::Result<russh::client::Handle<crate::connection::KeyVerifier>, HubError> {
    if stop.load(Ordering::Acquire) {
        return Err(HubError::Request(RequestError::Disconnected));
    }
    crate::connection::wait_for_sshd(&profile.host, profile.port)?;
    let pins = crate::pin::load_entries(&profile.known_hosts_path)?;
    let key = crate::connection::load_identity_key(&profile.identity_path)?;
    let (verifier, refusal) =
        crate::connection::KeyVerifier::new(profile.host.clone(), profile.port, pins);
    let connect = russh::client::connect(
        crate::connection::ssh_client_config(),
        (profile.host.as_str(), profile.port),
        verifier,
    )
    .await;
    let mut ssh: russh::client::Handle<crate::connection::KeyVerifier> = match connect {
        Ok(ssh) => ssh,
        Err(error) => {
            let decision = refusal.lock().ok().and_then(|guarded| guarded.clone());
            return Err(decision
                .and_then(|decision| {
                    crate::pin::refusal_error(&profile.host, profile.port, &decision)
                })
                .unwrap_or(Error::Ssh(error))
                .into());
        }
    };
    crate::connection::authenticate_publickey(&mut ssh, profile.user.as_str(), key).await?;
    Ok(ssh)
}

async fn write_line(
    channel: &mut russh::Channel<russh::client::Msg>,
    line: &[u8],
) -> std::result::Result<(), HubError> {
    use tokio::io::AsyncWriteExt as _;
    let mut data = line.to_vec();
    data.push(b'\n');
    let mut writer = channel.make_writer();
    writer.write_all(&data).await.map_err(|error| {
        HubError::Request(RequestError::Transport { detail: error.to_string() })
    })?;
    writer.flush().await.map_err(|error| {
        HubError::Request(RequestError::Transport { detail: error.to_string() })
    })
}

/// Read one newline-terminated reply line. The daemon answers exactly one
/// line per request; a partial line past the deadline is a timeout.
async fn read_line_until(
    channel: &mut russh::Channel<russh::client::Msg>,
    buffered: &mut Vec<u8>,
    deadline: Instant,
) -> std::result::Result<String, HubError> {
    use tokio::io::AsyncReadExt as _;
    loop {
        if let Some(newline) = buffered.iter().position(|byte| *byte == b'\n') {
            let mut line: Vec<u8> = buffered.drain(..=newline).collect();
            while line.last() == Some(&b'\n') || line.last() == Some(&b'\r') {
                line.pop();
            }
            return Ok(String::from_utf8_lossy(&line).into_owned());
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(HubError::Request(RequestError::Timeout {
                detail: "the host never answered in time".to_owned(),
            }));
        }
        let mut chunk = vec![0u8; 16 * 1024];
        match tokio::time::timeout(remaining, channel.make_reader().read(&mut chunk)).await {
            Err(_) => {
                return Err(HubError::Request(RequestError::Timeout {
                    detail: "the host never answered in time".to_owned(),
                }));
            }
            Ok(Err(error)) => {
                buffered.clear();
                return Err(HubError::Request(RequestError::Transport {
                    detail: error.to_string(),
                }));
            }
            Ok(Ok(0)) => {
                buffered.clear();
                return Err(HubError::Request(RequestError::Disconnected));
            }
            Ok(Ok(read)) => buffered.extend_from_slice(&chunk[..read]),
        }
    }
}

/// The daemon's API socket path for `session`, mirroring how the host's
/// daemon derives it (herdr `config_dir`/`api_socket_path_for`): the config
/// root is `XDG_CONFIG_HOME` when this process has one, else the standard
/// Linux home of the user, then `herdr` — or `herdr/sessions/<name>` for a
/// named session — with `herdr.sock`. Android has no `HOME` env, so the
/// user-based default is the device-side rule: a path the SSH server can
/// resolve absolutely.
fn api_socket_path(user: &str, session: &str) -> PathBuf {
    let root = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/home").join(user).join(".config"));
    let dir = if session == "default" || session.is_empty() {
        root.join("herdr")
    } else {
        root.join("herdr").join("sessions").join(session)
    };
    dir.join("herdr.sock")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

/// The measured `SuccessResponse` shape: `result` is serde-tagged, and the
/// pane read body rides inside `result.read` (`ResponseResult::PaneRead`).
fn read_response(text: &str, truncated: bool) -> Value {
    json!({
        "id": "req-1",
        "result": {
            "type": "pane_read",
            "read": {
                "pane_id": "w1:p1",
                "workspace_id": "w1",
                "tab_id": "t1",
                "source": "visible",
                "format": "text",
                "text": text,
                "revision": 7,
                "truncated": truncated
            }
        }
    })
}

fn refused_response(code: &str, message: &str) -> Value {
    json!({
        "id": "req-1",
        "error": {"code": code, "message": message}
    })
}

#[test]
fn pane_read_params_ask_for_the_visible_text() {
    let params = pane_read_params("w1:p1");
    assert_eq!(params["pane_id"], json!("w1:p1"));
    assert_eq!(params["source"], json!("visible"));
    assert_eq!(params["format"], json!("text"));
    assert_eq!(params["strip_ansi"], json!(true));
}

#[test]
fn prompt_params_carry_target_and_text() {
    let params = prompt_params("w1:p1", "echo t11-ok");
    assert_eq!(params["target"], json!("w1:p1"));
    assert_eq!(params["text"], json!("echo t11-ok"));
}

#[test]
fn send_keys_params_carry_pane_and_key_list() {
    let params = send_keys_params("w1:p1", &["c".to_owned(), "l".to_owned(), "tab".to_owned()]);
    assert_eq!(params["pane_id"], json!("w1:p1"));
    assert_eq!(params["keys"], json!(["c", "l", "tab"]));
}

#[test]
fn send_keys_params_keep_modifier_composition_verbatim() {
    // The daemon accepts `ctrl+c` as one key string; the core passes the
    // caller's names through untouched — no local re-spelling that could
    // drift from the daemon's parser.
    let params = send_keys_params("w1:p1", &["ctrl+c".to_owned()]);
    assert_eq!(params["keys"], json!(["ctrl+c"]));
}

fn send_keys_ok_response() -> Value {
    // Measured live: the daemon's success shape for pane.send_keys.
    json!({"id": "companion-1", "result": {"type": "ok"}})
}

#[test]
fn send_keys_success_is_accepted_only_on_result_ok() {
    assert!(parse_send_keys(&send_keys_ok_response()).is_ok(), "result ok is a send");
    // Any other result shape does not prove the keys were delivered.
    let other = json!({"id": "companion-1", "result": {"type": "pane_read"}});
    let error = parse_send_keys(&other).expect_err("an unknown result is not a send");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
    let none = json!({"id": "companion-1"});
    let error = parse_send_keys(&none).expect_err("no result is not a send");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn send_keys_refusals_carry_the_daemons_code() {
    for (code, message) in [
        ("invalid_key", "unsupported key definitely-not-a-key"),
        ("pane_not_found", "pane w99:p9 not found"),
    ] {
        let error = parse_send_keys(&refused_response(code, message))
            .expect_err("a refusal is an error");
        match error {
            RequestError::Refused { code: got, detail } => {
                assert_eq!(got, code);
                assert_eq!(detail, message);
            }
            other => panic!("expected RequestError::Refused for {code}, got {other:?}"),
        }
    }
}

#[test]
fn pane_read_response_parses_text_and_truncation() {
    let read = parse_pane_read(&read_response("hello\nworld", false)).expect("must parse");
    assert_eq!(read.pane_id, "w1:p1");
    assert_eq!(read.text, "hello\nworld");
    assert!(!read.truncated);
}

#[test]
fn pane_read_empty_is_empty_text_not_an_error() {
    let read = parse_pane_read(&read_response("", false)).expect("an empty pane is a valid read");
    assert_eq!(read.text, "");
    assert!(!read.truncated, "an empty pane is not a truncated pane");
}

#[test]
fn pane_read_truncated_is_reported_honestly() {
    let read = parse_pane_read(&read_response("tail only", true)).expect("must parse");
    assert!(read.truncated);
}

#[test]
fn pane_read_refusal_maps_to_typed_code_and_detail() {
    let error = parse_pane_read(&refused_response("pane_not_found", "no such pane"))
        .expect_err("a refusal is an error");
    match error {
        RequestError::Refused { code, detail } => {
            assert_eq!(code, "pane_not_found");
            assert_eq!(detail, "no such pane");
        }
        other => panic!("expected RequestError::Refused, got {other:?}"),
    }
}

#[test]
fn malformed_pane_read_is_a_bad_response_not_a_transport_fault() {
    // A well-formed reply missing the fields we read is the daemon's shape
    // drifting: typed as BadResponse, not a broken link.
    let error = parse_pane_read(&json!({"id": "req-1", "result": {"type": "pane_read"}}))
        .expect_err("a missing read body must not parse");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
    // A read body without its text is the same honesty rule.
    let error = parse_pane_read(&json!({
        "id": "req-1",
        "result": {"type": "pane_read", "read": {"pane_id": "w1:p1", "truncated": false}}
    }))
    .expect_err("missing text must not parse");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn advertised_list_gates_our_methods() {
    let methods = vec!["pane.read".to_owned(), "agent.prompt".to_owned()];
    assert!(ensure_advertised("pane.read", &methods).is_ok());
    assert!(ensure_advertised("agent.prompt", &methods).is_ok());
    let error = ensure_advertised("agent.read", &methods).expect_err("unoffered method is refused");
    match error {
        RequestError::UnsupportedMethod { method } => assert_eq!(method, "agent.read"),
        other => panic!("expected RequestError::UnsupportedMethod, got {other:?}"),
    }
    // An empty advertised list means this daemon offers nothing we can check:
    // fail closed rather than sending into the dark.
    assert!(ensure_advertised("pane.read", &[]).is_err());
}

#[test]
fn agent_prompt_success_is_accepted_only_on_agent_prompted() {
    let accepted = json!({
        "id": "req-1",
        "result": {
            "type": "agent_prompted",
            "agent": {"agent_status": "working", "name": "t11-verify"}
        }
    });
    assert!(parse_agent_prompt(&accepted).is_ok(), "agent_prompted is a send");
    // Any other result shape does not prove the prompt was submitted.
    let other = json!({"id": "req-1", "result": {"type": "ok"}});
    let error = parse_agent_prompt(&other).expect_err("an unknown result is not a send");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn agent_prompt_refusals_carry_the_daemons_code() {
    for (code, _) in [
        ("agent_blocked", "agent is blocked"),
        ("agent_not_found", "no such agent"),
        ("empty_agent_prompt", "agent prompt must not be empty"),
        ("agent_not_ready", "agent is not ready"),
        ("timeout", "the wait ran out"),
    ] {
        let error = parse_agent_prompt(&refused_response(code, "message"))
            .expect_err("a refusal is an error");
        match error {
            RequestError::Refused { code: got, .. } => assert_eq!(got, code),
            other => panic!("expected RequestError::Refused for {code}, got {other:?}"),
        }
    }
}

// --- Workspace requests (S26-2, S26-3) -----------------------------------

/// The measured `workspace.list` success shape (raw socket call, protocol
/// 22): `result` is tagged `workspace_list`, rows carry the required
/// `WorkspaceInfo` fields; `worktree`/`tokens` are optional and absent on
/// this host's workspaces.
fn workspace_list_response() -> Value {
    json!({
        "id": "cli:workspace:list",
        "result": {
            "type": "workspace_list",
            "workspaces": [
                {
                    "workspace_id": "wN",
                    "number": 1,
                    "label": "ERP-WEBV2",
                    "focused": false,
                    "pane_count": 3,
                    "tab_count": 3,
                    "active_tab_id": "wN:tB",
                    "agent_status": "unknown"
                },
                {
                    "workspace_id": "w1F",
                    "number": 8,
                    "label": "gentle-mobile",
                    "focused": true,
                    "pane_count": 3,
                    "tab_count": 3,
                    "active_tab_id": "w1F:t1",
                    "agent_status": "done"
                }
            ]
        }
    })
}

#[test]
fn workspace_list_params_are_empty() {
    assert_eq!(workspace_list_params(), json!({}));
}

#[test]
fn workspace_list_parses_the_measured_sample() {
    let rows = parse_workspace_list(&workspace_list_response()).expect("must parse");
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].id, "wN");
    assert_eq!(rows[0].number, 1);
    assert_eq!(rows[0].label, "ERP-WEBV2");
    assert!(!rows[0].focused);
    assert_eq!(rows[0].pane_count, 3);
    assert_eq!(rows[0].agent_status, "unknown");
    assert_eq!(rows[1].id, "w1F");
    assert_eq!(rows[1].label, "gentle-mobile");
    assert!(rows[1].focused);
    assert_eq!(rows[1].agent_status, "done");
}

#[test]
fn workspace_list_refusal_maps_to_typed_code() {
    let error = parse_workspace_list(&refused_response("error_response", "daemon down"))
        .expect_err("a refusal is an error");
    match error {
        RequestError::Refused { code, detail } => {
            assert_eq!(code, "error_response");
            assert_eq!(detail, "daemon down");
        }
        other => panic!("expected RequestError::Refused, got {other:?}"),
    }
}

#[test]
fn workspace_list_without_its_array_is_a_bad_response() {
    // An empty list must come from the daemon saying so, never from a
    // reply we failed to read: a missing array is a shape drift.
    let error = parse_workspace_list(&json!({"id": "x", "result": {"type": "workspace_list"}}))
        .expect_err("no workspaces array must not parse");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn workspace_create_params_carry_cwd_and_label() {
    let params = workspace_create_params("/srv/repo", Some("repo"));
    assert_eq!(params["cwd"], json!("/srv/repo"));
    assert_eq!(params["label"], json!("repo"));
    // No label: the field is omitted (nullable), not sent as a string.
    let params = workspace_create_params("/srv/repo", None);
    assert_eq!(params["cwd"], json!("/srv/repo"));
    assert!(params.get("label").is_none() || params["label"].is_null());
}

/// The measured `workspace.create` success shape (my scratch repo, t36).
fn workspace_created_response() -> Value {
    json!({
        "id": "companion-1",
        "result": {
            "type": "workspace_created",
            "workspace": {
                "workspace_id": "w1H",
                "number": 10,
                "label": "t36-repo",
                "focused": false,
                "pane_count": 1,
                "tab_count": 1,
                "active_tab_id": "w1H:t1",
                "agent_status": "unknown"
            },
            "tab": {"tab_id": "w1H:t1", "workspace_id": "w1H", "number": 1,
                     "label": "1", "focused": false, "pane_count": 1,
                     "agent_status": "unknown"},
            "root_pane": {
                "pane_id": "w1H:p1",
                "workspace_id": "w1H",
                "tab_id": "w1H:t1",
                "cwd": "/mnt/WD-Black/test_tmp/gentle-companion/t36-repo"
            }
        }
    })
}

#[test]
fn workspace_created_parses_workspace_and_root_pane() {
    let created = parse_workspace_created(&workspace_created_response()).expect("must parse");
    assert_eq!(created.workspace.id, "w1H");
    assert_eq!(created.workspace.label, "t36-repo");
    assert_eq!(created.workspace.pane_count, 1);
    assert_eq!(created.root_pane_id.as_deref(), Some("w1H:p1"));
}

#[test]
fn workspace_create_refusal_maps_to_typed_code() {
    // Measured live: a permission-denied directory is refused with the
    // daemon's own code, not a fallback or a success.
    let error = parse_workspace_created(&refused_response(
        "workspace_create_failed",
        "Permission denied (os error 13)",
    ))
    .expect_err("a refusal is an error");
    match error {
        RequestError::Refused { code, detail } => {
            assert_eq!(code, "workspace_create_failed");
            assert_eq!(detail, "Permission denied (os error 13)");
        }
        other => panic!("expected RequestError::Refused, got {other:?}"),
    }
}

#[test]
fn workspace_created_without_a_workspace_is_a_bad_response() {
    let error = parse_workspace_created(&json!({
        "id": "companion-1",
        "result": {"type": "workspace_created"}
    }))
    .expect_err("no workspace must not parse");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn workspace_focus_params_carry_the_workspace_id() {
    let params = workspace_focus_params("w1H");
    assert_eq!(params, json!({"workspace_id": "w1H"}));
}

#[test]
fn workspace_focus_accepts_the_workspace_info_result() {
    // Measured live: the focus reply is the focused workspace info.
    let reply = json!({
        "id": "companion-1",
        "result": {
            "type": "workspace_info",
            "workspace": {
                "workspace_id": "w1H", "number": 10, "label": "t36-repo",
                "focused": true, "pane_count": 1, "tab_count": 1,
                "active_tab_id": "w1H:t1", "agent_status": "unknown"
            }
        }
    });
    assert!(parse_workspace_focus(&reply).is_ok(), "workspace_info is a focus");
    let error = parse_workspace_focus(&json!({"id": "x", "result": {"type": "ok"}}))
        .expect_err("an unknown result shape is not a focus proof");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn workspace_focus_refusal_names_the_workspace_code() {
    // Measured live: `workspace_not_found` for an unknown id.
    let error = parse_workspace_focus(&refused_response(
        "workspace_not_found",
        "workspace wZZZ not found",
    ))
    .expect_err("a refusal is an error");
    match error {
        RequestError::Refused { code, .. } => assert_eq!(code, "workspace_not_found"),
        other => panic!("expected RequestError::Refused, got {other:?}"),
    }
}

#[test]
fn tab_focus_params_carry_the_tab_id() {
    let params = tab_focus_params("w1F:t3");
    assert_eq!(params, json!({"tab_id": "w1F:t3"}));
}

#[test]
fn tab_focus_accepts_the_tab_info_result() {
    // Measured live: `herdr tab focus w1F:t3` answers `tab_info` with the
    // focused tab's row — the daemon allows focusing a tab with no agent.
    let reply = json!({
        "id": "companion-1",
        "result": {
            "type": "tab_info",
            "tab": {
                "tab_id": "w1F:t3", "workspace_id": "w1F", "number": 3,
                "label": "2", "focused": true, "pane_count": 1,
                "agent_status": "unknown"
            }
        }
    });
    assert!(parse_tab_focus(&reply).is_ok(), "tab_info is a focus");
    let error = parse_tab_focus(&json!({"id": "x", "result": {"type": "ok"}}))
        .expect_err("an unknown result shape is not a focus proof");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn tab_focus_refusal_names_the_tab_code() {
    // The daemon's typed refusal for an unknown tab id.
    let error = parse_tab_focus(&refused_response(
        "tab_not_found",
        "tab wZZZ:tX not found",
    ))
    .expect_err("a refusal is an error");
    match error {
        RequestError::Refused { code, .. } => assert_eq!(code, "tab_not_found"),
        other => panic!("expected RequestError::Refused, got {other:?}"),
    }
}

#[test]
fn pane_focus_params_carry_the_pane_id() {
    let params = pane_focus_params("w1G:p2");
    assert_eq!(params, json!({"pane_id": "w1G:p2"}));
}

#[test]
fn pane_focus_accepts_the_pane_info_result() {
    // Measured live: `herdr pane focus w1G:p2` answers `pane_info` with the
    // focused pane's row; a shell pane is focusable, so the switcher can
    // reach a pane no agent runs in.
    let reply = json!({
        "id": "companion-1",
        "result": {
            "type": "pane_info",
            "pane": {
                "pane_id": "w1G:p2", "workspace_id": "w1G", "tab_id": "w1G:t1",
                "agent_status": "unknown"
            }
        }
    });
    assert!(parse_pane_focus(&reply).is_ok(), "pane_info is a focus");
    let error = parse_pane_focus(&json!({"id": "x", "result": {"type": "ok"}}))
        .expect_err("an unknown result shape is not a focus proof");
    assert!(matches!(error, RequestError::BadResponse { .. }), "{error:?}");
}

#[test]
fn pane_focus_refusal_names_the_pane_code() {
    // The daemon's typed refusal for an unknown pane id.
    let error = parse_pane_focus(&refused_response(
        "pane_not_found",
        "pane wZZZ:pX not found",
    ))
    .expect_err("a refusal is an error");
    match error {
        RequestError::Refused { code, .. } => assert_eq!(code, "pane_not_found"),
        other => panic!("expected RequestError::Refused, got {other:?}"),
    }
}

#[test]
fn worktree_list_params_carry_the_workspace_id_and_no_trust() {
    let params = worktree_list_params("w1H");
    assert_eq!(params["workspace_id"], json!("w1H"));
    // trust_repository must not be sent: measured read-only without it,
    // and the app must never silently trust the human's repositories.
    assert!(params.get("trust_repository").is_none());
}

/// The measured `worktree.list` success shape for a git-backed workspace.
fn worktree_list_response() -> Value {
    json!({
        "id": "companion-1",
        "result": {
            "type": "worktree_list",
            "source": {
                "repo_key": "/srv/repo/.git",
                "repo_name": "repo",
                "repo_root": "/srv/repo",
                "source_checkout_path": "/srv/repo",
                "source_workspace_id": "w1H"
            },
            "worktrees": [
                {
                    "path": "/srv/repo",
                    "branch": "master",
                    "is_bare": false,
                    "is_detached": false,
                    "is_prunable": false,
                    "is_linked_worktree": false,
                    "open_workspace_id": "w1H",
                    "label": "repo"
                }
            ]
        }
    })
}

#[test]
fn worktree_branch_maps_the_open_worktree() {
    let branch =
        parse_worktree_branch(&worktree_list_response(), "w1H").expect("must parse");
    assert_eq!(branch.as_deref(), Some("master"));
}

#[test]
fn worktree_branch_of_another_workspace_is_none_when_the_list_is_empty() {
    // No worktrees in the reply: no branch to name.
    let mut response = worktree_list_response();
    response["result"]["worktrees"] = json!([]);
    let branch = parse_worktree_branch(&response, "wN").expect("must parse");
    assert_eq!(branch, None);
}

#[test]
fn a_single_worktree_opened_elsewhere_still_names_its_branch() {
    // The daemon derived the worktree list from THIS workspace's own panes,
    // so a repo with one worktree is unambiguous even when another
    // workspace has it open: the branch is what this checkout is on.
    let mut response = worktree_list_response();
    response["result"]["worktrees"][0]["open_workspace_id"] = json!("wZZ");
    let branch = parse_worktree_branch(&response, "w1H").expect("must parse");
    assert_eq!(branch.as_deref(), Some("master"));
}

#[test]
fn worktree_branch_falls_back_to_a_single_worktree() {
    // A repo with one worktree but opened from another workspace id: the
    // repo root's branch still names what the checkout is on.
    let mut response = worktree_list_response();
    response["result"]["worktrees"][0]["open_workspace_id"] = json!("wZZ");
    let branch = parse_worktree_branch(&response, "w1H").expect("must parse");
    assert_eq!(branch.as_deref(), Some("master"));
}

#[test]
fn worktree_branch_is_none_when_two_worktrees_and_none_is_ours() {
    let mut response = worktree_list_response();
    let second = response["result"]["worktrees"][0].clone();
    response["result"]["worktrees"].as_array_mut().unwrap().push(second);
    response["result"]["worktrees"][0]["open_workspace_id"] = json!("wZZ");
    response["result"]["worktrees"][1]["open_workspace_id"] = json!("wYY");
    let branch = parse_worktree_branch(&response, "w1H").expect("must parse");
    assert_eq!(branch, None, "with two worktrees we cannot guess the branch");
}

#[test]
fn worktree_refusals_carry_the_daemons_code() {
    // Measured live: a non-git directory is refused with `not_git_worktree`
    // — the typed refusal the caller maps to "no branch".
    let error = parse_worktree_branch(
        &refused_response(
            "not_git_worktree",
            "Herdr worktree actions require a path inside a Git work tree",
        ),
        "w1H",
    )
    .expect_err("a refusal is an error");
    match error {
        RequestError::Refused { code, .. } => assert_eq!(code, "not_git_worktree"),
        other => panic!("expected RequestError::Refused, got {other:?}"),
    }
}

}
