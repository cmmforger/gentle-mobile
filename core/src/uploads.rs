//! The upload lane: images the composer attaches travel to the host over
//! the SFTP subsystem, never through the herdr daemon.
//!
//! Measured, not assumed: the daemon's API (protocol 22) advertises no
//! method that can receive a file — `herdr api schema --json` lists 161
//! method names and none of them uploads, writes or copies a file — so the
//! daemon cannot take the bytes. sshd itself serves the SFTP subsystem, so
//! the file goes straight to the host filesystem over the same pinned SSH
//! session the app already trusts (same host-key pins, same device key),
//! into a predictable per-user state directory, and the prompt hands the
//! absolute path to the agent — agents work on files.
//!
//! The contract this module owns and unit-tests:
//!
//! * **Where**: `~/.local/state/gentle-mobile/uploads/<utc-timestamp>-<name>`
//!   (relative to the SFTP session's home, resolved to an absolute path with
//!   the server's own `canonicalize(".")` before the prompt references it).
//! * **What name**: sanitised (safe characters only, traversal collapsed,
//!   stem capped, extension kept) and prefixed with a compact UTC timestamp
//!   so repeated uploads of the same name never overwrite each other.
//! * **How big**: [`MAX_UPLOAD_BYTES`] per file, refused locally before any
//!   byte moves ([`UploadError::TooLarge`]).
//! * **How the agent sees it**: [`compose_prompt`] appends one
//!   `[attached image: <absolute path>]` line per uploaded file — a failed
//!   upload never produces a path line, because the queue that calls this
//!   only ever passes suffixes handed back by a confirmed upload.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Per-file upload ceiling. A 20 MiB image is far above any screenshot or
/// camera shot a user sends, and well under what a tailnet link streams
/// comfortably; refusing locally keeps the failure instant and honest.
pub const MAX_UPLOAD_BYTES: u64 = 20 * 1024 * 1024;

/// The upload directory, relative to the remote user's home (the SFTP
/// session's default working directory). Created with `mkdir -p` semantics
/// before any file lands.
pub const UPLOAD_DIR_RELATIVE: &str = ".local/state/gentle-mobile/uploads";

/// Largest permitted stem before truncation (extension always survives).
const MAX_STEM_CHARS: usize = 48;

/// Largest permitted extension, so a crafted "extension" cannot smuggle a
/// second oversized stem past the cap.
const MAX_EXT_CHARS: usize = 12;

/// An upload-contract failure the UI can name to the user.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UploadError {
    /// The file is over [`MAX_UPLOAD_BYTES`]. Refused before the transfer.
    TooLarge { size_bytes: u64, max_bytes: u64 },
    /// The name has no usable form once sanitised (empty, blank, or made
    /// only of path separators).
    EmptyName,
}

impl std::fmt::Display for UploadError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            UploadError::TooLarge { size_bytes, max_bytes } => write!(
                formatter,
                "the file is too large: {size_bytes} bytes, the host accepts up to {max_bytes}"
            ),
            UploadError::EmptyName => write!(formatter, "the file has no usable name"),
        }
    }
}

/// Everything a confirmed upload hands back: the absolute remote path and
/// the one-line suffix the prompt carries.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UploadOutcome {
    pub remote_path: String,
    pub prompt_suffix: String,
}

/// The prompt block for one uploaded image — exactly what [`compose_prompt`]
/// appends per file.
pub fn prompt_suffix_for(remote_path: &str) -> String {
    format!("[attached image: {remote_path}]")
}

// --- Pure contract functions (unit-tested below) ---------------------------

/// The last path segment of `raw`, with every character outside
/// `A-Z a-z 0-9 . _ -` replaced by `_`, leading dots turned into underscores
/// (hidden files and `..` both stop being special), the stem capped at
/// [`MAX_STEM_CHARS`] and the extension kept (capped at [`MAX_EXT_CHARS`]).
/// `None` when nothing usable remains — an empty, blank, or separator-only
/// name.
pub fn sanitise_file_name(raw: &str) -> Option<String> {
    // Only the final segment survives: a path or traversal attempt cannot
    // smuggle directories into the stored name.
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("");
    let trimmed = base.trim();
    if trimmed.is_empty() || base.chars().all(|c| c == '/' || c == '\\') {
        return None;
    }
    let safe: String = trimmed
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-' {
            c
        } else {
            '_'
        })
        .collect();
    // Leading dots become underscores so hidden files and `..` lose their
    // shell meaning on the host.
    let leading_dots = safe.len() - safe.trim_start_matches('.').len();
    let safe = format!("{}{}", "_".repeat(leading_dots), &safe[leading_dots..]);
    if safe.is_empty() {
        return None;
    }
    // A name made only of dots ("...", "..") becomes all underscores above
    // and stays a valid, harmless name.
    let (stem, ext) = match safe.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() && !ext.is_empty() => (stem, Some(ext)),
        _ => (safe.as_str(), None),
    };
    let stem: String = stem.chars().take(MAX_STEM_CHARS).collect();
    let ext = ext.map(|ext| ext.chars().take(MAX_EXT_CHARS).collect::<String>());
    Some(match ext {
        Some(ext) => format!("{stem}.{ext}"),
        None => stem,
    })
}

/// Compact UTC timestamp used as the stored-name prefix:
/// `YYYYMMDD-HHMMSS` in UTC, derived from `now`.
pub fn upload_timestamp(now: SystemTime) -> String {
    // Civil-time conversion without a date crate: days since the Unix epoch
    // → (year, month, day) by the standard civil-from-days algorithm
    // (Howard Hinnant's), then the wall clock. UTC, never local time — the
    // name must be identical no matter where the phone is set.
    let secs = now
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_secs();
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}{month:02}{day:02}-{hh:02}{mm:02}{ss:02}",
        hh = rem / 3_600,
        mm = (rem % 3_600) / 60,
        ss = rem % 60,
    )
}

/// Days since 1970-01-01 → (year, month, day); Hinnant's `civil_from_days`.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64; // [0, 146096]
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32; // [1, 12]
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// The stored name: `<timestamp>-<sanitised>` — the timestamp prefix means
/// two uploads of the same file never overwrite each other.
pub fn stored_name(raw: &str, now: SystemTime) -> Result<String, UploadError> {
    let sanitised = sanitise_file_name(raw).ok_or(UploadError::EmptyName)?;
    Ok(format!("{}-{sanitised}", upload_timestamp(now)))
}

/// Refuse anything over [`MAX_UPLOAD_BYTES`] before a byte moves.
pub fn check_size(size_bytes: u64) -> Result<(), UploadError> {
    if size_bytes > MAX_UPLOAD_BYTES {
        Err(UploadError::TooLarge {
            size_bytes,
            max_bytes: MAX_UPLOAD_BYTES,
        })
    } else {
        Ok(())
    }
}

/// The final prompt text: `text` alone with no uploads, or the trimmed text
/// followed by one `[attached image: <path>]` line per upload. The caller
/// only ever passes suffixes handed back by confirmed uploads, so a failed
/// upload can never reach the agent as a path.
pub fn compose_prompt(text: &str, image_suffixes: &[String]) -> String {
    if image_suffixes.is_empty() {
        return text.to_owned();
    }
    let trimmed = text.trim_end();
    if trimmed.is_empty() {
        // No text, only uploads: the prompt is the image lines alone — never
        // a leading blank line.
        return image_suffixes.join("\n");
    }
    let mut out = String::from(trimmed);
    out.push_str("\n\n");
    for (index, suffix) in image_suffixes.iter().enumerate() {
        if index > 0 {
            out.push('\n');
        }
        out.push_str(suffix);
    }
    out
}

// --- The transfer lane ------------------------------------------------------
// Below the pure contract: the SFTP transfer itself. It runs on its own
// one-shot pinned SSH session (`requests::connect_pinned_ssh`), opens an SFTP
// subsystem channel, creates the upload directory tree, streams the bytes in
// chunks with per-chunk progress, and resolves the absolute remote path with
// the server's own `canonicalize` — the path the prompt hands to the agent.

use crate::{profile::HostProfile, requests::{HubError, RequestError, connect_pinned_ssh}};

/// One chunk per SFTP write, and one progress report per chunk.
const UPLOAD_CHUNK_BYTES: usize = 256 * 1024;

/// The whole upload — SSH handshake, subsystem, transfer — is bounded, so a
/// wedged host cannot park the composer's sending state forever.
pub const UPLOAD_TIMEOUT: Duration = Duration::from_secs(180);

/// Per-write deadline: one chunk over a working link is far quicker.
const CHUNK_TIMEOUT: Duration = Duration::from_secs(30);

/// Progress callback: bytes sent so far and the total, called once per
/// written chunk.
pub type ProgressFn<'a> = &'a (dyn Fn(u64, u64) + Send + Sync);

/// Upload `data` as `stored_name` into [`UPLOAD_DIR_RELATIVE`] under the
/// remote user's home, over the SFTP subsystem of a fresh pinned SSH
/// session. Returns the outcome the prompt needs: the absolute remote path
/// (resolved with the server's own `canonicalize(".")`, never guessed
/// client-side) and the prompt suffix for it.
pub async fn upload_over_sftp(
    profile: &HostProfile,
    stored_name: &str,
    data: &[u8],
    progress: ProgressFn<'_>,
) -> Result<UploadOutcome, HubError> {
    // The name reaches the host inside a path; only an already-sanitised
    // name is allowed through (idempotent: a stored name re-sanitises to
    // itself).
    if sanitise_file_name(stored_name) != Some(stored_name.to_owned()) {
        return Err(HubError::Request(RequestError::Transport {
            detail: "the upload name was not sanitised".to_owned(),
        }));
    }
    tokio::time::timeout(UPLOAD_TIMEOUT, async {
        let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let ssh = connect_pinned_ssh(profile, &stop).await?;
        let upload = async {
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
            let sftp = russh_sftp::client::SftpSession::new(channel.into_stream())
                .await
                .map_err(|error| HubError::Request(RequestError::Transport {
                    detail: format!("the SFTP session could not start: {error}"),
                }))?;
            // The absolute home comes from the server, never from a
            // client-side guess about the user's environment.
            let home = sftp
                .canonicalize(".")
                .await
                .map_err(|error| HubError::Request(RequestError::Transport {
                    detail: format!("the host would not resolve the home directory: {error}"),
                }))?;
            let home = home.trim_end_matches('/').to_owned();
            // mkdir -p for the upload tree: create_dir on an existing
            // directory fails harmlessly and is ignored.
            let mut partial = String::new();
            for component in UPLOAD_DIR_RELATIVE.split('/') {
                if !partial.is_empty() {
                    partial.push('/');
                }
                partial.push_str(component);
                let _ = sftp.create_dir(&partial).await;
            }
            let relative = format!("{UPLOAD_DIR_RELATIVE}/{stored_name}");
            let mut file = sftp
                .create(&relative)
                .await
                .map_err(|error| HubError::Request(RequestError::Transport {
                    detail: format!("the host refused the upload destination: {error}"),
                }))?;
            use tokio::io::AsyncWriteExt as _;
            let total = data.len() as u64;
            let mut sent: u64 = 0;
            for chunk in data.chunks(UPLOAD_CHUNK_BYTES) {
                tokio::time::timeout(CHUNK_TIMEOUT, file.write_all(chunk))
                    .await
                    .map_err(|_| HubError::Request(RequestError::Timeout {
                        detail: "an upload chunk never completed in time".to_owned(),
                    }))?
                    .map_err(|error| HubError::Request(RequestError::Transport {
                        detail: format!("an upload chunk failed: {error}"),
                    }))?;
                sent += chunk.len() as u64;
                progress(sent, total);
            }
            tokio::time::timeout(CHUNK_TIMEOUT, file.sync_all())
                .await
                .map_err(|_| HubError::Request(RequestError::Timeout {
                    detail: "the upload flush never completed in time".to_owned(),
                }))?
                .map_err(|error| HubError::Request(RequestError::Transport {
                    detail: format!("the upload could not be flushed: {error}"),
                }))?;
            file.close().await.map_err(|error| {
                HubError::Request(RequestError::Transport {
                    detail: format!("the uploaded file could not be closed: {error}"),
                })
            })?;
            let remote_path = format!("{home}/{relative}");
            Ok(UploadOutcome {
                prompt_suffix: prompt_suffix_for(&remote_path),
                remote_path,
            })
        }
        .await;
        // The session is one-shot: tear it down whatever the outcome.
        let _ = ssh
            .disconnect(russh::Disconnect::ByApplication, "", "en")
            .await;
        upload
    })
    .await
    .map_err(|_| HubError::Request(RequestError::Timeout {
        detail: "the upload never completed in time".to_owned(),
    }))?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// 2026-02-12 10:15:00 UTC, as a fixed instant for timestamp tests.
    fn fixed_now() -> SystemTime {
        UNIX_EPOCH + Duration::from_secs(1_770_891_300u64)
    }

    // --- sanitise_file_name -------------------------------------------------

    #[test]
    fn sanitise_keeps_safe_characters_verbatim() {
        assert_eq!(sanitise_file_name("photo-01.PNG"), Some("photo-01.PNG".to_owned()));
        assert_eq!(sanitise_file_name("trace_409.png"), Some("trace_409.png".to_owned()));
    }

    #[test]
    fn sanitise_takes_the_last_path_segment_first() {
        // A path (or a traversal attempt) collapses to its final segment.
        assert_eq!(sanitise_file_name("../../etc/passwd"), Some("passwd".to_owned()));
        assert_eq!(sanitise_file_name("/sdcard/Download/shot.png"), Some("shot.png".to_owned()));
        assert_eq!(sanitise_file_name("a\\b.png"), Some("b.png".to_owned()));
    }

    #[test]
    fn sanitise_replaces_unsafe_characters_with_underscores() {
        assert_eq!(sanitise_file_name("my photo (1).png"), Some("my_photo__1_.png".to_owned()));
        assert_eq!(sanitise_file_name("résumé.png"), Some("r_sum_.png".to_owned()));
        // A name of underscores alone is still a distinct, safe name.
        assert_eq!(sanitise_file_name("###"), Some("___".to_owned()));
    }

    #[test]
    fn sanitise_turns_leading_dots_into_underscores() {
        // Hidden files and bare dot-names stop being special.
        assert_eq!(sanitise_file_name(".env"), Some("_env".to_owned()));
        assert_eq!(sanitise_file_name(".."), Some("__".to_owned()));
    }

    #[test]
    fn sanitise_caps_the_stem_but_keeps_the_extension() {
        let stem = "a".repeat(80);
        let sanitised = sanitise_file_name(&format!("{stem}.png")).expect("usable name");
        let (stem_part, ext_part) = sanitised
            .rsplit_once('.')
            .expect("extension survives");
        assert!(stem_part.chars().count() <= MAX_STEM_CHARS, "{stem_part}");
        assert_eq!(ext_part, "png");
        assert!(sanitised.starts_with('a'));
    }

    #[test]
    fn sanitise_caps_a_crafted_long_extension_too() {
        // A 30-character "extension" is truncated, never smuggled whole.
        let sanitised = sanitise_file_name(&format!("shot.{}", "x".repeat(30)))
            .expect("usable name");
        let (stem_part, ext_part) = sanitised.rsplit_once('.').expect("a dot survives");
        assert_eq!(stem_part, "shot");
        assert!(ext_part.chars().count() <= MAX_EXT_CHARS, "{ext_part}");
    }

    #[test]
    fn sanitise_rejects_names_with_nothing_usable() {
        assert_eq!(sanitise_file_name(""), None);
        assert_eq!(sanitise_file_name("   "), None);
        assert_eq!(sanitise_file_name("/"), None);
        assert_eq!(sanitise_file_name("//"), None);
    }

    // --- upload_timestamp / stored_name --------------------------------------

    #[test]
    fn upload_timestamp_is_compact_utc() {
        assert_eq!(upload_timestamp(fixed_now()), "20260212-101500");
    }

    #[test]
    fn stored_name_prefixes_the_timestamp_and_keeps_the_name() {
        let name = stored_name("trace-409.png", fixed_now()).expect("usable");
        assert_eq!(name, "20260212-101500-trace-409.png");
    }

    #[test]
    fn stored_name_refuses_an_empty_name() {
        assert_eq!(stored_name("   ", fixed_now()), Err(UploadError::EmptyName));
    }

    // --- check_size -----------------------------------------------------------

    #[test]
    fn check_size_accepts_the_limit_and_refuses_above_it() {
        assert_eq!(check_size(0), Ok(()));
        assert_eq!(check_size(MAX_UPLOAD_BYTES), Ok(()));
        assert_eq!(
            check_size(MAX_UPLOAD_BYTES + 1),
            Err(UploadError::TooLarge {
                size_bytes: MAX_UPLOAD_BYTES + 1,
                max_bytes: MAX_UPLOAD_BYTES,
            })
        );
    }

    // --- compose_prompt -------------------------------------------------------

    #[test]
    fn compose_prompt_without_images_is_the_text_itself() {
        assert_eq!(compose_prompt("hello", &[]), "hello");
        assert_eq!(compose_prompt("", &[]), "");
    }

    #[test]
    fn compose_prompt_appends_one_image_line_per_upload() {
        let suffixes = vec![
            prompt_suffix_for("/home/u/.local/state/gentle-mobile/uploads/20260212-101500-a.png"),
            prompt_suffix_for("/home/u/.local/state/gentle-mobile/uploads/20260212-101501-b.png"),
        ];
        assert_eq!(
            compose_prompt("Compare the 409 with yesterday's retry.", &suffixes),
            "Compare the 409 with yesterday's retry.\n\
             \n\
             [attached image: /home/u/.local/state/gentle-mobile/uploads/20260212-101500-a.png]\n\
             [attached image: /home/u/.local/state/gentle-mobile/uploads/20260212-101501-b.png]"
        );
    }

    #[test]
    fn compose_prompt_with_images_and_blank_text_still_carries_the_paths() {
        let suffixes = vec![prompt_suffix_for("/abs/a.png")];
        assert_eq!(compose_prompt("", &suffixes), "[attached image: /abs/a.png]");
        // Trailing whitespace on the text is trimmed, never doubled.
        assert_eq!(compose_prompt("look  \n", &suffixes), "look\n\n[attached image: /abs/a.png]");
    }

    #[test]
    fn prompt_suffix_names_the_path_exactly() {
        assert_eq!(
            prompt_suffix_for("/abs/a.png"),
            "[attached image: /abs/a.png]"
        );
    }
}
