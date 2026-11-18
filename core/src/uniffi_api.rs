//! The UniFFI surface: a deliberately small DTO API for the mobile UIs.
//!
//! This is an additional surface on top of the Rust API, not a rewrite. The
//! mobile side gets exactly four capabilities: a [`HostProfileDto`] record, a
//! [`CompanionConnection::connect`] that returns either a [`ConnectOutcome::Challenge`]
//! (the real `SHA256:` fingerprint plus the presented key blob — never a bypass)
//! or a [`ConnectOutcome::Connected`] live connection, a free
//! [`accept_host_key`] that pins the presented key, and
//! [`CompanionConnection::snapshot`] returning plain DTOs.

use std::{collections::HashMap, path::Path, sync::{Arc, Mutex}};

use herdr_client::{
    ConnectOptions, protocol::{AgentStatus, ClientShellAgent, ClientShellPane, ClientShellSnapshot},
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

/// Connect off the UI thread. Before any data flows, the host key is probed
/// and checked against the pins: unknown or mismatched keys come back as a
/// [`ConnectOutcome::Challenge`] with the real fingerprint and the presented
/// key blob. Only after the user accepts does the next connect succeed —
/// nothing is auto-accepted.
#[uniffi::export]
pub fn connect(profile: HostProfileDto, session: String) -> Result<ConnectOutcome, CompanionError> {
    let profile = profile.into_core();
    let probe = crate::connection::probe_host_key(&profile)?;
    let key_blob = openssh_blob(&probe.key)?;
    match PinDecisionRef::from(probe.decision) {
        PinDecisionRef::Match => {
            let connection =
                Connection::connect(&profile, &session, ConnectOptions::default())?;
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
