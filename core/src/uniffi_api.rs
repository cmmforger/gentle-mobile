//! The UniFFI surface: a deliberately small DTO API for the mobile UIs.
//!
//! This is an additional surface on top of the Rust API, not a rewrite. The
//! mobile side gets exactly four capabilities: a [`HostProfileDto`] record, a
//! [`CompanionConnection::connect`] that returns either a [`ConnectOutcome::Challenge`]
//! (the real `SHA256:` fingerprint plus the presented key blob — never a bypass)
//! or a [`ConnectOutcome::Connected`] live connection, a free
//! [`accept_host_key`] that pins the presented key, and
//! [`CompanionConnection::snapshot`] returning plain DTOs.

use std::{collections::HashMap, path::Path, sync::{Arc, Mutex as StdMutex, Mutex}};

use herdr_client::{
    ConnectOptions, protocol::{AgentStatus, ClientShellAgent, ClientShellPane, ClientShellSnapshot, ClientSurfaceSize},
};
use russh::keys::parse_public_key_base64;

use crate::{Connection, Error, pin, profile::HostProfile};

/// A host the app can reach over SSH. `identity_path` names a device-owned
/// private key file; `known_hosts_path` is the app-private pin store.
#[derive(uniffi::Record, Debug, Clone)]
pub struct HostProfileDto {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub identity_path: String,
    pub known_hosts_path: String,
}

impl HostProfileDto {
    fn into_core(self) -> HostProfile {
        HostProfile {
            host: self.host,
            port: self.port,
            user: self.user,
            identity_path: self.identity_path.into(),
            known_hosts_path: self.known_hosts_path.into(),
        }
    }
}

/// The host-key question shown to the user. `fingerprint` is the presented
/// key's `SHA256:...` string; `key_blob` is the same key in OpenSSH one-line
/// form so that [`accept_host_key`] pins the exact key that was presented.
#[derive(uniffi::Record, Debug, Clone)]
pub struct HostKeyChallenge {
    pub fingerprint: String,
    /// Present only when a pin already existed and did not match.
    pub pinned_fingerprint: Option<String>,
    pub key_blob: String,
}

/// Agent status, word-shaped for the UI.
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum AgentStatusDto {
    Idle,
    Working,
    Blocked,
    Done,
    Unknown,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct WorkspaceDto {
    pub id: String,
    pub number: u64,
    pub label: String,
    pub focused: bool,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct TabDto {
    pub id: String,
    pub workspace_id: String,
    pub number: u64,
    pub label: String,
    pub focused: bool,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct PaneDto {
    pub id: String,
    pub workspace_id: String,
    pub tab_id: String,
    pub label: Option<String>,
    pub cwd: Option<String>,
    pub focused: bool,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct AgentDto {
    pub name: String,
    pub kind: Option<String>,
    pub status: AgentStatusDto,
    pub cwd: Option<String>,
    pub focused: bool,
    /// The pane the agent lives in: the address both `read_pane` and
    /// `prompt_agent` take (the daemon's `agent.prompt` targets accept panes).
    pub pane_id: String,
    /// The workspace the agent's pane belongs to — the key the inbox filters
    /// by when one workspace is selected (S26-2).
    pub workspace_id: String,
}

/// One full snapshot in plain DTOs.
#[derive(uniffi::Record, Debug, Clone)]
pub struct SnapshotDto {
    pub workspaces: Vec<WorkspaceDto>,
    pub tabs: Vec<TabDto>,
    pub panes: Vec<PaneDto>,
    pub agents: Vec<AgentDto>,
}

/// Errors the UI matches on. Pinning failures keep their typed shape; the
/// transport detail collapses into a message.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CompanionError {
    #[error("unknown host key for {host}:{port}; fingerprint {fingerprint}")]
    HostKeyUnknown { host: String, port: u16, fingerprint: String },
    #[error("host key mismatch for {host}:{port}: pinned {pinned}, presented {presented}")]
    HostKeyMismatch { host: String, port: u16, pinned: String, presented: String },
    #[error("the presented key blob is not a valid OpenSSH key")]
    InvalidKeyBlob,
    #[error("timed out: {detail}")]
    Timeout { detail: String },
    #[error("{detail}")]
    Transport { detail: String },
    /// The daemon answered a request with an error object.
    #[error("the host refused the request ({code}): {detail}")]
    RequestRefused { code: String, detail: String },
    /// The daemon's advertised method list has no such method.
    #[error("the host does not offer {method}")]
    UnsupportedMethod { method: String, detail: String },
    /// The file is over the upload size limit; refused before any byte moved.
    #[error("the file is too large: {size_bytes} bytes, the host accepts up to {max_bytes}")]
    UploadTooLarge { size_bytes: u64, max_bytes: u64 },
    /// The file name has no usable form once sanitised.
    #[error("the file has no usable name")]
    UploadEmptyName,
    /// The resume path is not a regular file on the host (T17b).
    #[error("the session is not a file on the host: {path}")]
    RpcSessionNotAFile { path: String },
    /// The directory a fresh session should start in does not exist on the
    /// host, or is not a directory (S24-5).
    #[error("the directory does not exist on the host: {path}")]
    RpcStartNotADirectory { path: String },
    /// A live lease held by another device — refused, never opened (S18).
    #[error("the session is already in use: {detail}")]
    RpcSessionLeasedElsewhere { detail: String },
    /// The host could not spawn `gentle-shell --mode rpc`.
    #[error("the host refused to start the RPC session: {detail}")]
    RpcSpawnRefused { detail: String },
    /// The child did not speak the expected protocol, or reported a
    /// different session id than the list showed.
    #[error("the RPC session did not speak the expected protocol: {detail}")]
    RpcProtocolGarbage { detail: String },
    /// The RPC child answered a command with an error.
    #[error("the RPC session refused {command}: {detail}")]
    RpcRefused { command: String, detail: String },
    /// The resumed session has ended (closed, EOF, or transport death).
    #[error("the RPC session is closed")]
    RpcSessionClosed,
    /// The browsed path does not exist on the host (T27, S30-3).
    #[error("{path} does not exist on the host")]
    BrowseMissing { path: String },
    /// The browsed path exists but is not a directory.
    #[error("{path} is not a directory")]
    BrowseNotADirectory { path: String },
    /// The directory exists but cannot be read.
    #[error("{path} cannot be read (permission denied)")]
    BrowseNoPermission { path: String },
    /// The host could not answer the listing at all — never an empty list
    /// pretending success.
    #[error("the host could not answer the listing: {detail}")]
    BrowseHost { detail: String },
}

impl From<Error> for CompanionError {
    fn from(error: Error) -> Self {
        match error {
            Error::HostKeyUnknown { host, port, fingerprint } => {
                CompanionError::HostKeyUnknown { host, port, fingerprint }
            }
            Error::HostKeyMismatch { host, port, pinned, presented } => {
                CompanionError::HostKeyMismatch { host, port, pinned, presented }
            }
            Error::Timeout(message) => CompanionError::Timeout { detail: message.to_owned() },
            other => CompanionError::Transport { detail: other.to_string() },
        }
    }
}

impl From<crate::paths::BrowseError> for CompanionError {
    fn from(error: crate::paths::BrowseError) -> Self {
        match error {
            crate::paths::BrowseError::Missing { path } => CompanionError::BrowseMissing { path },
            crate::paths::BrowseError::NotADirectory { path } => {
                CompanionError::BrowseNotADirectory { path }
            }
            crate::paths::BrowseError::NoPermission { path } => {
                CompanionError::BrowseNoPermission { path }
            }
            crate::paths::BrowseError::Host { detail } => CompanionError::BrowseHost { detail },
        }
    }
}

impl From<crate::requests::RequestError> for CompanionError {
    fn from(error: crate::requests::RequestError) -> Self {
        CompanionError::from(crate::requests::HubError::Request(error))
    }
}

impl From<crate::requests::HubError> for CompanionError {
    fn from(error: crate::requests::HubError) -> Self {
        match error {
            crate::requests::HubError::Core(error) => CompanionError::from(error),
            crate::requests::HubError::Request(
                crate::requests::RequestError::Refused { code, detail },
            ) => CompanionError::RequestRefused { code, detail },
            crate::requests::HubError::Request(
                crate::requests::RequestError::UnsupportedMethod { method },
            ) => CompanionError::UnsupportedMethod {
                method,
                detail: "checked against the host's advertised method list".to_owned(),
            },
            crate::requests::HubError::Request(
                crate::requests::RequestError::Timeout { detail },
            ) => CompanionError::Timeout { detail },
            crate::requests::HubError::Request(other) => {
                CompanionError::Transport { detail: other.to_string() }
            }
        }
    }
}

/// One agent pane read: exactly the visible text the host would show for
/// that pane, plus whether the daemon had to leave anything out.
#[derive(uniffi::Record, Debug, Clone)]
pub struct PaneReadDto {
    pub pane_id: String,
    pub text: String,
    pub truncated: bool,
}

/// Either the host-key question or a live connection — the two things a
/// connect can be.
#[derive(uniffi::Enum, Debug)]
pub enum ConnectOutcome {
    Challenge(HostKeyChallenge),
    Connected(Arc<CompanionConnection>),
}

/// The upload plan for one attachment: the stored name (timestamp-prefixed,
/// sanitised) and the size ceiling it was checked against. Advisory for the
/// UI — the transfer itself re-checks everything.
#[derive(uniffi::Record, Debug, Clone)]
pub struct UploadPlanDto {
    pub stored_name: String,
    pub max_bytes: u64,
}

/// One confirmed upload: the absolute remote path (resolved by the server)
/// and the exact prompt suffix that hands the file to the agent.
#[derive(uniffi::Record, Debug, Clone)]
pub struct UploadOutcomeDto {
    pub remote_path: String,
    pub prompt_suffix: String,
}

/// Progress callbacks for an in-flight upload: one call per written chunk.
#[uniffi::export(callback_interface)]
pub trait UploadProgressListener: Send + Sync {
    fn on_progress(&self, sent_bytes: u64, total_bytes: u64);
}

/// Plan one attachment before any byte moves: sanitise the name, prefix the
/// compact UTC timestamp, and refuse oversize files instantly.
#[uniffi::export]
pub fn plan_upload(file_name: String, size_bytes: u64) -> Result<UploadPlanDto, CompanionError> {
    crate::uploads::check_size(size_bytes)?;
    let stored_name = crate::uploads::stored_name(
        &file_name,
        std::time::SystemTime::now(),
    )?;
    Ok(UploadPlanDto {
        stored_name,
        max_bytes: crate::uploads::MAX_UPLOAD_BYTES,
    })
}

impl From<crate::uploads::UploadError> for CompanionError {
    fn from(error: crate::uploads::UploadError) -> Self {
        match error {
            crate::uploads::UploadError::TooLarge { size_bytes, max_bytes } => {
                CompanionError::UploadTooLarge { size_bytes, max_bytes }
            }
            crate::uploads::UploadError::EmptyName => CompanionError::UploadEmptyName,
        }
    }
}

/// The final prompt text for a send with uploads: the draft plus one
/// `[attached image: <path>]` line per uploaded file. The UI passes only
/// suffixes handed back by confirmed uploads, so a failed upload can never
/// reach the agent as a path.
#[uniffi::export]
pub fn compose_prompt(text: String, image_suffixes: Vec<String>) -> String {
    crate::uploads::compose_prompt(&text, &image_suffixes)
}

/// One listed Gentle Shell session, in the words the UI shows.
#[derive(uniffi::Record, Debug, Clone)]
pub struct SessionDto {
    /// The session file's name on the host (`<timestamp>_<id>.jsonl`).
    pub file_name: String,
    pub session_id: String,
    pub cwd: Option<String>,
    /// The file's last-write time on the host (SFTP mtime), epoch ms.
    pub last_modified_epoch_ms: u64,
    /// `None` when the file was too big to count cheaply.
    pub message_count: Option<u64>,
    pub lease: LeaseViewDto,
}

/// The advisory-lease view of one session (S18: Pi provides no lock, so
/// the guard is ours).
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum LeaseViewDto {
    /// No lease file: free to open.
    Open,
    /// A live lease held by this device.
    HeldHere,
    /// A live lease held by another device — the row must not offer to open.
    InUseElsewhere,
    /// Stale or malformed claim — reclaimable, with a visible note.
    Stale,
}

impl From<crate::sessions::LeaseView> for LeaseViewDto {
    fn from(view: crate::sessions::LeaseView) -> Self {
        match view {
            crate::sessions::LeaseView::Open => LeaseViewDto::Open,
            crate::sessions::LeaseView::HeldHere => LeaseViewDto::HeldHere,
            crate::sessions::LeaseView::InUseElsewhere => LeaseViewDto::InUseElsewhere,
            crate::sessions::LeaseView::Stale => LeaseViewDto::Stale,
        }
    }
}

/// The outcome of acquiring a lease: `Reclaimed` means a stale (or
/// malformed) claim was replaced, which the UI must show, never silently.
#[derive(uniffi::Enum, Debug, Clone)]
pub enum LeaseTakeOutcomeDto {
    Taken,
    Reclaimed,
}

/// The sessions directory the app lists when the user picks no override:
/// the Gentle Shell agent home (the harness runs with
/// `PI_CODING_AGENT_DIR=~/.gentle-shell/agent`); the listing itself falls
/// back to the plain Pi default when this one does not exist on the host.
#[uniffi::export]
pub fn default_sessions_dir(user: String) -> String {
    crate::sessions::default_sessions_dir(&user)
}

/// List the host's Gentle Shell sessions over the SFTP lane, each with its
/// lease view decided against the host's own clock. Files that are not
/// sessions are skipped; the call fails only when the transport fails.
#[uniffi::export]
pub fn list_sessions(
    profile: HostProfileDto,
    sessions_dir: String,
    device_id: String,
) -> Result<Vec<SessionDto>, CompanionError> {
    let profile = profile.into_core();
    let host_sessions = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| CompanionError::Transport {
            detail: format!("the listing runtime could not start: {error}"),
        })?
        .block_on(crate::sessions::list_sessions_over_sftp(
            &profile,
            &sessions_dir,
            &device_id,
        ))?;
    Ok(host_sessions
        .into_iter()
        .map(|session| SessionDto {
            file_name: session.file_name,
            session_id: session.summary.session_id,
            cwd: session.summary.cwd,
            last_modified_epoch_ms: session.summary.last_modified_epoch_ms,
            message_count: session.summary.message_count,
            lease: LeaseViewDto::from(session.lease),
        })
        .collect())
}

/// Acquire (or reclaim) the advisory lease for one session. A live lease
/// held by another device is refused typed. `Ok(Reclaimed)` obliges the UI
/// to show the stale-lease note.
#[uniffi::export]
pub fn acquire_lease(
    profile: HostProfileDto,
    session_id: String,
    device_id: String,
    token: String,
) -> Result<LeaseTakeOutcomeDto, CompanionError> {
    let profile = profile.into_core();
    let outcome = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| CompanionError::Transport {
            detail: format!("the lease runtime could not start: {error}"),
        })?
        .block_on(crate::sessions::acquire_lease_over_sftp(
            &profile,
            &session_id,
            &device_id,
            &token,
        ))?;
    Ok(match outcome {
        crate::sessions::LeaseTakeOutcome::Taken => LeaseTakeOutcomeDto::Taken,
        crate::sessions::LeaseTakeOutcome::Reclaimed => LeaseTakeOutcomeDto::Reclaimed,
    })
}

/// Refresh our heartbeat on the host. `Ok(false)` means the lease is no
/// longer ours — the UI must surface the loss, never keep claiming.
#[uniffi::export]
pub fn refresh_lease(
    profile: HostProfileDto,
    session_id: String,
    device_id: String,
    token: String,
) -> Result<bool, CompanionError> {
    let profile = profile.into_core();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| CompanionError::Transport {
            detail: format!("the lease runtime could not start: {error}"),
        })?
        .block_on(crate::sessions::refresh_lease_over_sftp(
            &profile,
            &session_id,
            &device_id,
            &token,
        ))
        .map_err(CompanionError::from)
}

/// Release our lease. `Ok(true)` when the file was absent or was ours and
/// is now removed; `Ok(false)` when another device holds it — we never
/// delete somebody else's claim.
#[uniffi::export]
pub fn release_lease(
    profile: HostProfileDto,
    session_id: String,
    device_id: String,
    token: String,
) -> Result<bool, CompanionError> {
    let profile = profile.into_core();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| CompanionError::Transport {
            detail: format!("the lease runtime could not start: {error}"),
        })?
        .block_on(crate::sessions::release_lease_over_sftp(
            &profile,
            &session_id,
            &device_id,
            &token,
        ))
        .map_err(CompanionError::from)
}

// --- The RPC lane (T17b, S15/S17/S18) ---------------------------------------

/// One transcript line of a resumed session: role and plain text, nothing
/// invented. Tool calls, tool results and thinking never render.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct TranscriptEntryDto {
    pub role: String,
    pub text: String,
}

impl From<crate::rpc::TranscriptEntry> for TranscriptEntryDto {
    fn from(entry: crate::rpc::TranscriptEntry) -> Self {
        TranscriptEntryDto { role: entry.role, text: entry.text }
    }
}

/// A blocking dialog (S17d) the UI must answer, or the agent waits.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct DialogRequestDto {
    pub dialog_id: String,
    pub method: String,
    pub title: Option<String>,
    pub message: Option<String>,
    pub placeholder: Option<String>,
    pub options: Vec<String>,
}

impl From<crate::rpc::DialogRequest> for DialogRequestDto {
    fn from(dialog: crate::rpc::DialogRequest) -> Self {
        DialogRequestDto {
            dialog_id: dialog.dialog_id,
            method: dialog.method,
            title: dialog.title,
            message: dialog.message,
            placeholder: dialog.placeholder,
            options: dialog.options,
        }
    }
}

impl From<crate::rpc::RpcError> for CompanionError {
    fn from(error: crate::rpc::RpcError) -> Self {
        match error {
            crate::rpc::RpcError::NotAFile { path } => CompanionError::RpcSessionNotAFile { path },
            crate::rpc::RpcError::NotADirectory { path } => {
                CompanionError::RpcStartNotADirectory { path }
            }
            crate::rpc::RpcError::AlreadyLeased { detail } => {
                CompanionError::RpcSessionLeasedElsewhere { detail }
            }
            crate::rpc::RpcError::SpawnRefused { detail } => CompanionError::RpcSpawnRefused { detail },
            crate::rpc::RpcError::ProtocolGarbage { detail } => {
                CompanionError::RpcProtocolGarbage { detail }
            }
            crate::rpc::RpcError::CommandRefused { command, detail } => {
                CompanionError::RpcRefused { command, detail }
            }
            crate::rpc::RpcError::Disconnected => CompanionError::RpcSessionClosed,
            crate::rpc::RpcError::Timeout { detail } => CompanionError::Timeout { detail },
            crate::rpc::RpcError::Transport { detail } => CompanionError::Transport { detail },
        }
    }
}

/// One resumed Gentle Shell session: the child process behind the pinned
/// SSH channel, driven from its own thread. `close` releases the advisory
/// lease (S18); so does any unexpected end of the pump, and so does losing
/// the object without closing it.
#[derive(uniffi::Object)]
pub struct RpcResumedSession {
    commands: StdMutex<Option<crossbeam_channel::Sender<crate::rpc::RpcCommand>>>,
    handle: StdMutex<Option<std::thread::JoinHandle<()>>>,
    shared: Arc<StdMutex<crate::rpc::RpcShared>>,
    session_id: String,
    message_count: u64,
    reclaimed: bool,
}

impl std::fmt::Debug for RpcResumedSession {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("RpcResumedSession")
            .field("session_id", &self.session_id)
            .finish()
    }
}

/// Resume a closed Gentle Shell session over the RPC lane: the advisory
/// lease is taken **before** any child exists (S18 — a live lease held
/// elsewhere is refused typed), then `gentle-shell --mode rpc --session
/// <path>` is spawned over a fresh channel of the pinned transport (no new
/// host key, no new pin), and the handshake verifies the child reports
/// **the same session id** the list showed. `Ok(Reclaimed)` means a stale
/// lease was replaced: the UI owes the user the visible note.
#[uniffi::export]
pub fn resume_session(
    profile: HostProfileDto,
    session_path: String,
    session_id: String,
    device_id: String,
    token: String,
) -> Result<RpcResumeOutcome, CompanionError> {
    let profile = profile.into_core();
    let deadlines = crate::rpc::RpcDeadlines::default();
    // 1. Lease first, one-shot, on this thread (S18).
    let taken = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| CompanionError::Transport {
            detail: format!("the resume runtime could not start: {error}"),
        })?
        .block_on(crate::rpc::take_lease_for_resume(
            &profile,
            &session_path,
            &session_id,
            &device_id,
            &token,
        ))
        .map_err(CompanionError::from)?;
    let reclaimed = matches!(taken, crate::sessions::LeaseTakeOutcome::Reclaimed);
    // 2. The pump thread owns the pinned connection, the child and the
    //    handshake; the caller waits for the handshake result.
    let shared: Arc<StdMutex<crate::rpc::RpcShared>> = Arc::new(StdMutex::new(crate::rpc::RpcShared::default()));
    let (commands_tx, commands_rx) = crossbeam_channel::bounded::<crate::rpc::RpcCommand>(8);
    let (announced_tx, announced_rx) = crossbeam_channel::bounded::<Result<crate::rpc::RpcHandshake, crate::rpc::RpcError>>(1);
    let lease = crate::rpc::LeaseGuard {
        profile: profile.clone(),
        session_id: session_id.clone(),
        device_id,
        token,
    };
    let shared_for_thread = shared.clone();
    let handle = std::thread::Builder::new()
        .name("gentle-rpc".to_owned())
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build();
            match runtime {
                Ok(runtime) => runtime.block_on(crate::rpc::run_resumed_session(
                    profile,
                    session_path,
                    session_id.clone(),
                    lease,
                    commands_rx,
                    shared_for_thread,
                    announced_tx,
                    deadlines,
                )),
                Err(error) => {
                    let _ = announced_tx.send(Err(crate::rpc::RpcError::Transport {
                        detail: format!("the RPC pump runtime could not start: {error}"),
                    }));
                    // The lease cannot be refreshed by a dead pump: give it
                    // back via a fresh one-shot runtime.
                    if let Ok(release_runtime) = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                    {
                        let _ = release_runtime.block_on(crate::sessions::release_lease_over_sftp(
                            &lease.profile,
                            &lease.session_id,
                            &lease.device_id,
                            &lease.token,
                        ));
                    }
                }
            }
        })
        .map_err(|error| CompanionError::Transport {
            detail: format!("the RPC pump thread could not start: {error}"),
        })?;
    // 3. The handshake decides the outcome.
    let handshake = announced_rx
        .recv_timeout(deadlines.response + deadlines.exit)
        .map_err(|_| CompanionError::Timeout {
            detail: "the RPC session never completed its handshake".to_owned(),
        })?
        .map_err(CompanionError::from)?;
    Ok(RpcResumeOutcome {
        session: Arc::new(RpcResumedSession {
            commands: StdMutex::new(Some(commands_tx)),
            handle: StdMutex::new(Some(handle)),
            shared,
            session_id: handshake.session_id,
            message_count: handshake.message_count,
            reclaimed,
        }),
        transcript: handshake.transcript.into_iter().map(TranscriptEntryDto::from).collect(),
        reclaimed,
    })
}

/// The result of a successful resume: the live session, its transcript as
/// it stands, and whether a stale lease had to be reclaimed (the visible
/// note, S18).
#[derive(uniffi::Record)]
pub struct RpcResumeOutcome {
    pub session: Arc<RpcResumedSession>,
    pub transcript: Vec<TranscriptEntryDto>,
    pub reclaimed: bool,
}

/// Start a fresh Gentle Shell session in `cwd` (S24-5): the directory must
/// exist on the host, then `gentle-shell --mode rpc` is spawned **without
/// `--session`** over a fresh channel of the pinned transport with the
/// child's working directory set to `cwd`, the handshake learns the session
/// id the child itself chose, and the advisory lease is taken under that id
/// — only once the session exists. A missing directory, a missing binary,
/// a spawn refusal or a failed handshake reports the reason typed and
/// leaves nothing half-open: no lease is ever written for a session that
/// never became ours, and the pump releases the lease on every exit path.
#[uniffi::export]
pub fn start_session_in_dir(
    profile: HostProfileDto,
    cwd: String,
    device_id: String,
    token: String,
) -> Result<RpcResumeOutcome, CompanionError> {
    let profile = profile.into_core();
    let deadlines = crate::rpc::RpcDeadlines::default();
    let shared: Arc<StdMutex<crate::rpc::RpcShared>> =
        Arc::new(StdMutex::new(crate::rpc::RpcShared::default()));
    let (commands_tx, commands_rx) = crossbeam_channel::bounded::<crate::rpc::RpcCommand>(8);
    let (announced_tx, announced_rx) =
        crossbeam_channel::bounded::<Result<crate::rpc::RpcHandshake, crate::rpc::RpcError>>(1);
    let shared_for_thread = shared.clone();
    let handle = std::thread::Builder::new()
        .name("gentle-rpc".to_owned())
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build();
            match runtime {
                Ok(runtime) => runtime.block_on(crate::rpc::run_fresh_session(
                    profile,
                    cwd,
                    device_id,
                    token,
                    commands_rx,
                    shared_for_thread,
                    announced_tx,
                    deadlines,
                )),
                Err(error) => {
                    let _ = announced_tx.send(Err(crate::rpc::RpcError::Transport {
                        detail: format!("the RPC pump runtime could not start: {error}"),
                    }));
                }
            }
        })
        .map_err(|error| CompanionError::Transport {
            detail: format!("the RPC pump thread could not start: {error}"),
        })?;
    let handshake = announced_rx
        .recv_timeout(deadlines.response + deadlines.exit)
        .map_err(|_| CompanionError::Timeout {
            detail: "the RPC session never completed its handshake".to_owned(),
        })?
        .map_err(CompanionError::from)?;
    Ok(RpcResumeOutcome {
        session: Arc::new(RpcResumedSession {
            commands: StdMutex::new(Some(commands_tx)),
            handle: StdMutex::new(Some(handle)),
            shared,
            session_id: handshake.session_id,
            message_count: handshake.message_count,
            reclaimed: false,
        }),
        transcript: handshake.transcript.into_iter().map(TranscriptEntryDto::from).collect(),
        reclaimed: false,
    })
}

#[uniffi::export]
impl RpcResumedSession {
    /// The session id the child itself reported (T5: the same one the list
    /// showed).
    pub fn session_id(&self) -> String {
        self.session_id.clone()
    }

    /// The child's own message count at resume time.
    pub fn message_count(&self) -> u64 {
        // The driver refreshes the child's own count after every settled
        // turn; until the first refresh the handshake number stands.
        self.shared
            .lock()
            .ok()
            .and_then(|guarded| guarded.message_count)
            .unwrap_or(self.message_count)
    }

    /// Whether the resume reclaimed a stale lease — the visible note.
    pub fn reclaimed(&self) -> bool {
        self.reclaimed
    }

    /// The transcript as the driver last saw it.
    pub fn transcript(&self) -> Vec<TranscriptEntryDto> {
        self.shared
            .lock()
            .map(|guarded| guarded.transcript.iter().cloned().map(TranscriptEntryDto::from).collect())
            .unwrap_or_default()
    }

    /// The blocking dialog waiting for an answer, if any (S17d).
    pub fn pending_dialog(&self) -> Option<DialogRequestDto> {
        self.shared
            .lock()
            .ok()
            .and_then(|guarded| guarded.pending_dialog.clone())
            .map(DialogRequestDto::from)
    }

    /// True once the pump ended — orderly close, EOF, or transport death.
    /// The lease is released by then in every case (S18).
    pub fn is_closed(&self) -> bool {
        self.shared.lock().map(|guarded| guarded.closed).unwrap_or(true)
    }

    /// The reason the pump ended, once it has.
    pub fn last_error(&self) -> Option<String> {
        self.shared.lock().ok().and_then(|guarded| guarded.last_error.clone())
    }

    /// Append one prompt through the RPC lane and wait for the turn to
    /// settle. Returns the refreshed transcript — the caller replaces what
    /// it renders with this, so the reply is exactly what the child has.
    pub fn send_prompt(&self, text: String) -> Result<Vec<TranscriptEntryDto>, CompanionError> {
        let sender = self.commands.lock().map_err(|_| CompanionError::RpcSessionClosed)?.clone();
        let sender = sender.ok_or(CompanionError::RpcSessionClosed)?;
        let (reply_tx, reply_rx) = crossbeam_channel::bounded(1);
        sender
            .send(crate::rpc::RpcCommand::Prompt { text, reply: reply_tx })
            .map_err(|_| CompanionError::RpcSessionClosed)?;
        reply_rx
            .recv()
            .map_err(|_| CompanionError::RpcSessionClosed)?
            .map(|entries| entries.into_iter().map(TranscriptEntryDto::from).collect())
            .map_err(CompanionError::from)
    }

    /// Answer one blocking dialog. `cancelled` answers with the documented
    /// default; otherwise `value` answers select/input/editor and
    /// `confirmed` answers confirm.
    pub fn answer_dialog(
        &self,
        dialog_id: String,
        cancelled: bool,
        value: Option<String>,
        confirmed: Option<bool>,
    ) -> Result<(), CompanionError> {
        let sender = self.commands.lock().map_err(|_| CompanionError::RpcSessionClosed)?.clone();
        let sender = sender.ok_or(CompanionError::RpcSessionClosed)?;
        let payload = if cancelled {
            serde_json::json!({ "cancelled": true })
        } else if let Some(value) = value {
            serde_json::json!({ "value": value })
        } else {
            serde_json::json!({ "confirmed": confirmed.unwrap_or(false) })
        };
        let (reply_tx, reply_rx) = crossbeam_channel::bounded(1);
        sender
            .send(crate::rpc::RpcCommand::AnswerDialog { dialog_id, payload, reply: reply_tx })
            .map_err(|_| CompanionError::RpcSessionClosed)?;
        reply_rx
            .recv()
            .map_err(|_| CompanionError::RpcSessionClosed)?
            .map_err(CompanionError::from)
    }

    /// Orderly shutdown (S17b): unanswered blocking dialogs are cancelled,
    /// stdin gets EOF, stdout is drained to EOF, and the advisory lease is
    /// released (S18). Idempotent. Named `close_session` because the
    /// generated binding already implements `AutoCloseable.close`.
    pub fn close_session(&self) -> Result<(), CompanionError> {
        let sender = self.commands.lock().ok().and_then(|inner| inner.clone());
        if let Some(sender) = sender {
            let (reply_tx, reply_rx) = crossbeam_channel::bounded(1);
            let sent = sender.send(crate::rpc::RpcCommand::Close { reply: reply_tx }).is_ok();
            let _ = self.commands.lock().map(|mut inner| inner.take());
            if sent {
                if let Ok(result) = reply_rx.recv_timeout(std::time::Duration::from_secs(30)) {
                    result.map_err(CompanionError::from)?;
                }
            }
        }
        if let Ok(mut handle) = self.handle.lock() {
            if let Some(handle) = handle.take() {
                let _ = handle.join();
            }
        }
        Ok(())
    }
}

impl std::fmt::Debug for CompanionConnection {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("CompanionConnection")
            .field("connected", &self.inner.lock().map(|inner| inner.is_some()).unwrap_or(false))
            .finish()
    }
}

/// A live connection to one host, owned by the UI.
#[derive(uniffi::Object)]
pub struct CompanionConnection {
    inner: Mutex<Option<Connection>>,
    /// Kept for the lanes that open their own session — today, the SFTP
    /// upload lane. The same pins and device key, always.
    profile: HostProfile,
}

/// The grid the phone asks the daemon to render for this connection.
/// Units are terminal **cells**, not pixels. Valid range 1..=4096 per side
/// and 1_000_000 cells in total — the same bounds herdr-client validates
/// on the wire — anything outside is sanitized to the client's default.
#[derive(uniffi::Record, Debug, Clone)]
pub struct SurfaceSizeDto {
    pub cols: u16,
    pub rows: u16,
}

/// The surface size the connection asks the daemon for: the phone mirrors
/// the desktop's terminal, so the UI requests a desktop-sized grid and the
/// fit scales it. `None` (either side) keeps the client default (80×24) so
/// no existing caller changes behaviour; a value the client's own
/// validation would refuse falls back to the default too — a bad requested
/// size must never break the connect.
fn surface_options(cols: Option<u16>, rows: Option<u16>) -> ConnectOptions {
    let valid = match (cols, rows) {
        (Some(c), Some(r)) => {
            c > 0 && r > 0 && c <= 4096 && r <= 4096 && u32::from(c) * u32::from(r) <= 1_000_000
        }
        _ => false,
    };
    if valid {
        ConnectOptions {
            surface_size: ClientSurfaceSize {
                cols: cols.unwrap_or(80),
                rows: rows.unwrap_or(24),
            },
            cell_width_px: 0,
            cell_height_px: 0,
        }
    } else {
        ConnectOptions::default()
    }
}

/// Connect off the UI thread. Before any data flows, the host key is probed
/// and checked against the pins: unknown or mismatched keys come back as a
/// [`ConnectOutcome::Challenge`] with the real fingerprint and the presented
/// key blob. Only after the user accepts does the next connect succeed —
/// nothing is auto-accepted.
///
/// `surface` is the grid (terminal cells) the daemon renders for this
/// connection; the UI passes a desktop-sized grid so the phone mirrors the
/// desktop's terminal. `None` keeps the client default (80×24).
#[uniffi::export]
pub fn connect(
    profile: HostProfileDto,
    session: String,
    surface: Option<SurfaceSizeDto>,
) -> Result<ConnectOutcome, CompanionError> {
    let profile = profile.into_core();
    let probe = crate::connection::probe_host_key(&profile)?;
    let key_blob = openssh_blob(&probe.key)?;
    let options = surface.map(|s| surface_options(Some(s.cols), Some(s.rows)))
        .unwrap_or_else(ConnectOptions::default);
    match PinDecisionRef::from(probe.decision) {
        PinDecisionRef::Match => {
            let connection =
                Connection::connect(&profile, &session, options)?;
            Ok(ConnectOutcome::Connected(Arc::new(CompanionConnection {
                inner: Mutex::new(Some(connection)),
                profile,
            })))
        }
        PinDecisionRef::Unknown { presented } => {
            Ok(ConnectOutcome::Challenge(HostKeyChallenge {
                fingerprint: presented,
                pinned_fingerprint: None,
                key_blob,
            }))
        }
        PinDecisionRef::Mismatch { pinned, presented } => {
            Ok(ConnectOutcome::Challenge(HostKeyChallenge {
                fingerprint: presented,
                pinned_fingerprint: Some(pinned),
                key_blob,
            }))
        }
    }
}

#[uniffi::export]
impl CompanionConnection {
    /// The most recent full snapshot in plain DTOs, or `None` until the host
    /// has sent one. The UI polls this; every reconnect starts a fresh
    /// baseline so a snapshot is never a stale delta.
    pub fn snapshot(&self) -> Option<SnapshotDto> {
        let inner = self.inner.lock().ok()?;
        let connection = inner.as_ref()?;
        connection.snapshot().map(|snapshot| snapshot_to_dto(&snapshot))
    }

    /// The latest surface frame, decoded into UI DTOs, or `None` until the
    /// host has rendered one. Latest-wins at the source: a burst of frames
    /// between two polls collapses into the newest one, and a fresh
    /// connection starts from an empty store, so the grid is always the new
    /// connection's own baseline.
    pub fn latest_surface(&self) -> Option<crate::surface::SurfaceDto> {
        let inner = self.inner.lock().ok()?;
        let connection = inner.as_ref()?;
        connection.latest_surface()
    }

    /// True once the connection is over, however it ended.
    pub fn is_disconnected(&self) -> bool {
        self.inner
            .lock()
            .ok()
            .and_then(|inner| inner.as_ref().map(Connection::is_disconnected))
            .unwrap_or(true)
    }

    /// Ping the host now instead of trusting a socket that may have died
    /// unnoticed — the foreground-return check (T8c). An unanswered probe
    /// ends the connection, and the next liveness poll reports the drop.
    pub fn probe_liveness(&self) {
        let guard = self.inner.lock().ok();
        if let Some(connection) = guard.as_deref().and_then(|inner| inner.as_ref()) {
            connection.probe_liveness();
        }
    }

    /// Disconnect cleanly and drop the connection.
    pub fn disconnect(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            if let Some(connection) = inner.take() {
                connection.disconnect();
            }
        }
    }

    /// Read the visible text of one pane over the request channel. Fails
    /// typed — a refusal names the daemon's own code, so the UI can show why
    /// instead of an empty pane.
    pub fn read_pane(&self, pane_id: String) -> Result<PaneReadDto, CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        let read = connection
            .pane_read(pane_id.as_str())
            .map_err(CompanionError::from)?;
        Ok(PaneReadDto {
            pane_id: read.pane_id,
            text: read.text,
            truncated: read.truncated,
        })
    }

    /// Submit one prompt to an agent, addressed by its pane id. `Ok(())`
    /// means the daemon confirmed the submission (`agent_prompted`); a
    /// failure names why, so the UI never claims "sent" without a reply.
    pub fn prompt_agent(&self, target: String, prompt: String) -> Result<(), CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        connection
            .agent_prompt(target.as_str(), prompt.as_str())
            .map_err(CompanionError::from)
    }

    /// Send one batch of key presses to the agent's pane — the extra-key
    /// row's method. `keys` are the daemon's own key names (`esc`, `tab`,
    /// `enter`, `up`, `down`, single characters, and `ctrl+`/`alt+`
    /// compositions like `ctrl+c`); the core passes them through untouched.
    /// `Ok(())` means the daemon confirmed delivery (`ok`); a failure names
    /// why, so a chip never claims a press that did not land.
    pub fn send_keys(&self, pane_id: String, keys: Vec<String>) -> Result<(), CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        connection
            .pane_send_keys(pane_id.as_str(), &keys)
            .map_err(CompanionError::from)
    }

    /// The host's workspace list (S26-2): one row per workspace, with the
    /// branch from the repo behind it (a per-workspace `worktree.list`,
    /// measured read-only; a `not_git_worktree` refusal means no branch,
    /// never a failure) and the agent count joined from the latest snapshot.
    pub fn list_workspaces(&self) -> Result<Vec<WorkspaceRowDto>, CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        let workspaces = connection.workspace_list().map_err(CompanionError::from)?;
        let counts = connection
            .snapshot()
            .map(|snapshot| workspace_agent_counts(&snapshot.agents))
            .unwrap_or_default();
        let mut rows = Vec::with_capacity(workspaces.len());
        for workspace in workspaces {
            let workspace_id = workspace.id.clone();
            // A refusal (not_git_worktree) means "no branch", never a screen
            // failure; transport failures still fail the screen honestly.
            let branch = connection.worktree_branch(&workspace_id).map_err(CompanionError::from)?;
            rows.push(WorkspaceRowDto {
                id: workspace.id,
                number: workspace.number,
                label: workspace.label,
                focused: workspace.focused,
                status: status_dto_from_wire(&workspace.agent_status),
                branch,
                agent_count: counts.get(&workspace_id).copied().unwrap_or(0),
            });
        }
        Ok(rows)
    }

    /// Focus one workspace on the host (S26-2): the daemon switches to it,
    /// exactly as tapping it in the herdr TUI does. `Ok(())` means the
    /// daemon confirmed (`workspace_info`); a refusal names why.
    pub fn focus_workspace(&self, workspace_id: String) -> Result<(), CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        connection.workspace_focus(&workspace_id).map_err(CompanionError::from)
    }

    /// Focus one tab of the current workspace (S28-1): the daemon's own
    /// `tab.focus {tab_id}` on the request lane. Verified live on protocol
    /// 22: a tab with no agent is focusable — the daemon answers
    /// `tab_info` either way, and the surface stream (which renders the
    /// focused pane) follows, so the phone's visible pane follows.
    pub fn focus_tab(&self, tab_id: String) -> Result<(), CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        connection.tab_focus(&tab_id).map_err(CompanionError::from)
    }

    /// Ask the daemon to re-render at a new surface size (S28-3): the grid
    /// the phone's terminal card derived from its own geometry. The core
    /// adds no opinion of its own — the client's validation refuses a size
    /// it would never render, so an oversized ask fails here, not on screen.
    pub fn resize_surface(&self, cols: u16, rows: u16) -> Result<(), CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        connection
            .resize_surface(cols, rows)
            .map_err(|error| CompanionError::Transport { detail: error.to_string() })
    }

    /// Activate (or release) this client's surface control — measured in
    /// the daemon's own source: without an activated surface a resize only
    /// reshapes the client's projection and the pane's PTY keeps the
    /// desktop's width, so the phone paints a narrow frame over wide lines
    /// and text disappears. Every reflow activates first; leaving the
    /// session screen releases, handing the geometry control back to the
    /// desktop's own view.
    pub fn set_surface_active(&self, active: bool) -> Result<(), CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        connection
            .set_surface_active(active)
            .map_err(|error| CompanionError::Transport { detail: error.to_string() })
    }

    /// Create a workspace on the host in `cwd` (S26-3). The path must have
    /// been validated first (`checkHostDir`): measured live, the daemon
    /// does **not** refuse a missing directory — it silently falls back to
    /// `$HOME`. `Ok` carries the created workspace and its root pane.
    pub fn create_workspace(
        &self,
        cwd: String,
        label: Option<String>,
    ) -> Result<WorkspaceCreatedDto, CompanionError> {
        let inner = self.inner.lock().map_err(|_| CompanionError::Transport {
            detail: "the connection is not available".to_owned(),
        })?;
        let connection = inner
            .as_ref()
            .ok_or(CompanionError::Transport { detail: "the connection is closed".to_owned() })?;
        let created = connection
            .workspace_create(&cwd, label.as_deref())
            .map_err(CompanionError::from)?;
        Ok(WorkspaceCreatedDto {
            workspace_id: created.workspace.id,
            label: created.workspace.label,
            pane_id: created.root_pane_id,
        })
    }

    /// Upload one attachment to the host over the SFTP subsystem — a fresh
    /// pinned SSH session per upload, into the host's
    /// `~/.local/state/gentle-mobile/uploads/`. Progress is reported per
    /// chunk. `Ok` carries the absolute remote path (resolved by the server)
    /// and the exact prompt suffix for it; a failure means no path exists,
    /// and the caller must not send a prompt that references one.
    pub fn upload_file(
        &self,
        stored_name: String,
        data: Vec<u8>,
        listener: Box<dyn UploadProgressListener>,
    ) -> Result<UploadOutcomeDto, CompanionError> {
        // Re-check the limit against the bytes actually handed over — the
        // plan is advisory, the transfer is the authority.
        crate::uploads::check_size(data.len() as u64)?;
        let profile = self.profile.clone();
        let listener: Box<dyn UploadProgressListener> = listener;
        let progress = |sent: u64, total: u64| listener.on_progress(sent, total);
        let outcome = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| CompanionError::Transport {
                detail: format!("the upload runtime could not start: {error}"),
            })?
            .block_on(crate::uploads::upload_over_sftp(
                &profile,
                &stored_name,
                &data,
                &progress,
            ))?;
        Ok(UploadOutcomeDto {
            remote_path: outcome.remote_path,
            prompt_suffix: outcome.prompt_suffix,
        })
    }

    /// List the machine's disks (T27, S30-3): `/proc/mounts`, read and
    /// parsed over the same one-shot pinned SSH + SFTP lane the uploads
    /// use. Root first, then by mount point; kernel plumbing is skipped on
    /// the host side. A transport failure is typed, never an empty list.
    pub fn list_disks(&self) -> Result<Vec<DiskDto>, CompanionError> {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| CompanionError::Transport {
                detail: format!("the disk-listing runtime could not start: {error}"),
            })?
            .block_on(crate::paths::list_host_disks_over_sftp(&self.profile))
            .map_err(CompanionError::from)
            .map(|disks| disks.into_iter().map(DiskDto::from).collect())
    }

    /// List one directory's folders (T27, S30-3): folders only, hidden
    /// entries skipped, `.git` detected, symlinks reported with their
    /// target and never followed — over the same one-shot pinned lane.
    /// A missing, unreadable or non-directory path is typed, never an
    /// empty list pretending success.
    pub fn list_folders(&self, path: String) -> Result<Vec<FolderEntryDto>, CompanionError> {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| CompanionError::Transport {
                detail: format!("the folder-listing runtime could not start: {error}"),
            })?
            .block_on(crate::paths::list_host_folders_over_sftp(
                &self.profile,
                &path,
            ))
            .map_err(CompanionError::from)
            .map(|rows| rows.into_iter().map(FolderEntryDto::from).collect())
    }
}

/// One disk the `+` picker offers as a place to start a project: the mount
/// point, the short human label, and the filesystem type the row shows.
#[derive(uniffi::Record, Debug, Clone)]
pub struct DiskDto {
    pub mount_point: String,
    pub label: String,
    pub fs_type: String,
}

impl From<crate::paths::Disk> for DiskDto {
    fn from(disk: crate::paths::Disk) -> Self {
        DiskDto {
            mount_point: disk.mount_point,
            label: disk.label,
            fs_type: disk.fs_type,
        }
    }
}

/// One folder of a browsed directory: a `.git` marker when the folder is a
/// repository, and a symlink shown with its target and never followed.
#[derive(uniffi::Record, Debug, Clone)]
pub struct FolderEntryDto {
    pub name: String,
    pub path: String,
    pub is_symlink: bool,
    /// The symlink's target when it could be read; never followed.
    pub target: Option<String>,
    pub is_git_repo: bool,
}

impl From<crate::paths::FolderEntry> for FolderEntryDto {
    fn from(row: crate::paths::FolderEntry) -> Self {
        FolderEntryDto {
            name: row.name,
            path: row.path,
            is_symlink: row.is_symlink,
            target: row.target,
            is_git_repo: row.is_git_repo,
        }
    }
}

/// Pin the presented key for `host:port`. The `key_blob` comes from the
/// challenge, so the pin records the exact key the user was shown. It may be
/// the bare base64 blob or the full OpenSSH one-line form (`<type> <blob>`).
#[uniffi::export]
pub fn accept_host_key(
    known_hosts_path: String,
    host: String,
    port: u16,
    key_blob: String,
) -> Result<(), CompanionError> {
    let key = parse_presented_blob(&key_blob)?;
    pin::accept_host_key(Path::new(&known_hosts_path), &host, port, &key)?;
    Ok(())
}

/// One pinned host as the Settings screen shows it: the OpenSSH hostspec
/// (`host` for port 22, `[host]:port` otherwise) and the pinned key's
/// `SHA256:...` fingerprint — the same string the connect challenge shows.
#[derive(uniffi::Record, Debug, Clone)]
pub struct KnownHostDto {
    pub spec: String,
    pub fingerprint: String,
}

/// Check one absolute path on the host over the SFTP lane (S26-3): exists,
/// is a directory, is readable, and whether it carries a `.git` marker.
/// One-shot pinned session per check, same lane as the lease work.
#[uniffi::export]
pub fn check_host_dir(profile: HostProfileDto, path: String) -> Result<DirCheckDto, CompanionError> {
    let profile = profile.into_core();
    let check = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| CompanionError::Transport {
            detail: format!("the path-check runtime could not start: {error}"),
        })?
        .block_on(crate::paths::check_host_dir_over_sftp(&profile, &path))?;
    Ok(DirCheckDto {
        verdict: match check.verdict {
            crate::paths::DirVerdict::Ok => DirVerdictDto::Ok,
            crate::paths::DirVerdict::Missing => DirVerdictDto::Missing,
            crate::paths::DirVerdict::NotADirectory => DirVerdictDto::NotADirectory,
            crate::paths::DirVerdict::NoPermission => DirVerdictDto::NoPermission,
        },
        is_git_repo: check.is_git_repo,
    })
}

/// List the pinned hosts from the app's known_hosts store. Junk lines and
/// hashed entries the core does not understand are skipped, never errors; a
/// missing file is an empty list.
#[uniffi::export]
pub fn list_known_hosts(known_hosts_path: String) -> Vec<KnownHostDto> {
    pin::load_entries(Path::new(&known_hosts_path))
        .unwrap_or_default()
        .iter()
        .filter_map(|entry| {
            let fingerprint = parse_public_key_base64(&entry.blob)
                .ok()
                .map(|key| pin::fingerprint_of(&key))?;
            Some(entry.specs.iter().map(move |spec| KnownHostDto {
                spec: spec.clone(),
                fingerprint: fingerprint.clone(),
            }))
        })
        .flatten()
        .collect()
}

/// Remove every entry naming `host:port` from the pin store. `true` when an
/// entry was removed — the next connection then challenges the fingerprint
/// again instead of silently matching the old pin.
#[uniffi::export]
pub fn forget_host(
    known_hosts_path: String,
    host: String,
    port: u16,
) -> Result<bool, CompanionError> {
    Ok(pin::forget_host(Path::new(&known_hosts_path), &host, port)?)
}

/// Parse the presented key: the bare base64 blob or the full OpenSSH
/// one-line form. Anything else is an [`CompanionError::InvalidKeyBlob`].
pub(crate) fn parse_presented_blob(key_blob: &str) -> Result<russh::keys::PublicKey, CompanionError> {
    let blob = key_blob
        .split_whitespace()
        .last()
        .unwrap_or(key_blob);
    parse_public_key_base64(blob).map_err(|_| CompanionError::InvalidKeyBlob)
}

/// The OpenSSH one-line form of a presented key.
fn openssh_blob(key: &russh::keys::PublicKey) -> Result<String, CompanionError> {
    key.to_openssh()
        .map_err(|_| CompanionError::Transport { detail: "the presented host key has no OpenSSH form".to_owned() })
}

/// The pin verdict, computed by the core and carried here as plain strings.
enum PinDecisionRef {
    Match,
    Unknown { presented: String },
    Mismatch { pinned: String, presented: String },
}

impl From<pin::PinDecision> for PinDecisionRef {
    fn from(decision: pin::PinDecision) -> Self {
        match decision {
            pin::PinDecision::Match => Self::Match,
            pin::PinDecision::Unknown { presented } => Self::Unknown { presented },
            pin::PinDecision::Mismatch { pinned, presented } => Self::Mismatch { pinned, presented },
        }
    }
}

/// Map the vendored `AgentStatus` to the DTO word.
pub(crate) fn status_dto(status: AgentStatus) -> AgentStatusDto {
    match status {
        AgentStatus::Idle => AgentStatusDto::Idle,
        AgentStatus::Working => AgentStatusDto::Working,
        AgentStatus::Blocked => AgentStatusDto::Blocked,
        AgentStatus::Done => AgentStatusDto::Done,
        AgentStatus::Unknown => AgentStatusDto::Unknown,
    }
}

/// The daemon's wire `agent_status` word (the request lane's
/// `workspace.list` carries it as a plain string) to the DTO word. Anything
/// unknown stays `Unknown` — never invented into a word the host did not
/// send.
pub(crate) fn status_dto_from_wire(word: &str) -> AgentStatusDto {
    match word {
        "idle" => AgentStatusDto::Idle,
        "working" => AgentStatusDto::Working,
        "blocked" => AgentStatusDto::Blocked,
        "done" => AgentStatusDto::Done,
        _ => AgentStatusDto::Unknown,
    }
}

/// `pane_id → agent count` per workspace, from the snapshot's agents —
/// the count the workspace row shows.
pub(crate) fn workspace_agent_counts(agents: &[ClientShellAgent]) -> HashMap<String, u64> {
    let mut counts: HashMap<String, u64> = HashMap::new();
    for agent in agents {
        *counts.entry(agent.workspace_id.clone()).or_insert(0) += 1;
    }
    counts
}

/// One row of the host's workspace list (S26-2): the human's own label, the
/// branch when the repo behind the workspace has one, the aggregate state
/// word, and how many agents work in it.
#[derive(uniffi::Record, Debug, Clone)]
pub struct WorkspaceRowDto {
    pub id: String,
    pub number: u64,
    pub label: String,
    pub focused: bool,
    pub status: AgentStatusDto,
    /// The branch the workspace's checkout is on; `None` when the host gave
    /// no branch (no git repo, or the daemon could not say).
    pub branch: Option<String>,
    pub agent_count: u64,
}

/// A workspace created through the `+` flow (S26-3): its id, its label, and
/// the root pane the agents will appear in.
#[derive(uniffi::Record, Debug, Clone)]
pub struct WorkspaceCreatedDto {
    pub workspace_id: String,
    pub label: String,
    pub pane_id: Option<String>,
}

/// The verdict of a host-path check over SFTP (S26-3): the plain-sentence
/// rejections map from these words in the UI.
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum DirVerdictDto {
    Ok,
    Missing,
    NotADirectory,
    NoPermission,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct DirCheckDto {
    pub verdict: DirVerdictDto,
    pub is_git_repo: bool,
}

/// `pane_id → cwd` for agent lookup, preferring the pane's foreground cwd.
pub(crate) fn pane_cwds(panes: &[ClientShellPane]) -> HashMap<&str, Option<&str>> {
    panes
        .iter()
        .map(|pane| {
            (
                pane.pane_id.as_str(),
                pane.foreground_cwd.as_deref().or(pane.cwd.as_deref()),
            )
        })
        .collect()
}

/// One agent DTO: name (with a readable fallback), kind, status, cwd from its
/// pane, and focus.
pub(crate) fn agent_dto(agent: &ClientShellAgent, cwd: Option<&str>) -> AgentDto {
    let kind = agent
        .display_agent
        .clone()
        .filter(|kind| !kind.is_empty())
        .or_else(|| agent.agent.clone().filter(|kind| !kind.is_empty()));
    let name = agent
        .name
        .clone()
        .filter(|name| !name.is_empty())
        .or_else(|| kind.clone())
        .unwrap_or_else(|| "<unnamed>".to_owned());
    AgentDto {
        name,
        kind,
        status: status_dto(agent.agent_status),
        cwd: cwd.map(str::to_owned),
        focused: agent.focused,
        pane_id: agent.pane_id.clone(),
        workspace_id: agent.workspace_id.clone(),
    }
}

/// Map the vendored full snapshot to plain DTOs.
pub(crate) fn snapshot_to_dto(snapshot: &ClientShellSnapshot) -> SnapshotDto {
    let cwds = pane_cwds(&snapshot.panes);
    SnapshotDto {
        workspaces: snapshot
            .workspaces
            .iter()
            .map(|workspace| WorkspaceDto {
                id: workspace.workspace_id.clone(),
                number: workspace.number as u64,
                label: workspace.label.clone(),
                focused: workspace.focused,
            })
            .collect(),
        tabs: snapshot
            .tabs
            .iter()
            .map(|tab| TabDto {
                id: tab.tab_id.clone(),
                workspace_id: tab.workspace_id.clone(),
                number: tab.number as u64,
                label: tab.label.clone(),
                focused: tab.focused,
            })
            .collect(),
        panes: snapshot
            .panes
            .iter()
            .map(|pane| PaneDto {
                id: pane.pane_id.clone(),
                workspace_id: pane.workspace_id.clone(),
                tab_id: pane.tab_id.clone(),
                label: pane.label.clone(),
                cwd: pane.foreground_cwd.clone().or(pane.cwd.clone()),
                focused: pane.focused,
            })
            .collect(),
        agents: snapshot
            .agents
            .iter()
            .map(|agent| {
                let cwd = cwds
                    .get(agent.pane_id.as_str())
                    .copied()
                    .flatten();
                agent_dto(agent, cwd)
            })
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::requests::RequestError;

    // --- surface size (S27: the phone mirrors the desktop's terminal) ----------

    #[test]
    fn no_surface_asked_keeps_the_client_default() {
        let options = surface_options(None, None);
        assert_eq!(options.surface_size.cols, 80);
        assert_eq!(options.surface_size.rows, 24);
    }

    #[test]
    fn an_explicit_desktop_size_travels_to_the_options() {
        let options = surface_options(Some(120), Some(40));
        assert_eq!(options.surface_size.cols, 120);
        assert_eq!(options.surface_size.rows, 40);
    }

    #[test]
    fn an_invalid_size_falls_back_to_the_default_and_never_breaks_the_connect() {
        // Zero, oversized, or a half-specified size: the client's own
        // validation would refuse these, so the core sanitizes them back to
        // the default instead of failing the connect.
        for (cols, rows) in [
            (Some(0u16), Some(40u16)),
            (Some(120u16), Some(0u16)),
            (Some(5000u16), Some(40u16)),
            (Some(2000u16), Some(2000u16)), // area over the 1M-cell guard
            (Some(120u16), None),
        ] {
            let options = surface_options(cols, rows);
            assert_eq!(options.surface_size.cols, 80, "cols {cols:?}");
            assert_eq!(options.surface_size.rows, 24, "rows {rows:?}");
        }
    }

    // A hand-built pane and agent: enough to test the DTO join, without a
    // running host.
    fn pane(id: &str, cwd: Option<&str>, foreground_cwd: Option<&str>) -> ClientShellPane {
        ClientShellPane {
            pane_id: id.to_owned(),
            workspace_id: "ws1".to_owned(),
            tab_id: "tab1".to_owned(),
            label: None,
            cwd: cwd.map(str::to_owned),
            foreground_cwd: foreground_cwd.map(str::to_owned),
            focused: false,
            right_click_passthrough: false,
        }
    }

    fn agent(_id: &str, pane_id: &str, name: Option<&str>, status: AgentStatus) -> ClientShellAgent {
        ClientShellAgent {
            pane_id: pane_id.to_owned(),
            workspace_id: "ws1".to_owned(),
            tab_id: "tab1".to_owned(),
            name: name.map(str::to_owned),
            display_agent: Some("pi".to_owned()),
            agent: Some("gentle-agent".to_owned()),
            title: None,
            terminal_title: None,
            terminal_title_stripped: None,
            agent_status: status,
            state_change_seq: 1,
            state_labels: Vec::new(),
            tokens: Vec::new(),
            focused: false,
        }
    }

    #[test]
    fn every_agent_status_maps_to_a_word() {
        assert_eq!(status_dto(AgentStatus::Idle), AgentStatusDto::Idle);
        assert_eq!(status_dto(AgentStatus::Working), AgentStatusDto::Working);
        assert_eq!(status_dto(AgentStatus::Blocked), AgentStatusDto::Blocked);
        assert_eq!(status_dto(AgentStatus::Done), AgentStatusDto::Done);
        assert_eq!(status_dto(AgentStatus::Unknown), AgentStatusDto::Unknown);
    }

    #[test]
    fn presented_blob_parses_bare_and_openssh_forms() {
        // The bare blob, as known_hosts stores it.
        assert!(parse_presented_blob(pin::KEY1_BLOB).is_ok());
        // The full OpenSSH one-line form, as the challenge carries it.
        let full = format!("ssh-ed25519 {}", pin::KEY1_BLOB);
        let parsed = parse_presented_blob(&full).expect("full line must parse");
        assert_eq!(
            pin::fingerprint_of(&parsed),
            pin::fingerprint_of(&parse_public_key_base64(pin::KEY1_BLOB).expect("test blob")),
            "parsed fingerprint must match the bare blob"
        );
        // Junk is refused, not accepted.
        assert!(matches!(
            parse_presented_blob("not a key"),
            Err(CompanionError::InvalidKeyBlob)
        ));
    }

    #[test]
    fn agent_cwd_comes_from_its_pane_foreground_first() {
        let panes = vec![
            pane("p1", Some("/srv/a"), Some("/srv/a/build")),
            pane("p2", Some("/srv/b"), None),
        ];
        let cwds = pane_cwds(&panes);
        assert_eq!(cwds.get("p1").copied().flatten(), Some("/srv/a/build"));
        // A pane with no foreground cwd falls back to its cwd.
        assert_eq!(cwds.get("p2").copied().flatten(), Some("/srv/b"));
    }

    #[test]
    fn agent_dto_joins_cwd_and_falls_back_on_a_blank_name() {
        let cwd_agent = agent("a1", "p1", Some("review-payments"), AgentStatus::Working);
        let dto = agent_dto(&cwd_agent, Some("/srv/a/build"));
        assert_eq!(dto.name, "review-payments");
        assert_eq!(dto.kind.as_deref(), Some("pi"));
        assert_eq!(dto.status, AgentStatusDto::Working);
        assert_eq!(dto.cwd.as_deref(), Some("/srv/a/build"));
        assert!(!dto.focused);
        assert_eq!(dto.pane_id, "p1", "the agent's pane is the request address");

        let blank = agent("a2", "p2", Some(""), AgentStatus::Idle);
        let dto = agent_dto(&blank, None);
        assert_eq!(dto.name, "pi", "a blank name falls back to the kind");
        assert_eq!(dto.cwd, None);
    }

    #[test]
    fn agent_dto_carries_its_workspace_id() {
        // S26-2: the inbox filters by workspace, so the DTO must carry the
        // workspace the vendored snapshot already names.
        let dto = agent_dto(&agent("a1", "p1", Some("x"), AgentStatus::Idle), None);
        assert_eq!(dto.workspace_id, "ws1");
    }

    #[test]
    fn wire_agent_status_words_map_to_the_dto_word() {
        // Measured live: `workspace.list` carries agent_status as a plain
        // string. Only the daemon's own words map; anything else stays
        // unknown, never invented.
        assert_eq!(status_dto_from_wire("idle"), AgentStatusDto::Idle);
        assert_eq!(status_dto_from_wire("working"), AgentStatusDto::Working);
        assert_eq!(status_dto_from_wire("blocked"), AgentStatusDto::Blocked);
        assert_eq!(status_dto_from_wire("done"), AgentStatusDto::Done);
        assert_eq!(status_dto_from_wire("unknown"), AgentStatusDto::Unknown);
        assert_eq!(status_dto_from_wire(""), AgentStatusDto::Unknown);
        assert_eq!(status_dto_from_wire("something-new"), AgentStatusDto::Unknown);
    }

    #[test]
    fn workspace_agent_counts_count_by_workspace() {
        let mut other = agent("a2", "p9", None, AgentStatus::Idle);
        other.workspace_id = "w9".to_owned();
        let counts = workspace_agent_counts(&[agent("a1", "p1", None, AgentStatus::Idle), other]);
        assert_eq!(counts.get("ws1"), Some(&1));
        assert_eq!(counts.get("w9"), Some(&1));
        assert_eq!(counts.get("w8"), None, "a workspace without agents has no entry");
    }

    #[test]
    fn known_hosts_list_and_forget_round_trip() -> Result<(), Box<dyn std::error::Error>> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("known_hosts");
        std::fs::write(
            &path,
            format!(
                "[127.0.0.1]:2424 ssh-ed25519 {}\nother.example ssh-ed25519 {}\nhost.example ssh-ed25519 not-base64!!\n",
                pin::KEY1_BLOB,
                pin::KEY2_BLOB
            ),
        )?;
        // The malformed line is skipped, not an error; the two real pins come
        // back with their spec and the OpenSSH fingerprint.
        let listed = list_known_hosts(path.to_str().expect("utf8 path").to_owned());
        assert_eq!(listed.len(), 2);
        assert_eq!(listed[0].spec, "[127.0.0.1]:2424");
        assert_eq!(
            listed[0].fingerprint,
            pin::fingerprint_of(&parse_public_key_base64(pin::KEY1_BLOB).expect("test blob"))
        );
        assert_eq!(listed[1].spec, "other.example");
        assert_eq!(
            listed[1].fingerprint,
            pin::fingerprint_of(&parse_public_key_base64(pin::KEY2_BLOB).expect("test blob"))
        );
        // Forgetting removes exactly that entry and only once.
        assert!(forget_host(path.to_str().expect("utf8 path").to_owned(), "127.0.0.1".to_owned(), 2424)?);
        assert_eq!(list_known_hosts(path.to_str().expect("utf8 path").to_owned()).len(), 1);
        assert!(!forget_host(path.to_str().expect("utf8 path").to_owned(), "127.0.0.1".to_owned(), 2424)?);
        // A file of only junk parses to an empty list, never an error.
        let junk = dir.path().join("junk");
        std::fs::write(&junk, "short-line\nhost.example ssh-ed25519 not-base64!!\n")?;
        assert!(list_known_hosts(junk.to_str().expect("utf8 path").to_owned()).is_empty());
        Ok(())
    }

    // --- browse errors (T27, S30-3): typed, never a fake empty list -------------

    #[test]
    fn browse_errors_map_to_typed_companion_variants() {
        let missing = CompanionError::from(crate::paths::BrowseError::Missing {
            path: "/nope".to_owned(),
        });
        assert!(matches!(missing, CompanionError::BrowseMissing { ref path } if path == "/nope"), "{missing:?}");

        let not_dir = CompanionError::from(crate::paths::BrowseError::NotADirectory {
            path: "/file".to_owned(),
        });
        assert!(matches!(not_dir, CompanionError::BrowseNotADirectory { ref path } if path == "/file"), "{not_dir:?}");

        let denied = CompanionError::from(crate::paths::BrowseError::NoPermission {
            path: "/locked".to_owned(),
        });
        assert!(matches!(denied, CompanionError::BrowseNoPermission { ref path } if path == "/locked"), "{denied:?}");

        let host = CompanionError::from(crate::paths::BrowseError::Host {
            detail: "transport died".to_owned(),
        });
        assert!(matches!(host, CompanionError::BrowseHost { ref detail } if detail == "transport died"), "{host:?}");
    }

#[test]
fn request_errors_map_to_typed_companion_variants_with_detail() {
    use crate::uniffi_api::CompanionError;
    let refused = CompanionError::from(RequestError::Refused {
        code: "agent_blocked".to_owned(),
        detail: "agent is blocked".to_owned(),
    });
    assert!(matches!(refused, CompanionError::RequestRefused { ref code, ref detail }
        if code == "agent_blocked" && detail == "agent is blocked"), "{refused:?}");

    let unsupported = CompanionError::from(RequestError::UnsupportedMethod {
        method: "agent.prompt".to_owned(),
    });
    assert!(matches!(unsupported, CompanionError::UnsupportedMethod { ref method, .. }
        if method == "agent.prompt"), "{unsupported:?}");

    let bad = CompanionError::from(RequestError::BadResponse { detail: "shape drifted".to_owned() });
    assert!(matches!(bad, CompanionError::Transport { ref detail }
        if detail.contains("shape drifted")), "{bad:?}");

    let gone = CompanionError::from(RequestError::Disconnected);
    assert!(matches!(gone, CompanionError::Transport { .. }), "{gone:?}");

    let late = CompanionError::from(RequestError::Timeout { detail: "no reply in 10s".to_owned() });
    assert!(
        matches!(late, CompanionError::Timeout { ref detail } if detail.contains("no reply")),
        "{late:?}"
    );
}

    #[test]
    fn snapshot_to_dto_preserves_counts_and_names() {
        let panes = vec![pane("p1", Some("/srv/a"), None)];
        let agents = vec![agent("a1", "p1", None, AgentStatus::Done)];
        let snapshot = ClientShellSnapshot {
            boot_id: "boot".to_owned(),
            revision: 1,
            config_diagnostic: None,
            product_announcement: None,
            update_available: None,
            update_install_command: String::new(),
            server_keybindings_toml: None,
            latest_release_notes_available: false,
            integration_updates_available: false,
            worktree_directory: "/srv".to_owned(),
            release_notes: None,
            focused_workspace_id: Some("ws1".to_owned()),
            focused_tab_id: Some("tab1".to_owned()),
            focused_pane_id: Some("p1".to_owned()),
            tab_bar_right: Vec::new(),
            tab_bar_right_separator: String::new(),
            agent_view_label: None,
            agent_order: vec!["a1".to_owned()],
            workspaces: Vec::new(),
            tabs: Vec::new(),
            panes,
            agents,
            commands: Vec::new(),
        };
        let dto = snapshot_to_dto(&snapshot);
        assert_eq!(dto.workspaces.len(), 0);
        assert_eq!(dto.tabs.len(), 0);
        assert_eq!(dto.panes.len(), 1);
        assert_eq!(dto.agents.len(), 1);
        assert_eq!(dto.agents[0].name, "pi", "unnamed agent falls back to kind");
        assert_eq!(dto.agents[0].status, AgentStatusDto::Done);
        assert_eq!(dto.agents[0].cwd.as_deref(), Some("/srv/a"));
    }
}
