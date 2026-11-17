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

/// Either the host-key question or a live connection — the two things a
/// connect can be.
#[derive(uniffi::Enum, Debug)]
pub enum ConnectOutcome {
    Challenge(HostKeyChallenge),
    Connected(Arc<CompanionConnection>),
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

    /// True once the connection is over, however it ended.
    pub fn is_disconnected(&self) -> bool {
        self.inner
            .lock()
            .ok()
            .and_then(|inner| inner.as_ref().map(Connection::is_disconnected))
            .unwrap_or(true)
    }

    /// Disconnect cleanly and drop the connection.
    pub fn disconnect(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            if let Some(connection) = inner.take() {
                connection.disconnect();
            }
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

        let blank = agent("a2", "p2", Some(""), AgentStatus::Idle);
        let dto = agent_dto(&blank, None);
        assert_eq!(dto.name, "pi", "a blank name falls back to the kind");
        assert_eq!(dto.cwd, None);
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
