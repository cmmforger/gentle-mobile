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
        Ok(Self { ssh, user: profile.user.clone(), session: session.to_owned(), request_counter: 0 })
    }

    fn next_id(&mut self) -> String {
        self.request_counter += 1;
        format!("companion-{}", self.request_counter)
    }
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

}
