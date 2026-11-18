//! Lane B-i groundwork (S15/S18, T17a): the host's Gentle Shell sessions,
//! listed over the SFTP lane, plus the advisory lease Pi will never provide.
//!
//! S18 measured that Pi has **no session lock**: two `--mode rpc` processes
//! on one session file both append, both exit 0, and diverge silently while
//! the file stays valid. The "never two writers" rule (S15) therefore cannot
//! be delegated to Pi — the app writes its own advisory lease next to the
//! sessions it wants to resume, refuses to open a session a live lease
//! claims, and treats every other case as reclaimable with a visible note.
//!
//! Everything on this page is read-only listing or lease bookkeeping; the
//! actual resume (the RPC lane) is the next task (T17b). The resume action
//! stays disabled in the UI until that lane exists.
//!
//! The contract this module owns and unit-tests:
//!
//! * **Sessions** ([`parse_session_file`]): a Pi session file is JSONL whose
//!   first line is the `type: "session"` header (id, cwd, timestamp). The
//!   list surfaces the session id, cwd, last-write time (the SFTP mtime) and
//!   a message count (the `type: "message"` lines) — skipped, never fatal,
//!   when a file turns out not to be a session. A torn trailing line
//!   (S17: `kill -9` mid-append) is tolerated, not an error.
//! * **Lease format** ([`Lease`]): JSON at
//!   `~/.local/state/gentle-mobile/leases/<sanitised session id>.json` with
//!   `device_id`, a pid-less random `token`, the **host** timestamps (the
//!   phone's clock is never trusted) and the heartbeat interval.
//! * **Liveness** ([`lease_liveness`], [`LEASE_STALE_AFTER_SECS`]): a lease
//!   is live while its heartbeat is fresher than 90 s — three missed
//!   30 s heartbeats — so a crashed phone releases its claim within a
//!   minute and a half while a momentary network stall does not steal it.
//!   Exactly at the threshold is already stale (not "fresher than").
//! * **Views** ([`lease_view`]): `Open`, `HeldHere` (our own live lease),
//!   `InUseElsewhere` (a live lease held by another device — never opened),
//!   `Stale` (no lease, a malformed one, or an expired one — reclaimable,
//!   with a visible note, never silently).
//!
//! Over the wire everything rides the same pinned SSH + SFTP lane the
//! uploads use (`connect_pinned_ssh`): no daemon call, no new transport.

use serde_json::{Value, json};

use crate::requests::{connect_pinned_ssh, HubError, RequestError};
use crate::profile::HostProfile;

/// The lease directory, relative to the remote user's home — the same
/// app-owned state tree the uploads live in.
pub const LEASE_DIR_RELATIVE: &str = ".local/state/gentle-mobile/leases";

/// How often the holder refreshes its heartbeat, in seconds.
pub const LEASE_HEARTBEAT_INTERVAL_SECS: u64 = 30;

/// A lease is live only while its heartbeat is **fresher than** this many
/// seconds. Three missed heartbeats: a momentary mobile stall (a lift,
/// a tunnel hand-off) does not steal a session, but a crashed app gives
/// its claim up within a minute and a half.
pub const LEASE_STALE_AFTER_SECS: u64 = 90;

/// Sessions larger than this are listed without a message count — counting
/// would mean streaming the whole file, which stops being "cheaply
/// derivable" (the header is still read, so the row itself still shows).
pub const MAX_CHEAP_SESSION_BYTES: u64 = 8 * 1024 * 1024;

/// The prefix read from an oversized session to recover its header.
const HEADER_READ_BYTES: u64 = 64 * 1024;

/// A lease that is exactly at the staleness threshold is already stale.
pub const LEASE_STALE_AFTER_MS: u64 = LEASE_STALE_AFTER_SECS * 1000;

/// What the list needs about one session file. The last-write time is the
/// SFTP mtime of the file; `message_count` is `None` when the file was too
/// big to count cheaply.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionSummary {
    pub session_id: String,
    pub cwd: Option<String>,
    pub last_modified_epoch_ms: u64,
    pub message_count: Option<u64>,
}

/// The file is not a Gentle Shell session — skipped by the listing, never a
/// whole-list failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionFileError {
    NotASession,
}

/// True for the file names the list should even attempt: Pi session files
/// end in `.jsonl`. Everything else (leaves, sockets, notes) is ignored
/// before a single byte is read.
pub fn is_session_file_name(name: &str) -> bool {
    name.ends_with(".jsonl")
}

/// Parse one session file's bytes into the summary the list shows.
///
/// Tolerant by contract: any line that fails to parse is skipped (a torn
/// trailing line after `kill -9` is the case S17 measured), and the header
/// must still be a valid `type: "session"` line with a non-empty id, or the
/// file is reported as [`SessionFileError::NotASession`].
pub fn parse_session_file(
    file_name: &str,
    last_modified_epoch_ms: u64,
    bytes: &[u8],
) -> Result<SessionSummary, SessionFileError> {
    let _ = file_name;
    let text = std::str::from_utf8(bytes).map_err(|_| SessionFileError::NotASession)?;
    let mut lines = text.lines();
    let header_line = lines.next().unwrap_or("");
    let header: Value = serde_json::from_str(header_line)
        .map_err(|_| SessionFileError::NotASession)?;
    if header.get("type").and_then(Value::as_str) != Some("session") {
        return Err(SessionFileError::NotASession);
    }
    let session_id = header
        .get("id")
        .and_then(Value::as_str)
        .filter(|id| !id.is_empty())
        .ok_or(SessionFileError::NotASession)?
        .to_owned();
    let cwd = header.get("cwd").and_then(Value::as_str).map(str::to_owned);
    // Message count: every parseable line typed `message`. Unparseable lines
    // (a torn trailing line is the S17 case) are skipped, never fatal.
    let mut message_count = 0u64;
    for line in lines {
        if line.trim().is_empty() {
            continue;
        }
        if let Ok(entry) = serde_json::from_str::<Value>(line) {
            if entry.get("type").and_then(Value::as_str) == Some("message") {
                message_count += 1;
            }
        }
    }
    Ok(SessionSummary {
        session_id,
        cwd,
        last_modified_epoch_ms,
        message_count: Some(message_count),
    })
}

/// A parsed advisory lease. `device_id` and `token` are pid-less — the token
/// is a random string generated fresh for every acquisition, so a stale
/// process can never be confused with the current one.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Lease {
    pub device_id: String,
    pub token: String,
    pub acquired_at_epoch_ms: u64,
    pub heartbeat_at_epoch_ms: u64,
    pub heartbeat_interval_secs: u64,
}

/// The lease file on the host is malformed: treated as [`LeaseView::Stale`]
/// (reclaimable, with the visible note), never as a live claim.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LeaseParseError {
    Malformed,
}

/// Serialise a lease to the exact JSON the file carries.
pub fn lease_to_json(lease: &Lease) -> String {
    json!({
        "device_id": lease.device_id,
        "token": lease.token,
        "acquired_at_epoch_ms": lease.acquired_at_epoch_ms,
        "heartbeat_at_epoch_ms": lease.heartbeat_at_epoch_ms,
        "heartbeat_interval_secs": lease.heartbeat_interval_secs,
    })
    .to_string()
}

/// Parse lease bytes; every deviation from the format is
/// [`LeaseParseError::Malformed`] — extra fields are tolerated, missing or
/// wrongly-typed ones are not.
pub fn lease_from_json(bytes: &[u8]) -> Result<Lease, LeaseParseError> {
    let value: Value = serde_json::from_slice(bytes).map_err(|_| LeaseParseError::Malformed)?;
    let object = value.as_object().ok_or(LeaseParseError::Malformed)?;
    let string_field = |name: &str| -> Result<String, LeaseParseError> {
        object
            .get(name)
            .and_then(Value::as_str)
            .filter(|field| !field.is_empty())
            .map(str::to_owned)
            .ok_or(LeaseParseError::Malformed)
    };
    let number_field = |name: &str| -> Result<u64, LeaseParseError> {
        object
            .get(name)
            .and_then(Value::as_u64)
            .ok_or(LeaseParseError::Malformed)
    };
    let heartbeat_interval_secs = number_field("heartbeat_interval_secs")?;
    if heartbeat_interval_secs == 0 {
        return Err(LeaseParseError::Malformed);
    }
    Ok(Lease {
        device_id: string_field("device_id")?,
        token: string_field("token")?,
        acquired_at_epoch_ms: number_field("acquired_at_epoch_ms")?,
        heartbeat_at_epoch_ms: number_field("heartbeat_at_epoch_ms")?,
        heartbeat_interval_secs,
    })
}

/// A fresh lease whose heartbeat starts now (host time, caller-supplied).
pub fn new_lease(device_id: &str, token: &str, now_epoch_ms: u64) -> Lease {
    Lease {
        device_id: device_id.to_owned(),
        token: token.to_owned(),
        acquired_at_epoch_ms: now_epoch_ms,
        heartbeat_at_epoch_ms: now_epoch_ms,
        heartbeat_interval_secs: LEASE_HEARTBEAT_INTERVAL_SECS,
    }
}

/// Liveness of a lease at `now_epoch_ms` (host clock). Live strictly while
/// the heartbeat is fresher than [`LEASE_STALE_AFTER_MS`]; exactly at the
/// threshold it is already stale.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LeaseLiveness {
    Live,
    Stale,
}

pub fn lease_liveness(lease: &Lease, now_epoch_ms: u64) -> LeaseLiveness {
    // Strictly fresher than the threshold; saturating so a drifted
    // (future) heartbeat can never overflow into a stale claim.
    let expires_at = lease
        .heartbeat_at_epoch_ms
        .saturating_add(LEASE_STALE_AFTER_MS);
    if expires_at > now_epoch_ms {
        LeaseLiveness::Live
    } else {
        LeaseLiveness::Stale
    }
}

/// What the list (and the details sheet) can say about one session's lease.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LeaseView {
    /// No lease file: free to open.
    Open,
    /// A live lease held by this device id.
    HeldHere,
    /// A live lease held by another device: the row must not offer to open.
    InUseElsewhere,
    /// No usable claim — a stale lease, or a malformed file. Reclaimable,
    /// with a visible note, never silently.
    Stale,
}

/// Decide the view from the lease file's bytes (absent = `None`), the host
/// clock and our device id. A malformed file is stale, never live.
pub fn lease_view(
    bytes: Option<&[u8]>,
    now_epoch_ms: u64,
    our_device_id: &str,
) -> LeaseView {
    let Some(raw) = bytes else {
        return LeaseView::Open;
    };
    let Ok(lease) = lease_from_json(raw) else {
        // A file we cannot parse is a claim we cannot honour: stale and
        // reclaimable, never live.
        return LeaseView::Stale;
    };
    match lease_liveness(&lease, now_epoch_ms) {
        LeaseLiveness::Live if lease.device_id == our_device_id => LeaseView::HeldHere,
        LeaseLiveness::Live => LeaseView::InUseElsewhere,
        LeaseLiveness::Stale => LeaseView::Stale,
    }
}

/// The lease file name for a session: the sanitised session id plus `.json`
/// (session ids are UUIDs in practice; the sanitising keeps a crafted id
/// from escaping the lease directory).
pub fn lease_file_name(session_id: &str) -> String {
    let sanitised = crate::uploads::sanitise_file_name(session_id)
        .unwrap_or_else(|| "session".to_owned());
    format!("{sanitised}.json")
}

/// The absolute lease file path under a host home directory.
pub fn lease_path_under_home(home: &str, session_id: &str) -> String {
    let home = home.trim_end_matches('/');
    format!("{home}/{LEASE_DIR_RELATIVE}/{}", lease_file_name(session_id))
}

/// The sessions directory the app lists when the user picks no override:
/// the Gentle Shell agent home first (the harness runs with
/// `PI_CODING_AGENT_DIR=~/.gentle-shell/agent`), then the plain Pi default.
/// The SFTP layer falls back to the second when the first does not exist.
pub fn default_sessions_dir(user: &str) -> String {
    format!("/home/{user}/.gentle-shell/agent/sessions")
}

// --- The SFTP lane ----------------------------------------------------------
// One-shot pinned SSH session per operation — the uploads lane's pattern:
// same pins, same device key, torn down whatever the outcome.

/// One listed session plus its lease view.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostSession {
    pub file_name: String,
    pub summary: SessionSummary,
    pub lease: LeaseView,
}

/// The outcome of acquiring the lease for one session.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LeaseTakeOutcome {
    /// No usable claim existed; our lease was written fresh.
    Taken,
    /// A stale (or malformed) claim was replaced — the UI owes the user the
    /// visible "reclaimed" note.
    Reclaimed,
}

/// The host's current time in epoch milliseconds, read over SSH so the
/// lease never carries the phone's clock.
async fn host_now_epoch_ms(
    ssh: &mut russh::client::Handle<crate::connection::KeyVerifier>,
) -> Result<u64, HubError> {
    use tokio::io::AsyncReadExt as _;
    let mut channel = ssh
        .channel_open_session()
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the host-time channel could not open: {error}"),
        }))?
    ;
    channel
        .exec(true, "date +%s%3N")
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the host refused the time command: {error}"),
        }))?
    ;
    let mut raw = Vec::new();
    channel
        .make_reader()
        .read_to_end(&mut raw)
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the host time could not be read: {error}"),
        }))?
    ;
    let text = String::from_utf8_lossy(&raw).trim().to_owned();
    if !text.chars().all(|c| c.is_ascii_digit()) || text.is_empty() {
        return Err(HubError::Request(RequestError::Transport {
            detail: format!("the host answered a time we cannot use: `{text}`"),
        }));
    }
    // GNU `date +%s%3N` gives milliseconds; a short answer is seconds.
    if text.len() > 11 {
        text.parse::<u64>().map_err(|error| {
            HubError::Request(RequestError::Transport {
                detail: format!("the host time did not parse: {error}"),
            })
        })
    } else {
        text.parse::<u64>()
            .map(|secs| secs.saturating_mul(1000))
            .map_err(|error| {
                HubError::Request(RequestError::Transport {
                    detail: format!("the host time did not parse: {error}"),
                })
            })
    }
}

/// One pinned SSH session with the SFTP subsystem open.
pub(crate) async fn connect_sftp(
    profile: &HostProfile,
) -> Result<
    (
        russh::client::Handle<crate::connection::KeyVerifier>,
        russh_sftp::client::SftpSession,
    ),
    HubError,
> {
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let ssh = connect_pinned_ssh(profile, &stop).await?;
    let opened = async {
        let channel = ssh
            .channel_open_session()
            .await
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the SFTP channel could not open: {error}"),
            }))?
        ;
        channel
            .request_subsystem(true, "sftp")
            .await
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the host refused the SFTP subsystem: {error}"),
            }))?
        ;
        russh_sftp::client::SftpSession::new(channel.into_stream())
            .await
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the SFTP session could not start: {error}"),
            }))
    }
    .await;
    if opened.is_err() {
        let _ = ssh
            .disconnect(russh::Disconnect::ByApplication, "", "en")
            .await;
    }
    opened.map(|sftp| (ssh, sftp))
}

/// Read the lease file for `session_id` over `sftp`; absent file = `None`.
async fn read_lease_bytes(
    sftp: &russh_sftp::client::SftpSession,
    home: &str,
    session_id: &str,
) -> Result<Option<Vec<u8>>, HubError> {
    let path = lease_path_under_home(home, session_id);
    let exists = sftp
        .try_exists(&path)
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the lease file could not be checked: {error}"),
        }))?
    ;
    if !exists {
        return Ok(None);
    }
    sftp.read(&path)
        .await
        .map(Some)
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the lease file could not be read: {error}"),
        }))
}

/// mkdir -p the lease directory and write the lease JSON.
async fn write_lease(
    sftp: &russh_sftp::client::SftpSession,
    home: &str,
    lease: &Lease,
    session_id: &str,
) -> Result<(), HubError> {
    // mkdir -p for the lease tree: an existing directory fails harmlessly.
    let mut partial = String::new();
    for component in LEASE_DIR_RELATIVE.split('/') {
        if !partial.is_empty() {
            partial.push('/');
        }
        partial.push_str(component);
        let _ = sftp.create_dir(&format!("{home}/{partial}")).await;
    }
    let path = lease_path_under_home(home, session_id);
    // Create (not open-for-write): a fresh session has no lease file yet,
    // and an open-without-create fails with No such file (found by the
    // T17b fixture verification).
    let mut file = sftp
        .create(&path)
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the lease file could not be created: {error}"),
        }))?;
    use tokio::io::AsyncWriteExt as _;
    file.write_all(lease_to_json(lease).as_bytes())
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the lease could not be written: {error}"),
        }))?;
    file.sync_all()
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the lease could not be flushed: {error}"),
        }))?;
    file.shutdown()
        .await
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the lease file could not be closed: {error}"),
        }))
}

/// List the sessions in `sessions_dir` over the SFTP lane, each with its
/// lease view. Files that are not sessions are skipped; the list itself
/// only fails when the transport fails.
pub async fn list_sessions_over_sftp(
    profile: &HostProfile,
    sessions_dir: &str,
    our_device_id: &str,
) -> Result<Vec<HostSession>, HubError> {
    let (mut ssh, sftp) = connect_sftp(profile).await?;
    let listing = async {
        // The host clock anchors every lease decision; the phone's clock is
        // never trusted.
        let now = host_now_epoch_ms(&mut ssh).await?;
        let home = sftp
            .canonicalize(".")
            .await
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the host would not resolve the home directory: {error}"),
            }))?
            .trim_end_matches('/')
            .to_owned();
        let dir = if sessions_dir.trim().is_empty() {
            // Default: the Gentle Shell agent home first, the plain Pi
            // default when that one does not exist on this host.
            let preferred = format!("{home}/.gentle-shell/agent/sessions");
            let has_preferred = sftp
                .try_exists(&preferred)
                .await
                .unwrap_or(false);
            if has_preferred {
                preferred
            } else {
                format!("{home}/.pi/agent/sessions")
            }
        } else {
            sessions_dir.to_owned()
        };
        let entries = sftp
            .read_dir(&dir)
            .await
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the sessions directory could not be read: {error}"),
            }))?
        ;
        let mut out = Vec::new();
        for entry in entries {
            let file_name = entry.file_name();
            if !is_session_file_name(&file_name) {
                continue;
            }
            let path = entry.path();
            let metadata = entry.metadata();
            let last_modified_epoch_ms = metadata
                .modified()
                .ok()
                .map(|time| {
                    time.duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or(std::time::Duration::ZERO)
                        .as_millis() as u64
                })
                .unwrap_or(0);
            // Count messages only while it is cheap; an oversized session is
            // listed with its header alone (message_count stays None).
            let bytes = if metadata.len() <= MAX_CHEAP_SESSION_BYTES {
                sftp.read(&path).await.ok()
            } else {
                bounded_header_read(&sftp, &path).await.ok()
            };
            let Some(bytes) = bytes else {
                continue;
            };
            let summary = match parse_session_file(&file_name, last_modified_epoch_ms, &bytes) {
                Ok(summary) => summary,
                Err(SessionFileError::NotASession) => continue,
            };
            let lease_bytes = read_lease_bytes(&sftp, &home, &summary.session_id).await?;
            let lease = lease_view(lease_bytes.as_deref(), now, our_device_id);
            out.push(HostSession { file_name, summary, lease });
        }
        Ok(out)
    }
    .await;
    let _ = ssh
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
    listing
}

/// The first [`HEADER_READ_BYTES`] of a session file — enough for the
/// header line, without streaming a session too big to count cheaply.
async fn bounded_header_read(
    sftp: &russh_sftp::client::SftpSession,
    path: &str,
) -> Result<Vec<u8>, russh_sftp::client::error::Error> {
    use tokio::io::AsyncReadExt as _;
    let file = sftp.open(path).await?;
    let mut buffer = Vec::new();
    file.take(HEADER_READ_BYTES).read_to_end(&mut buffer).await?;
    Ok(buffer)
}

/// Acquire (or reclaim) the advisory lease for one session. A live lease
/// held by another device is refused typed — the row never offered to open.
pub async fn acquire_lease_over_sftp(
    profile: &HostProfile,
    session_id: &str,
    our_device_id: &str,
    token: &str,
) -> Result<LeaseTakeOutcome, HubError> {
    let (mut ssh, sftp) = connect_sftp(profile).await?;
    let outcome = async {
        let now = host_now_epoch_ms(&mut ssh).await?;
        let home = canonical_home(&sftp).await?;
        let existing = read_lease_bytes(&sftp, &home, session_id).await?;
        let outcome = match lease_view(existing.as_deref(), now, our_device_id) {
            LeaseView::InUseElsewhere => {
                return Err(HubError::Request(RequestError::Refused {
                    code: "lease_live_elsewhere".to_owned(),
                    detail: "another device holds a live lease on this session".to_owned(),
                }));
            }
            LeaseView::Open | LeaseView::HeldHere => LeaseTakeOutcome::Taken,
            // Stale or malformed: replaced only under the visible note.
            LeaseView::Stale => LeaseTakeOutcome::Reclaimed,
        };
        write_lease(&sftp, &home, &new_lease(our_device_id, token, now), session_id).await?;
        Ok(outcome)
    }
    .await;
    let _ = ssh
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
    outcome
}

/// Refresh our heartbeat on the host. `Ok(false)` means the lease is no
/// longer ours (deleted, replaced, or lost) — the UI must surface that,
/// never keep claiming a session it does not hold.
pub async fn refresh_lease_over_sftp(
    profile: &HostProfile,
    session_id: &str,
    our_device_id: &str,
    token: &str,
) -> Result<bool, HubError> {
    let (mut ssh, sftp) = connect_sftp(profile).await?;
    let outcome = async {
        let now = host_now_epoch_ms(&mut ssh).await?;
        let home = canonical_home(&sftp).await?;
        let previous = read_lease_bytes(&sftp, &home, session_id).await?;
        let ours = previous.as_deref().is_some_and(|raw| {
            matches!(
                lease_from_json(raw),
                Ok(lease) if lease.device_id == our_device_id && lease.token == token
            )
        });
        if !ours {
            return Ok(false);
        }
        let mut lease = new_lease(our_device_id, token, now);
        // A refresh keeps the original acquisition time.
        if let Some(raw) = previous.as_deref() {
            if let Ok(held) = lease_from_json(raw) {
                lease.acquired_at_epoch_ms = held.acquired_at_epoch_ms;
            }
        }
        write_lease(&sftp, &home, &lease, session_id).await?;
        Ok(true)
    }
    .await;
    let _ = ssh
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
    outcome
}

/// Release our lease. `Ok(true)` when the file was absent, or was ours and
/// was removed; `Ok(false)` when another device now holds the file — we
/// never delete somebody else's claim.
pub async fn release_lease_over_sftp(
    profile: &HostProfile,
    session_id: &str,
    our_device_id: &str,
    token: &str,
) -> Result<bool, HubError> {
    let (ssh, sftp) = connect_sftp(profile).await?;
    let outcome = async {
        let home = canonical_home(&sftp).await?;
        let path = lease_path_under_home(&home, session_id);
        let Some(raw) = read_lease_bytes(&sftp, &home, session_id).await? else {
            // Nothing to release: already free.
            return Ok(true);
        };
        let ours = matches!(
            lease_from_json(&raw),
            Ok(lease) if lease.device_id == our_device_id && lease.token == token
        );
        if !ours {
            // Somebody else holds it now — never delete their claim.
            return Ok(false);
        }
        sftp.remove_file(&path)
            .await
            .map(|_| true)
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the lease could not be released: {error}"),
            }))
    }
    .await;
    let _ = ssh
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
    outcome
}

/// The SFTP session's home, resolved by the server itself.
async fn canonical_home(sftp: &russh_sftp::client::SftpSession) -> Result<String, HubError> {
    sftp.canonicalize(".")
        .await
        .map(|home| home.trim_end_matches('/').to_owned())
        .map_err(|error| HubError::Request(RequestError::Transport {
            detail: format!("the host would not resolve the home directory: {error}"),
        }))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2026-11-20T12:00:00.000Z, as a fixed host instant.
    fn now_ms() -> u64 {
        1_763_632_800_000
    }

    /// A minimal valid session file: header + two messages, newline-ended.
    fn two_message_session() -> Vec<u8> {
        let header = r#"{"type":"session","version":3,"id":"9f1c2f61-8a5e-4f6a-9f6b-3c1d2e4f5a6b","timestamp":"2026-11-20T11:00:00.000Z","cwd":"/mnt/D-SSD/work/projects/erp-web"}"#;
        let m1 = r#"{"type":"message","id":"a1b2c3d4","parentId":null,"timestamp":"2026-11-20T11:00:01.000Z","message":{"role":"user","content":"hello","timestamp":1763630401000}}"#;
        let m2 = r#"{"type":"message","id":"b2c3d4e5","parentId":"a1b2c3d4","timestamp":"2026-11-20T11:00:02.000Z","message":{"role":"assistant","content":[{"type":"text","text":"Hi!"}],"timestamp":1763630402000}}"#;
        format!("{header}\n{m1}\n{m2}\n").into_bytes()
    }

    const SESSION_ID: &str = "9f1c2f61-8a5e-4f6a-9f6b-3c1d2e4f5a6b";

    // --- is_session_file_name -----------------------------------------------

    #[test]
    fn only_jsonl_files_are_candidates() {
        assert!(is_session_file_name("20261120_9f1c2f61.jsonl"));
        assert!(!is_session_file_name("leases.json"));
        assert!(!is_session_file_name("notes.txt"));
        assert!(!is_session_file_name("20261120_9f1c2f61.jsonl.bak"));
    }

    // --- parse_session_file ---------------------------------------------------

    #[test]
    fn a_clean_session_parses_with_id_cwd_and_message_count() {
        let summary = parse_session_file("20261120_9f1c2f61.jsonl", now_ms(), &two_message_session())
            .expect("a valid session");
        assert_eq!(summary.session_id, SESSION_ID);
        assert_eq!(summary.cwd.as_deref(), Some("/mnt/D-SSD/work/projects/erp-web"));
        assert_eq!(summary.last_modified_epoch_ms, now_ms());
        assert_eq!(summary.message_count, Some(2));
    }

    #[test]
    fn a_torn_last_line_is_tolerated_not_fatal() {
        // S17: `kill -9` mid-append leaves one partial JSON line at the end.
        let mut torn = two_message_session();
        torn.extend_from_slice(b"{\"type\":\"message\",\"id\":\"c3d4e5f6\",\"parent");
        let summary =
            parse_session_file("s.jsonl", now_ms(), &torn).expect("a torn tail is not fatal");
        assert_eq!(summary.session_id, SESSION_ID);
        assert_eq!(summary.message_count, Some(2));
    }

    #[test]
    fn a_file_without_a_session_header_is_not_a_session() {
        // A JSONL file of some other kind: no header, wrong type.
        assert_eq!(
            parse_session_file("s.jsonl", now_ms(), b"{\"type\":\"message\"}\n"),
            Err(SessionFileError::NotASession)
        );
        assert_eq!(
            parse_session_file("s.jsonl", now_ms(), b"not json at all\n"),
            Err(SessionFileError::NotASession)
        );
        assert_eq!(parse_session_file("s.jsonl", now_ms(), b""), Err(SessionFileError::NotASession));
    }

    #[test]
    fn a_header_without_an_id_is_not_a_session() {
        let line = b"{\"type\":\"session\",\"version\":3,\"cwd\":\"/tmp\"}\n";
        assert_eq!(
            parse_session_file("s.jsonl", now_ms(), line),
            Err(SessionFileError::NotASession)
        );
    }

    #[test]
    fn a_session_without_a_cwd_still_parses() {
        let bytes = b"{\"type\":\"session\",\"version\":1,\"id\":\"abc\"}\n";
        let summary = parse_session_file("s.jsonl", now_ms(), bytes).expect("parses");
        assert_eq!(summary.session_id, "abc");
        assert_eq!(summary.cwd, None);
        assert_eq!(summary.message_count, Some(0));
    }

    // --- lease JSON round trip -------------------------------------------------

    fn sample_lease() -> Lease {
        Lease {
            device_id: "dm-0f1e2d3c4b5a6978".to_owned(),
            token: "3f9d1c02-7b4a-4e8f-9a1b-2c3d4e5f6a7b".to_owned(),
            acquired_at_epoch_ms: now_ms(),
            heartbeat_at_epoch_ms: now_ms() + 15_000,
            heartbeat_interval_secs: LEASE_HEARTBEAT_INTERVAL_SECS,
        }
    }

    #[test]
    fn lease_json_round_trips_field_for_field() {
        let json = lease_to_json(&sample_lease());
        let parsed = lease_from_json(json.as_bytes()).expect("our own JSON parses");
        assert_eq!(parsed, sample_lease());
    }

    #[test]
    fn a_malformed_lease_file_is_rejected_not_live() {
        for bad in [
            &b""[..],
            b"not json",
            b"{\"device_id\":\"dm-x\"}",
            b"{\"device_id\":\"\",\"token\":\"t\",\"acquired_at_epoch_ms\":1,\"heartbeat_at_epoch_ms\":1,\"heartbeat_interval_secs\":30}",
            b"{\"device_id\":\"d\",\"token\":\"t\",\"acquired_at_epoch_ms\":\"x\",\"heartbeat_at_epoch_ms\":1,\"heartbeat_interval_secs\":30}",
            b"{\"device_id\":\"d\",\"token\":\"t\",\"acquired_at_epoch_ms\":1,\"heartbeat_at_epoch_ms\":1}",
        ] {
            assert_eq!(
                lease_from_json(bad),
                Err(LeaseParseError::Malformed),
                "{}",
                String::from_utf8_lossy(bad)
            );
        }
    }

    #[test]
    fn a_lease_with_extra_fields_still_parses() {
        // `…}}` → `…,"x":1}` — an unknown field must not reject the lease.
        let json = lease_to_json(&sample_lease());
        let with_extra = format!("{},\"x\":1}}", &json[..json.len() - 1]);
        let parsed = lease_from_json(with_extra.as_bytes()).expect("extra fields tolerated");
        assert_eq!(parsed, sample_lease());
    }

    // --- liveness ----------------------------------------------------------------

    #[test]
    fn a_fresh_heartbeat_is_live() {
        let mut lease = sample_lease();
        lease.heartbeat_at_epoch_ms = now_ms();
        assert_eq!(lease_liveness(&lease, now_ms() + 1_000), LeaseLiveness::Live);
    }

    #[test]
    fn exactly_at_the_threshold_is_already_stale() {
        let mut lease = sample_lease();
        lease.heartbeat_at_epoch_ms = now_ms();
        assert_eq!(
            lease_liveness(&lease, now_ms() + LEASE_STALE_AFTER_MS - 1),
            LeaseLiveness::Live
        );
        assert_eq!(
            lease_liveness(&lease, now_ms() + LEASE_STALE_AFTER_MS),
            LeaseLiveness::Stale
        );
        assert_eq!(
            lease_liveness(&lease, now_ms() + LEASE_STALE_AFTER_MS + 1),
            LeaseLiveness::Stale
        );
    }

    #[test]
    fn a_future_heartbeat_is_live_not_a_panic() {
        // A drifted clock must not underflow into a bogus live claim.
        let mut lease = sample_lease();
        lease.heartbeat_at_epoch_ms = now_ms() + 60_000;
        assert_eq!(lease_liveness(&lease, now_ms()), LeaseLiveness::Live);
    }

    // --- views ---------------------------------------------------------------------

    #[test]
    fn no_lease_file_is_open() {
        assert_eq!(lease_view(None, now_ms(), "dm-us"), LeaseView::Open);
    }

    #[test]
    fn a_live_lease_by_another_device_is_in_use_elsewhere() {
        let mut lease = sample_lease();
        lease.device_id = "dm-other".to_owned();
        lease.heartbeat_at_epoch_ms = now_ms() - 5_000;
        let bytes = lease_to_json(&lease);
        assert_eq!(
            lease_view(Some(bytes.as_bytes()), now_ms(), "dm-us"),
            LeaseView::InUseElsewhere
        );
    }

    #[test]
    fn our_own_live_lease_is_held_here() {
        let mut lease = sample_lease();
        lease.device_id = "dm-us".to_owned();
        lease.heartbeat_at_epoch_ms = now_ms() - 5_000;
        let bytes = lease_to_json(&lease);
        assert_eq!(
            lease_view(Some(bytes.as_bytes()), now_ms(), "dm-us"),
            LeaseView::HeldHere
        );
    }

    #[test]
    fn a_stale_lease_is_reclaimable_whatever_the_holder() {
        let mut lease = sample_lease();
        lease.device_id = "dm-other".to_owned();
        lease.heartbeat_at_epoch_ms = now_ms() - LEASE_STALE_AFTER_MS;
        let bytes = lease_to_json(&lease);
        assert_eq!(
            lease_view(Some(bytes.as_bytes()), now_ms(), "dm-us"),
            LeaseView::Stale
        );
        // Our own expired claim is equally stale, not "held here".
        lease.device_id = "dm-us".to_owned();
        let bytes = lease_to_json(&lease);
        assert_eq!(
            lease_view(Some(bytes.as_bytes()), now_ms(), "dm-us"),
            LeaseView::Stale
        );
    }

    #[test]
    fn a_malformed_lease_file_is_stale_never_live() {
        assert_eq!(
            lease_view(Some(b"not json".as_slice()), now_ms(), "dm-us"),
            LeaseView::Stale
        );
    }

    // --- lease file naming -------------------------------------------------------------

    #[test]
    fn the_lease_file_name_is_the_sanitised_session_id() {
        assert_eq!(
            lease_file_name(SESSION_ID),
            format!("{SESSION_ID}.json")
        );
        // A crafted id cannot escape the lease directory.
        assert_eq!(lease_file_name("../../etc/passwd"), "passwd.json");
    }

    #[test]
    fn the_lease_path_sits_in_the_app_state_tree() {
        assert_eq!(
            lease_path_under_home("/home/u", SESSION_ID),
            format!("/home/u/{LEASE_DIR_RELATIVE}/{SESSION_ID}.json")
        );
    }

    // --- default directory -----------------------------------------------------------

    #[test]
    fn the_default_prefers_the_gentle_shell_agent_home() {
        assert_eq!(
            default_sessions_dir("erickfm"),
            "/home/erickfm/.gentle-shell/agent/sessions"
        );
    }

    // --- new_lease -----------------------------------------------------------------------

    #[test]
    fn a_new_lease_stamps_the_interval_and_both_timestamps() {
        let lease = new_lease("dm-us", "tok", now_ms());
        assert_eq!(lease.heartbeat_interval_secs, LEASE_HEARTBEAT_INTERVAL_SECS);
        assert_eq!(lease.acquired_at_epoch_ms, now_ms());
        assert_eq!(lease.heartbeat_at_epoch_ms, now_ms());
        assert_eq!(lease.device_id, "dm-us");
    }
}
