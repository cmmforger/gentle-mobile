//! The RPC lane (T17b, S15/S17/S18): resume a **closed** Gentle Shell
//! session by spawning `gentle-shell --mode rpc --session <path>` **over the
//! existing pinned SSH transport** — a new channel per resume, never a new
//! host key, never a new pin — and speaking Pi's strict JSONL protocol on
//! the child's stdin/stdout.
//!
//! The wire was measured live in T5 and is ported from
//! `spikes/gentle-rpc/src/main.rs`, not rewritten: one JSON object per
//! newline-terminated line, commands correlated by `id`, events streamed
//! between responses, and dialogs (`extension_ui_request`) answered with
//! `extension_ui_response`. The protocol source of truth is Pi's
//! `dist/modes/rpc/rpc-mode.js`; nothing here is guessed.
//!
//! S17 is the client contract this module owns and unit-tests:
//!
//! * (a) **never close the read end early** — Pi keeps writing records after
//!   the last consumed event, and closing the read end first makes the child
//!   crash on EPIPE (exit 1); the reader always drains to EOF;
//! * (b) **close stdin for an orderly shutdown** — stdin EOF is the shutdown
//!   signal, and stdout EOF afterwards is a normal end, not an error;
//! * (c) **tolerate a torn trailing JSONL line** — `kill -9` mid-append can
//!   leave one; an unparseable line is skipped, never fatal;
//! * (d) **answer every `extension_ui_request`** — the blocking dialogs
//!   (`select`, `confirm`, `input`, `editor`) are surfaced to the UI and
//!   must be answered or the agent blocks; the fire-and-forget ones
//!   (`notify`, `setStatus`, `setWidget`, `setTitle`, `set_editor_text`)
//!   are acknowledged immediately.
//!
//! S18 is the lease discipline: Pi provides **no session lock** — two RPC
//! processes on one session file both append and diverge silently — so the
//! guard is ours. The resume lane takes the advisory lease (the same one
//! T17a wrote) **before** spawning the child, refuses a session a live
//! lease claims, and releases the lease on close, on transport death and
//! on teardown. The heartbeat while the session is held stays the app's
//! 30 s loop over [`crate::sessions::refresh_lease_over_sftp`].

use std::time::{Duration, Instant};

use serde_json::{Value, json};

/// A resume-lane failure, typed for the UI. The four required refusals —
/// session not a file, already leased, spawn refused, protocol garbage —
/// are their own variants; everything else keeps enough detail to say why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RpcError {
    /// The path the UI asked to resume is not a regular file on the host.
    NotAFile { path: String },
    /// The directory a fresh session should start in does not exist on the
    /// host, or is not a directory (S24-5).
    NotADirectory { path: String },
    /// Another device holds a live advisory lease (S18): refused, never
    /// opened.
    AlreadyLeased { detail: String },
    /// The host could not spawn `gentle-shell --mode rpc` (binary missing,
    /// exec refused, channel dead).
    SpawnRefused { detail: String },
    /// The child spoke something this protocol cannot honour — including a
    /// resumed session whose id differs from the one the list reported.
    ProtocolGarbage { detail: String },
    /// A command the child answered with an error object.
    CommandRefused { command: String, detail: String },
    /// The transport died (SSH channel gone, child exited early).
    Disconnected,
    /// No answer within the per-step deadline.
    Timeout { detail: String },
    /// Anything else on the wire.
    Transport { detail: String },
}

impl std::fmt::Display for RpcError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RpcError::NotAFile { path } => {
                write!(formatter, "the session is not a file on the host: {path}")
            }
            RpcError::NotADirectory { path } => {
                write!(formatter, "the directory does not exist on the host: {path}")
            }
            RpcError::AlreadyLeased { detail } => {
                write!(formatter, "the session is already in use: {detail}")
            }
            RpcError::SpawnRefused { detail } => {
                write!(formatter, "the host refused to start the RPC session: {detail}")
            }
            RpcError::ProtocolGarbage { detail } => {
                write!(formatter, "the RPC session did not speak the expected protocol: {detail}")
            }
            RpcError::CommandRefused { command, detail } => {
                write!(formatter, "the RPC session refused {command}: {detail}")
            }
            RpcError::Disconnected => write!(formatter, "the RPC session is no longer connected"),
            RpcError::Timeout { detail } => write!(formatter, "timed out: {detail}"),
            RpcError::Transport { detail } => write!(formatter, "{detail}"),
        }
    }
}

impl std::error::Error for RpcError {}

/// Map a typed lease refusal from the SFTP lane onto the RPC lane's error.
/// This is the S18 guard: a live lease held elsewhere must reach the UI as
/// `AlreadyLeased`, never as a generic transport failure.
pub fn lease_refusal_to_rpc_error(code: &str, detail: &str) -> RpcError {
    if code == "lease_live_elsewhere" {
        RpcError::AlreadyLeased { detail: detail.to_owned() }
    } else {
        RpcError::Transport {
            detail: format!("the host refused the lease ({code}): {detail}"),
        }
    }
}

/// One line of the wire: one JSON object, or `None` for anything a reader
/// must skip — an empty line, or a torn trailing line (S17c: `kill -9`
/// mid-append). Never an error.
pub fn parse_jsonl_line(line: &str) -> Option<Value> {
    let line = line.trim();
    if line.is_empty() {
        return None;
    }
    serde_json::from_str(line).ok()
}

/// One transcript entry the UI can render: role and plain text, nothing
/// invented. `None` for records the transcript must not show (tool calls,
/// tool results, thinking, metadata) or entries with no text at all.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranscriptEntry {
    pub role: String,
    pub text: String,
}

/// Extract a transcript entry from one Pi `get_messages` message. User
/// messages carry a string content; assistant messages an array of typed
/// blocks — only `text` blocks render. `toolCall`/`toolResult`/`thinking`
/// blocks and non-user/assistant roles never become transcript lines.
pub fn transcript_entry(message: &Value) -> Option<TranscriptEntry> {
    let role = message.get("role")?.as_str()?;
    if role != "user" && role != "assistant" {
        return None;
    }
    let text = match message.get("content") {
        Some(Value::String(text)) => text.clone(),
        Some(Value::Array(blocks)) => blocks
            .iter()
            .filter_map(|block| {
                if block.get("type")?.as_str()? == "text" {
                    block.get("text")?.as_str().map(str::to_owned)
                } else {
                    None
                }
            })
            .collect::<Vec<_>>()
            .join("\n"),
        _ => return None,
    };
    if text.trim().is_empty() {
        return None;
    }
    Some(TranscriptEntry { role: role.to_owned(), text })
}

/// The whole transcript of a `get_messages` reply: every renderable entry,
/// in wire order.
pub fn transcript_from_messages(messages: &Value) -> Vec<TranscriptEntry> {
    messages
        .as_array()
        .map(|entries| entries.iter().filter_map(transcript_entry).collect())
        .unwrap_or_default()
}

/// The dialog methods that block the agent until answered. Everything else
/// the extension UI emits (`notify`, `setStatus`, `setWidget`, `setTitle`,
/// `set_editor_text`) is fire-and-forget on the host.
pub fn blocking_dialog(method: &str) -> bool {
    matches!(method, "select" | "confirm" | "input" | "editor")
}

/// The remote command for one resume: exec the child directly (no PTY, S17)
/// on the resolved binary path. The caller resolves the path first with
/// `command -v` on the same host, so PATH never enters the exec line.
pub fn rpc_exec_command(binary_path: &str, session_path: &str) -> String {
    format!(
        "{} --mode rpc --session {}",
        shell_quote(binary_path),
        shell_quote(session_path)
    )
}

/// The exec line for a **fresh** session in `cwd` (S24-5): `cd` first — an
/// SSH exec starts in the user's home, and the child's working directory is
/// what Pi records in the new session's header — then `exec` so the shell
/// is replaced and the SSH channel's stdin/stdout is the child directly
/// (the same EOF semantics the resume lane relies on, S17a/b), and **no
/// `--session`**: Pi creates a fresh session file itself.
pub fn rpc_exec_command_in_dir(binary_path: &str, cwd: &str) -> String {
    format!(
        "cd {} && exec {} --mode rpc",
        shell_quote(cwd),
        shell_quote(binary_path)
    )
}

/// POSIX single-quote shell quoting (the spike's `shell_quote`).
pub fn shell_quote(part: &str) -> String {
    format!("'{}'", part.replace('\'', r"'\''"))
}

/// The per-step deadlines the lane works under, in one place.
#[derive(Debug, Clone, Copy)]
pub struct RpcDeadlines {
    /// A command (`get_state`, `get_messages`, `prompt`) must be answered
    /// within this long.
    pub response: Duration,
    /// Waiting for the turn to settle (`agent_settled` after a prompt).
    pub settle: Duration,
    /// After stdin EOF, the child must exit within this grace.
    pub exit: Duration,
}

impl Default for RpcDeadlines {
    fn default() -> Self {
        Self {
            response: Duration::from_secs(30),
            settle: Duration::from_secs(300),
            exit: Duration::from_secs(20),
        }
    }
}

/// The instant `deadline_ms` milliseconds from now.
pub fn deadline_in(deadline_ms: u64) -> Instant {
    Instant::now() + Duration::from_millis(deadline_ms)
}

/// The strict JSONL protocol half the client owns, over any duplex byte
/// stream — the SSH channel on the wire, an in-memory duplex in tests. One
/// object per line, request ids for correlation, events between responses.
pub struct RpcProtocol<S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin> {
    stream: S,
    buffer: Vec<u8>,
    eof: bool,
    next_request_id: u64,
}

impl<S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin> RpcProtocol<S> {
    pub fn new(stream: S) -> Self {
        Self { stream, buffer: Vec::new(), eof: false, next_request_id: 0 }
    }

    /// Send one command: one JSON object, one line, flushed.
    pub async fn send(&mut self, value: &Value) -> Result<(), RpcError> {
        use tokio::io::AsyncWriteExt as _;
        let mut line = serde_json::to_vec(value)
            .map_err(|error| RpcError::Transport { detail: format!("the command did not serialise: {error}") })?;
        line.push(b'\n');
        self.stream.write_all(&line).await.map_err(|error| RpcError::Transport { detail: format!("the RPC link refused the write: {error}") })?;
        self.stream.flush().await.map_err(|error| RpcError::Transport { detail: format!("the RPC link refused the flush: {error}") })
    }

    /// True once stdout reached EOF. The orderly shutdown (b) ends here, and
    /// so does a child that died early — the caller tells the two apart by
    /// what it asked for last.
    pub fn is_eof(&self) -> bool {
        self.eof
    }

    /// The next protocol record, skipping blank and torn lines (S17c).
    /// `Ok(None)` means either stdout EOF (a normal end, S17b — check
    /// [`Self::is_eof`]) or `max_wait` elapsed without data. Reading never
    /// stops early otherwise: the caller keeps draining until EOF.
    pub async fn poll_record(&mut self, max_wait: Duration) -> Result<Option<Value>, RpcError> {
        use tokio::io::AsyncReadExt as _;
        let soft_deadline = tokio::time::Instant::now() + max_wait;
        loop {
            // A complete line already in the buffer?
            if let Some(newline) = self.buffer.iter().position(|byte| *byte == b'\n') {
                let line: Vec<u8> = self.buffer.drain(..=newline).collect();
                let text = String::from_utf8_lossy(&line[..line.len() - 1]);
                match parse_jsonl_line(&text) {
                    Some(value) => return Ok(Some(value)),
                    None => continue, // torn or blank: skipped, never fatal
                }
            }
            if self.eof {
                // A trailing partial line at EOF is a torn tail: ignore it.
                self.buffer.clear();
                return Ok(None);
            }
            let mut chunk = [0u8; 16 * 1024];
            let read = tokio::time::timeout_at(soft_deadline, self.stream.read(&mut chunk)).await;
            let read = match read {
                Ok(read) => read,
                Err(_) => return Ok(None), // soft deadline: no record yet
            };
            match read {
                Ok(0) => self.eof = true,
                Ok(count) => self.buffer.extend_from_slice(&chunk[..count]),
                Err(error) => return Err(RpcError::Transport { detail: format!("the RPC link failed while reading: {error}") }),
            }
        }
    }

    /// The next record under a hard deadline: `Ok(None)` at EOF (check
    /// [`Self::is_eof`]), a timeout error when the deadline passes first.
    pub async fn next_record(&mut self, deadline: Instant) -> Result<Option<Value>, RpcError> {
        loop {
            match self.poll_record(Duration::from_millis(50)).await? {
                Some(record) => return Ok(Some(record)),
                None if self.is_eof() => return Ok(None),
                None if Instant::now() >= deadline => {
                    return Err(RpcError::Timeout { detail: "the RPC link sent nothing in time".to_owned() });
                }
                None => continue,
            }
        }
    }

    /// Send a command and wait for **its** response, draining every event in
    /// between through `on_event`. Returns the response record.
    pub async fn request(
        &mut self,
        command: &str,
        fields: Value,
        deadline: Instant,
        on_event: &mut dyn FnMut(&Value) -> Option<(String, Value)>,
    ) -> Result<Value, RpcError> {
        self.next_request_id += 1;
        let id = format!("companion-rpc-{}", self.next_request_id);
        let mut envelope = json!({ "id": id, "type": command });
        if let Value::Object(map) = fields {
            if let Value::Object(target) = &mut envelope {
                for (key, value) in map {
                    target.insert(key.clone(), value.clone());
                }
            }
        }
        self.send(&envelope).await?;
        loop {
            let record = match self.poll_record(Duration::from_millis(100)).await? {
                Some(record) => record,
                None if self.is_eof() => return Err(RpcError::Disconnected),
                None if Instant::now() >= deadline => {
                    return Err(RpcError::Timeout { detail: "the RPC link sent nothing in time".to_owned() });
                }
                None => continue,
            };
            let record_type = record.get("type").and_then(Value::as_str).unwrap_or("<no type>");
            if record_type == "response"
                && record.get("id").and_then(Value::as_str) == Some(id.as_str())
            {
                if record.get("success").and_then(Value::as_bool) == Some(false) {
                    return Err(RpcError::CommandRefused {
                        command: command.to_owned(),
                        detail: record
                            .get("error")
                            .and_then(Value::as_str)
                            .unwrap_or("the child gave no reason")
                            .to_owned(),
                    });
                }
                return Ok(record);
            }
            if record_type == "extension_ui_request" {
                if let Some((dialog_id, payload)) = on_event(&record) {
                    self.answer(&dialog_id, &payload).await?;
                    continue;
                }
            }
        }
    }

    /// Acknowledge a fire-and-forget dialog (S17d: answer every
    /// `extension_ui_request`), or deliver the UI's answer to a blocking
    /// one. The host ignores responses for ids that were never pending.
    pub async fn answer(&mut self, dialog_id: &str, payload: &Value) -> Result<(), RpcError> {
        let mut response = json!({ "type": "extension_ui_response", "id": dialog_id });
        if let Value::Object(map) = payload {
            if let Value::Object(target) = &mut response {
                for (key, value) in map {
                    target.insert(key.clone(), value.clone());
                }
            }
        }
        self.send(&response).await
    }

    /// Orderly shutdown (S17b): stop answering, then close stdin and keep
    /// reading until stdout EOF so the child never sees a closed read end
    /// (S17a). `Ok(())` = the child ended cleanly.
    pub async fn close(&mut self, deadlines: RpcDeadlines) -> Result<(), RpcError> {
        use tokio::io::AsyncWriteExt as _;
        let _ = self.stream.shutdown().await;
        let deadline = Instant::now() + deadlines.exit;
        loop {
            match self.next_record(deadline).await {
                Ok(None) => return Ok(()),
                Ok(Some(_)) => continue, // drain to EOF; records are past caring
                Err(RpcError::Timeout { .. }) => {
                    return Ok(()); // the grace expired; the child is somebody else's problem now
                }
                Err(error) => return Err(error),
            }
        }
    }
}

// --- the resume lane over SSH -------------------------------------------------
//
// One dedicated thread per resumed session owns the pinned SSH connection
// (the same pins, the same device key — a new channel, never a new pin),
// the child process and the protocol pump. The UniFFI object talks to it
// over a crossbeam command channel, the same shape the Lane A connection
// uses (connection.rs), so nothing async leaks across the FFI boundary.

use std::sync::{Arc, Mutex as StdMutex, atomic::AtomicBool};

/// A blocking dialog the UI must answer (S17d), in the words the boards use.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DialogRequest {
    pub dialog_id: String,
    pub method: String,
    pub title: Option<String>,
    pub message: Option<String>,
    pub placeholder: Option<String>,
    pub options: Vec<String>,
}

/// Extract a blocking dialog from an `extension_ui_request` record; the
/// fire-and-forget methods never become pending dialogs.
pub fn dialog_from_record(record: &Value) -> Option<DialogRequest> {
    let dialog_id = record.get("id")?.as_str()?.to_owned();
    let method = record.get("method")?.as_str()?;
    if !blocking_dialog(method) {
        return None;
    }
    Some(DialogRequest {
        dialog_id,
        method: method.to_owned(),
        title: record.get("title").and_then(Value::as_str).map(str::to_owned),
        message: record.get("message").and_then(Value::as_str).map(str::to_owned),
        placeholder: record.get("placeholder").and_then(Value::as_str).map(str::to_owned),
        options: record
            .get("options")
            .and_then(Value::as_array)
            .map(|options| {
                options
                    .iter()
                    .filter_map(|option| option.as_str().map(str::to_owned))
                    .collect()
            })
            .unwrap_or_default(),
    })
}

/// The handshake result: the resumed session's id (T5: resume with the same
/// sessionId), the child's own message count, and the transcript as it
/// stands at resume time.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RpcHandshake {
    pub session_id: String,
    pub message_count: u64,
    pub transcript: Vec<TranscriptEntry>,
}

/// Handshake over a live protocol: `get_state` then `get_messages`, both
/// drained of events (dialogs are auto-answered). `expected_session_id`
/// comes from the Sessions list; a child that reports a different id is
/// protocol garbage, not a resume (S15: never a wrong-writer surprise).
pub async fn rpc_handshake<S>(
    protocol: &mut RpcProtocol<S>,
    expected_session_id: Option<&str>,
    deadlines: RpcDeadlines,
) -> Result<RpcHandshake, RpcError>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let mut auto_answer = |record: &Value| {
        // A handshake cannot wait for a UI that does not exist yet: any
        // blocking dialog during startup is cancelled, which the agent
        // treats as its documented default.
        dialog_from_record(record).map(|dialog| (dialog.dialog_id, json!({ "cancelled": true })))
    };
    let state = protocol
        .request("get_state", json!({}), Instant::now() + deadlines.response, &mut auto_answer)
        .await?;
    let session_id = state
        .pointer("/data/sessionId")
        .and_then(Value::as_str)
        .filter(|id| !id.is_empty())
        .ok_or_else(|| RpcError::ProtocolGarbage {
            detail: "get_state answered without a sessionId".to_owned(),
        })?
        .to_owned();
    if let Some(expected) = expected_session_id {
        if expected != session_id {
            return Err(RpcError::ProtocolGarbage {
                detail: format!(
                    "the child resumed session {session_id}, not the requested {expected}"
                ),
            });
        }
    }
    let message_count = state
        .pointer("/data/messageCount")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let messages = protocol
        .request("get_messages", json!({}), Instant::now() + deadlines.response, &mut auto_answer)
        .await?;
    let transcript = transcript_from_messages(messages.pointer("/data/messages").unwrap_or(&Value::Null));
    Ok(RpcHandshake { session_id, message_count, transcript })
}

/// Synchronous answer plumbing lives in [`RpcProtocol::request`]: the
/// callback may return an `(dialog_id, payload)` answer to send the moment
/// the event was seen.

/// Resolve the host's `gentle-shell` binary (the exec line then never
/// depends on the non-interactive PATH).
pub(crate) async fn resolve_remote_binary(
    ssh: &mut russh::client::Handle<crate::connection::KeyVerifier>,
) -> Result<String, RpcError> {
    use tokio::io::AsyncReadExt as _;
    let mut channel = ssh
        .channel_open_session()
        .await
        .map_err(|error| RpcError::Transport { detail: format!("the probe channel could not open: {error}") })?;
    channel
        .exec(true, "sh -lc 'command -v gentle-shell'")
        .await
        .map_err(|error| RpcError::SpawnRefused { detail: format!("the host refused the probe: {error}") })?;
    let mut raw = Vec::new();
    channel
        .make_reader()
        .read_to_end(&mut raw)
        .await
        .map_err(|error| RpcError::Transport { detail: format!("the probe could not be read: {error}") })?;
    // A login shell may echo profile noise before the answer: the last
    // non-empty line is the one `command -v` printed.
    let path = String::from_utf8_lossy(&raw)
        .lines()
        .rev()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or("")
        .to_owned();
    if path.is_empty() || path.contains('\n') {
        return Err(RpcError::SpawnRefused {
            detail: "gentle-shell is not on the host's PATH".to_owned(),
        });
    }
    Ok(path)
}

/// Exec `gentle-shell --mode rpc --session <path>` on a fresh channel of
/// the pinned connection (no PTY — S17). The returned stream is the child's
/// stdin+stdout.
pub async fn spawn_rpc_child(
    ssh: &mut russh::client::Handle<crate::connection::KeyVerifier>,
    session_path: &str,
) -> Result<russh::ChannelStream<russh::client::Msg>, RpcError> {
    let binary = resolve_remote_binary(ssh).await?;
    let channel = ssh
        .channel_open_session()
        .await
        .map_err(|error| RpcError::SpawnRefused { detail: format!("the RPC channel could not open: {error}") })?;
    channel
        .exec(true, rpc_exec_command(&binary, session_path).as_str())
        .await
        .map_err(|error| RpcError::SpawnRefused { detail: format!("the host refused the RPC exec: {error}") })?;
    Ok(channel.into_stream())
}

/// Exec a **fresh** `gentle-shell --mode rpc` with its working directory set
/// to `cwd` on a fresh channel of the pinned connection (no PTY — S17).
/// `cd` happens on the host, inside the exec line, so the remote login shell
/// itself places the child in the repository.
pub async fn spawn_rpc_child_in_dir(
    ssh: &mut russh::client::Handle<crate::connection::KeyVerifier>,
    cwd: &str,
) -> Result<russh::ChannelStream<russh::client::Msg>, RpcError> {
    let binary = resolve_remote_binary(ssh).await?;
    let channel = ssh
        .channel_open_session()
        .await
        .map_err(|error| RpcError::SpawnRefused { detail: format!("the RPC channel could not open: {error}") })?;
    channel
        .exec(true, rpc_exec_command_in_dir(&binary, cwd).as_str())
        .await
        .map_err(|error| RpcError::SpawnRefused { detail: format!("the host refused the RPC exec: {error}") })?;
    Ok(channel.into_stream())
}

/// The commands the UI can send a resumed session's driver.
pub enum RpcCommand {
    /// Append one prompt; the reply completes when the turn settles, with
    /// the refreshed transcript.
    Prompt { text: String, reply: crossbeam_channel::Sender<Result<Vec<TranscriptEntry>, RpcError>> },
    /// Answer one blocking dialog (S17d).
    AnswerDialog {
        dialog_id: String,
        payload: Value,
        reply: crossbeam_channel::Sender<Result<(), RpcError>>,
    },
    /// Orderly shutdown (S17b) + lease release. The reply completes when
    /// the child has been given stdin EOF and drained.
    Close { reply: crossbeam_channel::Sender<Result<(), RpcError>> },
}

/// The driver's shared state, readable from the UI thread at any time.
#[derive(Default)]
pub struct RpcShared {
    pub transcript: Vec<TranscriptEntry>,
    pub pending_dialog: Option<DialogRequest>,
    pub closed: bool,
    pub last_error: Option<String>,
    /// The child's own message count, refreshed from `get_state` after every
    /// settled turn — the header never keeps a stale handshake number once
    /// the conversation has moved (S24-5: `0 messages` must not outlive the
    /// first exchange).
    pub message_count: Option<u64>,
}

/// What the driver needs to keep its lease honest (S18).
#[derive(Clone)]
pub struct LeaseGuard {
    pub profile: crate::profile::HostProfile,
    pub session_id: String,
    pub device_id: String,
    pub token: String,
}

/// Drive one resumed session to its end: answer dialogs, run prompt turns,
/// and on any end — orderly close, EOF, or transport death — release the
/// advisory lease (S18: close, disconnect and teardown all release).
pub async fn drive_rpc_session<S>(
    mut protocol: RpcProtocol<S>,
    commands: crossbeam_channel::Receiver<RpcCommand>,
    shared: Arc<StdMutex<RpcShared>>,
    lease: LeaseGuard,
    deadlines: RpcDeadlines,
) where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    // One prompt turn at a time; the reply completes at `agent_settled`.
    let mut turn: Option<(
        String,
        crossbeam_channel::Sender<Result<Vec<TranscriptEntry>, RpcError>>,
        Instant,
    )> = None;
    let end = 'pump: loop {
        // 1. Commands, without blocking the pump. A dropped command channel
        // (the UI object gone without a Close) is itself a close request:
        // the lease must never outlive the object that promised it (S18).
        let mut orphaned = false;
        loop {
            match commands.try_recv() {
                Ok(command) => {
                    match command {
                RpcCommand::Close { reply } => {
                    // Cancel any blocking dialog we still owe an answer to
                    // (S17d), then stdin EOF and drain to stdout EOF.
                    let pending = shared.lock().ok().and_then(|mut guarded| guarded.pending_dialog.take());
                    if let Some(dialog) = pending {
                        let _ = protocol.answer(&dialog.dialog_id, &json!({ "cancelled": true })).await;
                    }
                    let _ = reply.send(protocol.close(deadlines).await);
                    break 'pump Ok(());
                }
                RpcCommand::Prompt { text, reply } => {
                    if turn.is_some() {
                        let _ = reply.send(Err(RpcError::CommandRefused {
                            command: "prompt".to_owned(),
                            detail: "a turn is already running".to_owned(),
                        }));
                        continue;
                    }
                    protocol.next_request_id += 1;
                    let id = format!("companion-rpc-{}", protocol.next_request_id);
                    if let Err(error) = protocol.send(&json!({ "id": id, "type": "prompt", "message": text })).await {
                        let _ = reply.send(Err(error));
                        continue;
                    }
                    turn = Some((id, reply, Instant::now() + deadlines.settle));
                }
                RpcCommand::AnswerDialog { dialog_id, payload, reply } => {
                    let result = protocol.answer(&dialog_id, &payload).await;
                    if result.is_ok() {
                        if let Ok(mut guarded) = shared.lock() {
                            if guarded
                                .pending_dialog
                                .as_ref()
                                .is_some_and(|dialog| dialog.dialog_id == dialog_id)
                            {
                                guarded.pending_dialog = None;
                            }
                        }
                    }
                    let _ = reply.send(result);
                }
                    }
                }
                Err(crossbeam_channel::TryRecvError::Empty) => break,
                Err(crossbeam_channel::TryRecvError::Disconnected) => {
                    orphaned = true;
                    break;
                }
            }
        }
        if orphaned {
            let pending = shared.lock().ok().and_then(|mut guarded| guarded.pending_dialog.take());
            if let Some(dialog) = pending {
                let _ = protocol.answer(&dialog.dialog_id, &json!({ "cancelled": true })).await;
            }
            let _ = protocol.close(deadlines).await;
            break 'pump Ok(());
        }
        // 2. The next record, with a soft deadline so commands keep being
        // served even while nothing streams.
        let record = match protocol.poll_record(Duration::from_millis(100)).await {
            Ok(Some(record)) => record,
            Ok(None) if protocol.is_eof() => {
                // stdout EOF: either the orderly close finished or the child
                // died early (S17b: a normal end either way — but an open
                // turn is answered honestly).
                break Err(RpcError::Disconnected);
            }
            Ok(None) => continue,
            Err(error) => break Err(error),
        };
        let record_type = record.get("type").and_then(Value::as_str).unwrap_or("");
        match record_type {
            "response" => {
                let id = record.get("id").and_then(Value::as_str).unwrap_or("");
                if let Some((turn_id, reply, deadline)) = turn.as_ref() {
                    if id == turn_id {
                        if record.get("success").and_then(Value::as_bool) == Some(false) {
                            let refused = RpcError::CommandRefused {
                                command: "prompt".to_owned(),
                                detail: record
                                    .get("error")
                                    .and_then(Value::as_str)
                                    .unwrap_or("the child gave no reason")
                                    .to_owned(),
                            };
                            let _ = reply.send(Err(refused));
                            turn = None;
                            continue;
                        }
                        // Preflight accepted: keep streaming until settle.
                        if Instant::now() > *deadline {
                            let _ = reply.send(Err(RpcError::Timeout { detail: "the turn never settled".to_owned() }));
                            turn = None;
                        }
                        continue;
                    }
                }
            }
            "agent_settled" => {
                if let Some((_turn_id, reply, deadline)) = turn.take() {
                    if Instant::now() > deadline {
                        let _ = reply.send(Err(RpcError::Timeout { detail: "the turn settled too late".to_owned() }));
                        continue;
                    }
                    // Refresh the transcript from the child (one-shot, hard
                    // deadline) and complete the prompt reply with it.
                    let fetched = protocol
                        .request(
                            "get_messages",
                            json!({}),
                            Instant::now() + deadlines.response,
                            &mut |_record| None,
                        )
                        .await;
                    match fetched {
                        Ok(messages) => {
                            let transcript =
                                transcript_from_messages(messages.pointer("/data/messages").unwrap_or(&Value::Null));
                            if let Ok(mut guarded) = shared.lock() {
                                guarded.transcript = transcript.clone();
                            }
                            let _ = reply.send(Ok(transcript));
                        }
                        Err(error) => {
                            let _ = reply.send(Err(error));
                        }
                    }
                    // The child's own count, refreshed while the turn is
                    // still fresh — a failure here is silent (the header
                    // keeps the last known number) but never lies forward.
                    if let Ok(state) = protocol
                        .request(
                            "get_state",
                            json!({}),
                            Instant::now() + deadlines.response,
                            &mut |_record| None,
                        )
                        .await
                    {
                        let count = state.pointer("/data/messageCount").and_then(Value::as_u64);
                        if let Ok(mut guarded) = shared.lock() {
                            guarded.message_count = count;
                        }
                    }
                }
            }
            "extension_ui_request" => {
                if let Some(dialog) = dialog_from_record(&record) {
                    if let Ok(mut guarded) = shared.lock() {
                        guarded.pending_dialog = Some(dialog.clone());
                    }
                } else {
                    // Fire-and-forget dialogs are acknowledged immediately
                    // (S17d: answer every request; the host ignores acks for
                    // ids it never awaited).
                    let dialog_id = record.get("id").and_then(Value::as_str).unwrap_or("");
                    if !dialog_id.is_empty() {
                        let _ = protocol.answer(dialog_id, &json!({})).await;
                    }
                }
            }
            _ => {} // message_update and friends: recorded by Pi, not by us
        }
    };
    // 3. The end, whatever it was: honest state, then the lease goes back.
    if let Ok(mut guarded) = shared.lock() {
        guarded.closed = true;
        guarded.pending_dialog = None;
        if let Err(error) = &end {
            guarded.last_error = Some(error.to_string());
        }
    }
    if let Some((_, reply, _)) = turn {
        let _ = reply.send(Err(RpcError::Disconnected));
    }
    release_lease_quietly(&lease).await;
}

/// Release the advisory lease; a failure is recorded, never allowed to
/// mask the session's own outcome.
async fn release_lease_quietly(lease: &LeaseGuard) {
    let _ = crate::sessions::release_lease_over_sftp(
        &lease.profile,
        &lease.session_id,
        &lease.device_id,
        &lease.token,
    )
    .await;
}

/// The pre-flight part of a resume, one-shot on the caller's runtime: the
/// path must be a regular file on the host, and the advisory lease must be
/// ours **before any child exists** (S18). A live lease held elsewhere is
/// refused typed — this is the guard the Sessions list already shows.
pub async fn take_lease_for_resume(
    profile: &crate::profile::HostProfile,
    session_path: &str,
    session_id: &str,
    device_id: &str,
    token: &str,
) -> Result<crate::sessions::LeaseTakeOutcome, RpcError> {
    let is_file = check_session_file(profile, session_path).await?;
    if !is_file {
        return Err(RpcError::NotAFile { path: session_path.to_owned() });
    }
    crate::sessions::acquire_lease_over_sftp(profile, session_id, device_id, token)
        .await
        .map_err(|error| match error {
            crate::requests::HubError::Request(crate::requests::RequestError::Refused { code, detail }) => {
                lease_refusal_to_rpc_error(&code, &detail)
            }
            other => RpcError::Transport { detail: other.to_string() },
        })
}

/// The whole live resume, designed to run **inside the driver thread's own
/// runtime** (the pinned connection must outlive any single call): pinned
/// SSH channel, child spawn, handshake, then the pump until the session
/// ends. The handshake result reaches the caller through `announced`.
pub async fn run_resumed_session(
    profile: crate::profile::HostProfile,
    session_path: String,
    session_id: String,
    lease: LeaseGuard,
    commands: crossbeam_channel::Receiver<RpcCommand>,
    shared: Arc<StdMutex<RpcShared>>,
    announced: crossbeam_channel::Sender<Result<RpcHandshake, RpcError>>,
    deadlines: RpcDeadlines,
) {
    let stop = Arc::new(AtomicBool::new(false));
    let outcome = async {
        let mut ssh = crate::requests::connect_pinned_ssh(&profile, &stop)
            .await
            .map_err(|error| RpcError::Transport { detail: error.to_string() })?;
        let stream = match spawn_rpc_child(&mut ssh, &session_path).await {
            Ok(stream) => stream,
            Err(error) => {
                let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
                return Err(error);
            }
        };
        let mut protocol = RpcProtocol::new(stream);
        let handshake = rpc_handshake(&mut protocol, Some(&session_id), deadlines).await;
        let handshake = match handshake {
            Ok(handshake) => handshake,
            Err(error) => {
                let _ = protocol.close(deadlines).await;
                let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
                return Err(error);
            }
        };
        let _ = announced.send(Ok(handshake.clone()));
        // The pump owns the rest of the session's life; it releases the
        // lease on every exit path (S18).
        drive_rpc_session(protocol, commands, shared, lease.clone(), deadlines).await;
        return Ok(handshake);
    }
    .await;
    if let Err(error) = outcome {
        let _ = announced.send(Err(error));
        release_lease_quietly(&lease).await;
    }
}

/// Check that the session path exists and is a regular file on the host,
/// over the same SFTP lane the listing uses.
async fn check_session_file(
    profile: &crate::profile::HostProfile,
    session_path: &str,
) -> Result<bool, RpcError> {
    let (_ssh, sftp) = crate::sessions::connect_sftp(profile)
        .await
        .map_err(|error| RpcError::Transport { detail: error.to_string() })?;
    let metadata = sftp.metadata(session_path).await.map_err(|error| RpcError::Transport {
        detail: format!("the session file could not be checked: {error}"),
    });
    Ok(match metadata {
        Ok(metadata) => metadata.file_type().is_file(),
        Err(_) => false,
    })
}

// --- the fresh-start lane (S24-5) ----------------------------------------------
//
// A session that does not exist yet cannot take a lease first — there is no
// session id to lease until the child reports one. The order is therefore:
// the directory must exist on the host, the child spawns **without
// `--session`**, the handshake learns the session id the child itself chose,
// and only then the advisory lease is taken under that id. Every failure
// before the pump closes the child and leaves nothing half-open: no lease is
// ever written for a session that never became ours, and the pump releases
// the lease on every exit path (S18).

/// Check that `cwd` exists and is a directory on the host, over the SFTP
/// subsystem of the **same** pinned connection the child will use. A missing
/// path — or one that is a file — is refused before anything spawns.
async fn check_host_directory(
    ssh: &mut russh::client::Handle<crate::connection::KeyVerifier>,
    cwd: &str,
) -> Result<(), RpcError> {
    let channel = ssh
        .channel_open_session()
        .await
        .map_err(|error| RpcError::Transport {
            detail: format!("the directory-probe channel could not open: {error}"),
        })?;
    channel
        .request_subsystem(true, "sftp")
        .await
        .map_err(|error| RpcError::Transport {
            detail: format!("the host refused the SFTP subsystem for the directory probe: {error}"),
        })?;
    let sftp = russh_sftp::client::SftpSession::new(channel.into_stream())
        .await
        .map_err(|error| RpcError::Transport {
            detail: format!("the directory probe could not start: {error}"),
        })?;
    let is_dir = matches!(sftp.metadata(cwd).await, Ok(metadata) if metadata.file_type().is_dir());
    if is_dir {
        Ok(())
    } else {
        Err(RpcError::NotADirectory { path: cwd.to_owned() })
    }
}

/// Handshake + lease for a fresh start, in the only safe order: the child
/// reports its session id, then the lease is taken under it. On a failed
/// handshake or a refused lease the child is closed here — stdin EOF and a
/// drained stdout — so a session this device does not hold never keeps
/// running. The lease closure runs exactly once, only after a good
/// handshake, and never for a session that was never ours.
pub async fn complete_fresh_start<S, F, Fut>(
    protocol: &mut RpcProtocol<S>,
    deadlines: RpcDeadlines,
    take_lease: F,
) -> Result<RpcHandshake, RpcError>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    F: FnOnce(String) -> Fut,
    Fut: Future<Output = Result<crate::sessions::LeaseTakeOutcome, RpcError>>,
{
    let handshake = match rpc_handshake(protocol, None, deadlines).await {
        Ok(handshake) => handshake,
        Err(error) => {
            let _ = protocol.close(deadlines).await;
            return Err(error);
        }
    };
    if let Err(error) = take_lease(handshake.session_id.clone()).await {
        let _ = protocol.close(deadlines).await;
        return Err(error);
    }
    Ok(handshake)
}

/// Map the SFTP lane's lease outcome onto the RPC lane's error — the same
/// mapping the resume lane uses.
pub async fn take_lease_for_fresh_start(
    profile: &crate::profile::HostProfile,
    session_id: &str,
    device_id: &str,
    token: &str,
) -> Result<crate::sessions::LeaseTakeOutcome, RpcError> {
    crate::sessions::acquire_lease_over_sftp(profile, session_id, device_id, token)
        .await
        .map_err(|error| match error {
            crate::requests::HubError::Request(crate::requests::RequestError::Refused { code, detail }) => {
                lease_refusal_to_rpc_error(&code, &detail)
            }
            other => RpcError::Transport { detail: other.to_string() },
        })
}

/// The whole fresh start (S24-5), designed like [`run_resumed_session`] to
/// run **inside the driver thread's own runtime**: pinned SSH connection,
/// host directory check, child spawn without `--session`, handshake, lease
/// under the child's own session id, then the pump until the session ends.
/// The handshake result reaches the caller through `announced`.
pub async fn run_fresh_session(
    profile: crate::profile::HostProfile,
    cwd: String,
    device_id: String,
    token: String,
    commands: crossbeam_channel::Receiver<RpcCommand>,
    shared: Arc<StdMutex<RpcShared>>,
    announced: crossbeam_channel::Sender<Result<RpcHandshake, RpcError>>,
    deadlines: RpcDeadlines,
) {
    let stop = Arc::new(AtomicBool::new(false));
    let outcome = async {
        let mut ssh = crate::requests::connect_pinned_ssh(&profile, &stop)
            .await
            .map_err(|error| RpcError::Transport { detail: error.to_string() })?;
        let prepared = async {
            check_host_directory(&mut ssh, &cwd).await?;
            let stream = spawn_rpc_child_in_dir(&mut ssh, &cwd).await?;
            Ok(RpcProtocol::new(stream))
        }
        .await;
        let mut protocol = match prepared {
            Ok(protocol) => protocol,
            Err(error) => {
                let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
                return Err(error);
            }
        };
        let handshake = complete_fresh_start(&mut protocol, deadlines, |session_id| {
            // Borrow the host profile and lease identity; own the session id
            // the child reported.
            let profile = &profile;
            let device_id = &device_id;
            let token = &token;
            async move {
                take_lease_for_fresh_start(profile, &session_id, device_id, token).await
            }
        })
        .await;
        let handshake = match handshake {
            Ok(handshake) => handshake,
            Err(error) => {
                // complete_fresh_start already closed the child; no lease
                // was ever written for a session that never became ours.
                let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
                return Err(error);
            }
        };
        let _ = announced.send(Ok(handshake.clone()));
        // The pump owns the rest of the session's life; it releases the
        // lease on every exit path (S18).
        let lease = LeaseGuard {
            profile,
            session_id: handshake.session_id.clone(),
            device_id,
            token,
        };
        drive_rpc_session(protocol, commands, shared, lease, deadlines).await;
        return Ok(handshake);
    }
    .await;
    if let Err(error) = outcome {
        let _ = announced.send(Err(error));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    // --- parse_jsonl_line (S17c) --------------------------------------------

    #[test]
    fn one_object_per_line_parses() {
        let value = parse_jsonl_line(r#"{"id":"s1","type":"get_state"}"#).expect("parses");
        assert_eq!(value.get("type").and_then(Value::as_str), Some("get_state"));
        // CR-LF endings survive the transport sometimes; still one record.
        let value = parse_jsonl_line("{\"id\":\"s1\"}\r").expect("parses");
        assert_eq!(value.get("id").and_then(Value::as_str), Some("s1"));
    }

    #[test]
    fn a_torn_last_line_is_skipped_never_fatal() {
        // `kill -9` mid-append leaves a partial line; it must never become
        // an error — it is simply not a record.
        assert_eq!(parse_jsonl_line(r#"{"type":"message","id":"c3d4"#), None);
        assert_eq!(parse_jsonl_line("not json at all"), None);
    }

    #[test]
    fn blank_lines_are_skipped() {
        assert_eq!(parse_jsonl_line(""), None);
        assert_eq!(parse_jsonl_line("   \n"), None);
    }

    // --- transcript_entry ------------------------------------------------------

    #[test]
    fn a_user_message_is_one_transcript_line() {
        let message: Value = serde_json::from_str(
            r#"{"role":"user","content":"resume from the phone","timestamp":1}"#,
        )
        .unwrap();
        let entry = transcript_entry(&message).expect("renders");
        assert_eq!(entry.role, "user");
        assert_eq!(entry.text, "resume from the phone");
    }

    #[test]
    fn an_assistant_message_renders_only_its_text_blocks() {
        let message: Value = serde_json::from_str(
            r#"{"role":"assistant","content":[
                {"type":"thinking","thinking":"hidden"},
                {"type":"text","text":"first"},
                {"type":"toolCall","id":"t1","name":"read"},
                {"type":"text","text":"second"}
            ]}"#,
        )
        .unwrap();
        let entry = transcript_entry(&message).expect("renders");
        assert_eq!(entry.role, "assistant");
        assert_eq!(entry.text, "first\nsecond");
    }

    #[test]
    fn tool_results_metadata_and_empty_entries_never_render() {
        let tool_result: Value = serde_json::from_str(
            r#"{"role":"toolResult","content":[{"type":"toolResult","output":"ok"}]}"#,
        )
        .unwrap();
        assert_eq!(transcript_entry(&tool_result), None);
        let system: Value =
            serde_json::from_str(r#"{"role":"systemMessage","content":"compacted"}"#).unwrap();
        assert_eq!(transcript_entry(&system), None);
        // An assistant message with only a tool call has no text to show.
        let call_only: Value = serde_json::from_str(
            r#"{"role":"assistant","content":[{"type":"toolCall","id":"t1","name":"read"}]}"#,
        )
        .unwrap();
        assert_eq!(transcript_entry(&call_only), None);
        let empty_text: Value =
            serde_json::from_str(r#"{"role":"assistant","content":[{"type":"text","text":"  "}]}"#)
                .unwrap();
        assert_eq!(transcript_entry(&empty_text), None);
    }

    #[test]
    fn transcript_from_messages_keeps_wire_order_and_skips_the_rest() {
        let messages: Value = serde_json::from_str(
            r#"[
            {"role":"user","content":"one"},
            {"role":"toolResult","content":[]},
            {"role":"assistant","content":[{"type":"text","text":"two"}]},
            {"role":"assistant","content":[{"type":"toolCall","id":"t"}]}
        ]"#,
        )
        .unwrap();
        let entries = transcript_from_messages(&messages);
        let texts: Vec<&str> = entries
            .iter()
            .map(|entry| (entry.role.as_str(), entry.text.as_str()))
            .map(|(role, _)| match role {
                "user" => "one",
                _ => "two",
            })
            .collect();
        assert_eq!(texts, vec!["one", "two"]);
        assert_eq!(entries[0].role, "user");
        assert_eq!(entries[1].role, "assistant");
    }

    // --- dialogs (S17d) ----------------------------------------------------------

    #[test]
    fn the_blocking_dialog_methods_are_known() {
        for method in ["select", "confirm", "input", "editor"] {
            assert!(blocking_dialog(method), "{method} blocks");
        }
        for method in ["notify", "setStatus", "setWidget", "setTitle", "set_editor_text"] {
            assert!(!blocking_dialog(method), "{method} is fire-and-forget");
        }
    }

    // --- the S18 lease refusal mapping ----------------------------------------------

    #[test]
    fn a_live_lease_held_elsewhere_is_typed_already_leased() {
        let error = lease_refusal_to_rpc_error(
            "lease_live_elsewhere",
            "another device holds a live lease on this session",
        );
        assert_eq!(
            error,
            RpcError::AlreadyLeased {
                detail: "another device holds a live lease on this session".to_owned()
            }
        );
        assert!(error.to_string().contains("already in use"));
    }

    #[test]
    fn any_other_lease_refusal_stays_a_transport_error_with_its_reason() {
        let error = lease_refusal_to_rpc_error("something_else", "no");
        assert!(matches!(error, RpcError::Transport { .. }));
        assert!(error.to_string().contains("something_else"));
    }

    // --- the exec line ------------------------------------------------------------------

    #[test]
    fn the_exec_line_is_the_resolved_binary_plus_the_quoted_session() {
        assert_eq!(
            rpc_exec_command("/home/u/.local/bin/gentle-shell", "/tmp/my session.jsonl"),
            "'/home/u/.local/bin/gentle-shell' --mode rpc --session '/tmp/my session.jsonl'"
        );
        // A path with a quote cannot escape its quoting.
        assert_eq!(
            rpc_exec_command("/bin/g", "/tmp/it's.jsonl"),
            "'/bin/g' --mode rpc --session '/tmp/it'\\''s.jsonl'"
        );
    }

    // --- the wire protocol over a real duplex (S17a/b/c/d) ------------------------------
    //
    // The fake child speaks exactly the shapes Pi's `rpc-mode.js` produces:
    // records on stdout, replies correlated by the client's `id`, dialogs
    // answered by `extension_ui_response` records on stdin.

    use tokio::io::{AsyncBufReadExt as _, AsyncWriteExt as _, BufReader};

    /// Scripted stdout: the lines are written up front, then EOF.
    async fn scripted_server(stream: tokio::io::DuplexStream, lines: Vec<Value>) {
        let mut writer = stream;
        for value in lines {
            let _ = writer.write_all(value.to_string().as_bytes()).await;
            let _ = writer.write_all(b"\n").await;
        }
        let _ = writer.flush().await;
        let _ = writer.shutdown().await;
    }

    /// An echoing child: answers every record it receives with a scripted
    /// reply chosen by the test, and reacts to lines the test cares about.
    async fn responding_server(
        stream: tokio::io::DuplexStream,
        reply_for: Arc<dyn Fn(&Value) -> Vec<Value> + Send + Sync>,
    ) {
        tokio::spawn(async move {
            let (reader_half, mut writer) = tokio::io::split(stream);
            let mut lines = BufReader::new(reader_half).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let Some(command) = parse_jsonl_line(&line) else {
                    continue;
                };
                for reply in reply_for(&command) {
                    let _ = writer.write_all(reply.to_string().as_bytes()).await;
                    let _ = writer.write_all(b"\n").await;
                }
                let _ = writer.flush().await;
            }
            let _ = writer.shutdown().await;
        });
    }

    #[tokio::test]
    async fn every_complete_record_arrives_and_a_torn_tail_is_ignored() {
        let (client_half, server_half) = tokio::io::duplex(1024);
        scripted_server(
            server_half,
            vec![
                json!({ "type": "response", "id": "1" }),
                json!({ "type": "agent_settled" }),
            ],
        )
        .await;
        let mut protocol = RpcProtocol::new(client_half);
        let deadline = deadline_in(2_000);
        let first = protocol.next_record(deadline).await.unwrap().unwrap();
        assert_eq!(first.get("id").and_then(Value::as_str), Some("1"));
        let second = protocol.next_record(deadline).await.unwrap().unwrap();
        assert_eq!(second.get("type").and_then(Value::as_str), Some("agent_settled"));
        // EOF after a clean tail: a normal end, not an error (S17b).
        assert_eq!(protocol.next_record(deadline).await.unwrap(), None);
        assert!(protocol.is_eof());
    }

    #[tokio::test]
    async fn a_torn_last_line_at_eof_is_ignored_not_fatal() {
        // S17c: kill -9 mid-append leaves a partial line before EOF.
        let (client_half, mut server_half) = tokio::io::duplex(1024);
        tokio::spawn(async move {
            let _ = server_half
                .write_all(b"{\"type\":\"response\",\"id\":\"1\"}\n{\"type\":\"message_u")
                .await;
            let _ = server_half.flush().await;
            let _ = server_half.shutdown().await;
        });
        let mut protocol = RpcProtocol::new(client_half);
        let deadline = deadline_in(2_000);
        let first = protocol.next_record(deadline).await.unwrap().unwrap();
        assert_eq!(first.get("id").and_then(Value::as_str), Some("1"));
        assert_eq!(protocol.next_record(deadline).await.unwrap(), None, "the torn tail is skipped");
    }

    #[tokio::test]
    async fn a_request_is_answered_by_its_own_id_and_events_drain_past_it() {
        let (client_half, server_half) = tokio::io::duplex(1024);
        let replies = Arc::new(|command: &Value| {
            let id = command.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
            if command.get("type").and_then(Value::as_str) == Some("get_state") {
                // An event before the response: the client must drain it.
                vec![
                    json!({ "type": "extension_ui_request", "id": "n1", "method": "notify", "message": "hi" }),
                    json!({ "type": "response", "id": id, "command": "get_state", "success": true,
                            "data": { "sessionId": "fix-1", "messageCount": 2 } }),
                ]
            } else {
                vec![json!({ "type": "response", "id": id, "command": "other", "success": true })]
            }
        });
        responding_server(server_half, replies).await;
        let mut protocol = RpcProtocol::new(client_half);
        let mut events = Vec::new();
        let response = protocol
            .request("get_state", json!({}), deadline_in(2_000), &mut |event| {
                events.push(event.clone());
                None
            })
            .await
            .expect("answered");
        assert_eq!(response.get("id").and_then(Value::as_str), Some("companion-rpc-1"));
        assert_eq!(events.len(), 1, "the notify dialog drained past the response");
    }

    #[tokio::test]
    async fn a_refused_command_is_a_typed_error_with_the_child_reason() {
        let (client_half, server_half) = tokio::io::duplex(1024);
        let replies: Arc<dyn Fn(&Value) -> Vec<Value> + Send + Sync> =
            Arc::new(|command: &Value| {
                let id = command.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
                vec![json!({
                    "type": "response", "id": id, "command": command.get("type"),
                    "success": false, "error": "no provider"
                })]
            });
        responding_server(server_half, replies).await;
        let mut protocol = RpcProtocol::new(client_half);
        let error = protocol
            .request("prompt", json!({ "message": "hi" }), deadline_in(2_000), &mut |_| None)
            .await
            .expect_err("refused");
        assert_eq!(
            error,
            RpcError::CommandRefused { command: "prompt".to_owned(), detail: "no provider".to_owned() }
        );
    }

    #[tokio::test]
    async fn a_dialog_answer_carries_the_dialog_id_on_the_wire() {
        // S17d: the child emits a blocking dialog; the client's answer must
        // be an `extension_ui_response` with the dialog's id, and the child
        // only then settles.
        let (client_half, server_half) = tokio::io::duplex(1024);
        let replies = Arc::new(|command: &Value| {
            let kind = command.get("type").and_then(Value::as_str).unwrap_or("");
            if kind == "extension_ui_response" {
                // The child saw the answer: settle, in the shape Pi uses.
                return vec![json!({ "type": "agent_settled" })];
            }
            Vec::new()
        });
        responding_server(server_half, replies).await;
        let mut protocol = RpcProtocol::new(client_half);
        protocol
            .answer("dlg-1", &json!({ "cancelled": true }))
            .await
            .expect("answered");
        // The answer went out; nothing more is scripted. The orderly close
        // (stdin EOF) ends the child; close() must return Ok, not treat the
        // EOF as an error (S17b).
        protocol.close(RpcDeadlines::default()).await.expect("clean close");
        assert!(protocol.is_eof());
    }

    #[tokio::test]
    async fn an_answered_dialog_is_seen_by_the_server_with_its_payload() {
        // The answer's exact wire shape: one object per line, with the
        // dialog id and the payload fields the UI supplied.
        let (client_half, server_half) = tokio::io::duplex(1024);
        let seen = Arc::new(std::sync::Mutex::new(Vec::<Value>::new()));
        let seen_for_task = seen.clone();
        let replies = Arc::new(move |command: &Value| {
            seen_for_task.lock().unwrap().push(command.clone());
            Vec::new()
        });
        responding_server(server_half, replies).await;
        let mut protocol = RpcProtocol::new(client_half);
        protocol
            .answer("dlg-7", &json!({ "value": "picked" }))
            .await
            .expect("answered");
        // Let the server task drain the line (current-thread runtime).
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
        let answers = seen.lock().unwrap().clone();
        assert_eq!(answers.len(), 1);
        assert_eq!(answers[0].get("type").and_then(Value::as_str), Some("extension_ui_response"));
        assert_eq!(answers[0].get("id").and_then(Value::as_str), Some("dlg-7"));
        assert_eq!(answers[0].get("value").and_then(Value::as_str), Some("picked"));
    }

    // --- the fresh-start lane (S24-5) --------------------------------------------------

    #[test]
    fn a_missing_start_directory_is_named_in_the_error() {
        let error = RpcError::NotADirectory { path: "/mnt/x/nope".to_owned() };
        assert!(error.to_string().contains("/mnt/x/nope"));
        assert!(error.to_string().contains("directory"));
    }

    #[test]
    fn the_fresh_exec_line_cds_into_the_directory_and_takes_no_session() {
        // The exec runs in the user's home by default: `cd` places the child
        // in the repository so Pi records that cwd in its session header,
        // and there is no `--session` — Pi creates a fresh session file.
        assert_eq!(
            rpc_exec_command_in_dir("/home/u/.local/bin/gentle-shell", "/mnt/x/erp-web"),
            "cd '/mnt/x/erp-web' && exec '/home/u/.local/bin/gentle-shell' --mode rpc"
        );
        // A directory with a quote cannot escape its quoting.
        assert_eq!(
            rpc_exec_command_in_dir("/bin/g", "/tmp/it's"),
            "cd '/tmp/it'\\''s' && exec '/bin/g' --mode rpc"
        );
    }

    #[tokio::test]
    async fn a_fresh_start_takes_the_lease_under_the_childs_own_session_id() {
        let (client_half, server_half) = tokio::io::duplex(1024);
        let replies: Arc<dyn Fn(&Value) -> Vec<Value> + Send + Sync> = Arc::new(|command: &Value| {
            let id = command.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
            match command.get("type").and_then(Value::as_str) {
                Some("get_state") => vec![json!({ "type": "response", "id": id, "command": "get_state", "success": true,
                        "data": { "sessionId": "fresh-1", "messageCount": 0 } })],
                Some("get_messages") => vec![json!({ "type": "response", "id": id, "command": "get_messages", "success": true,
                        "data": { "messages": [] } })],
                _ => Vec::new(),
            }
        });
        responding_server(server_half, replies).await;
        let mut protocol = RpcProtocol::new(client_half);
        let seen = Arc::new(StdMutex::new(Vec::<String>::new()));
        let seen_for_closure = seen.clone();
        let handshake = complete_fresh_start(&mut protocol, RpcDeadlines::default(), move |session_id| {
            let seen = seen_for_closure.clone();
            async move {
                seen.lock().unwrap().push(session_id);
                Ok(crate::sessions::LeaseTakeOutcome::Taken)
            }
        })
        .await
        .expect("the fresh start completed");
        assert_eq!(handshake.session_id, "fresh-1");
        assert_eq!(handshake.message_count, 0);
        assert!(handshake.transcript.is_empty());
        // The lease was asked for exactly once, under the id the child
        // itself reported — not an id the client invented.
        assert_eq!(seen.lock().unwrap().clone(), vec!["fresh-1".to_owned()]);
    }

    #[tokio::test]
    async fn a_failed_fresh_handshake_closes_the_child_and_never_takes_a_lease() {
        // The child refused `get_state`: nothing half-open survives — the
        // child gets stdin EOF, and no lease was ever written.
        let (client_half, server_half) = tokio::io::duplex(1024);
        let replies: Arc<dyn Fn(&Value) -> Vec<Value> + Send + Sync> = Arc::new(|command: &Value| {
            let id = command.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
            if command.get("type").and_then(Value::as_str) == Some("get_state") {
                vec![json!({
                    "type": "response", "id": id, "command": "get_state",
                    "success": false, "error": "no provider"
                })]
            } else {
                Vec::new()
            }
        });
        responding_server(server_half, replies).await;
        let mut protocol = RpcProtocol::new(client_half);
        let lease_asked = Arc::new(StdMutex::new(false));
        let lease_asked_for_closure = lease_asked.clone();
        let error = complete_fresh_start(&mut protocol, RpcDeadlines::default(), move |_session_id| {
            let lease_asked = lease_asked_for_closure.clone();
            async move {
                *lease_asked.lock().unwrap() = true;
                Ok(crate::sessions::LeaseTakeOutcome::Taken)
            }
        })
        .await
        .expect_err("the handshake refused");
        assert!(matches!(error, RpcError::CommandRefused { .. }), "{error}");
        assert!(!*lease_asked.lock().unwrap(), "no lease for a session that never became ours");
        assert!(protocol.is_eof(), "the child was closed (stdin EOF, stdout drained)");
    }

    #[tokio::test]
    async fn a_refused_lease_after_a_fresh_handshake_closes_the_child() {
        // The child started and reported its session id, but the lease take
        // failed: the child must not stay running for a session this device
        // does not hold, and the reason reaches the UI typed.
        let (client_half, server_half) = tokio::io::duplex(1024);
        let replies: Arc<dyn Fn(&Value) -> Vec<Value> + Send + Sync> = Arc::new(|command: &Value| {
            let id = command.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
            match command.get("type").and_then(Value::as_str) {
                Some("get_state") => vec![json!({ "type": "response", "id": id, "command": "get_state", "success": true,
                        "data": { "sessionId": "fresh-2", "messageCount": 0 } })],
                Some("get_messages") => vec![json!({ "type": "response", "id": id, "command": "get_messages", "success": true,
                        "data": { "messages": [] } })],
                _ => Vec::new(),
            }
        });
        responding_server(server_half, replies).await;
        let mut protocol = RpcProtocol::new(client_half);
        let seen = Arc::new(StdMutex::new(Vec::<String>::new()));
        let seen_for_closure = seen.clone();
        let error = complete_fresh_start(&mut protocol, RpcDeadlines::default(), move |session_id| {
            let seen = seen_for_closure.clone();
            async move {
                seen.lock().unwrap().push(session_id);
                Err(RpcError::AlreadyLeased { detail: "taken".to_owned() })
            }
        })
        .await
        .expect_err("the lease take refused");
        assert_eq!(
            error,
            RpcError::AlreadyLeased { detail: "taken".to_owned() }
        );
        assert_eq!(seen.lock().unwrap().clone(), vec!["fresh-2".to_owned()]);
        assert!(protocol.is_eof(), "the child was closed after the refused lease");
    }

    #[tokio::test]
    async fn a_settled_turn_refreshes_the_childs_own_message_count() {
        // S24-5 honesty: after the first exchange, the header must not keep
        // saying `0 messages`. The driver refreshes the count from the
        // child's own `get_state` on every settled turn.
        let (client_half, server_half) = tokio::io::duplex(4096);
        let replies: Arc<dyn Fn(&Value) -> Vec<Value> + Send + Sync> = Arc::new(|command: &Value| {
            let id = command.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
            match command.get("type").and_then(Value::as_str) {
                Some("prompt") => vec![
                    json!({ "type": "response", "id": id, "command": "prompt", "success": true }),
                    json!({ "type": "agent_settled" }),
                ],
                Some("get_messages") => vec![json!({ "type": "response", "id": id, "command": "get_messages", "success": true,
                        "data": { "messages": [
                            { "role": "user", "content": "hi" },
                            { "role": "assistant", "content": [{ "type": "text", "text": "hello" }] }
                        ] } })],
                Some("get_state") => vec![json!({ "type": "response", "id": id, "command": "get_state", "success": true,
                        "data": { "sessionId": "s", "messageCount": 2 } })],
                _ => Vec::new(),
            }
        });
        responding_server(server_half, replies).await;
        let (commands_tx, commands_rx) = crossbeam_channel::bounded::<RpcCommand>(8);
        let shared = Arc::new(StdMutex::new(RpcShared::default()));
        let shared_for_task = shared.clone();
        // A lease pointing at a refused port: the release at the end of the
        // pump fails instantly and quietly — the pump's outcome is untouched.
        let lease = LeaseGuard {
            profile: crate::profile::HostProfile {
                host: "127.0.0.1".to_owned(),
                port: 1,
                user: "u".to_owned(),
                identity_path: "/dev/null".into(),
                known_hosts_path: "/dev/null".into(),
            },
            session_id: "s".to_owned(),
            device_id: "d".to_owned(),
            token: "t".to_owned(),
        };
        let local = tokio::task::LocalSet::new();
        local
            .run_until(async move {
                // The pump runs on a local task: it is not Send (it holds the
                // dialog closure across awaits) — exactly how the production
                // driver thread runs it, block_on on its own runtime.
                let task = tokio::task::spawn_local(drive_rpc_session(
                    RpcProtocol::new(client_half),
                    commands_rx,
                    shared_for_task,
                    lease,
                    RpcDeadlines {
                        response: Duration::from_secs(5),
                        settle: Duration::from_secs(10),
                        exit: Duration::from_secs(5),
                    },
                ));
                let (reply_tx, reply_rx) = crossbeam_channel::bounded(1);
                commands_tx
                    .send(RpcCommand::Prompt { text: "hi".to_owned(), reply: reply_tx })
                    .unwrap();
                // The reply completes at settle. Polling (never a blocking
                // recv here): this async block drives the pump, so blocking
                // it would deadlock the very reply being waited for.
                let transcript = loop {
                    match reply_rx.try_recv() {
                        Ok(result) => break result.expect("the turn succeeded"),
                        Err(crossbeam_channel::TryRecvError::Empty) => {
                            tokio::time::sleep(Duration::from_millis(50)).await
                        }
                        Err(crossbeam_channel::TryRecvError::Disconnected) => {
                            panic!("the reply channel closed before the turn settled")
                        }
                    }
                };
                assert_eq!(transcript.len(), 2, "the refreshed transcript came back");
                assert_eq!(
                    shared.lock().unwrap().message_count,
                    Some(2),
                    "the count is the child's own, not the stale handshake number"
                );
                let (close_tx, _close_rx) = crossbeam_channel::bounded(1);
                commands_tx.send(RpcCommand::Close { reply: close_tx }).unwrap();
                let _ = task.await;
            })
            .await;
    }
}
