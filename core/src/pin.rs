//! Host-key pinning: fingerprint formatting, the OpenSSH-shaped pin store, and
//! the pin *decision* — the part that must not be sloppy.
//!
//! Unknown host → [`PinDecision::Unknown`] so the caller can surface the
//! fingerprint and ask the user. Known host with a different key →
//! [`PinDecision::Mismatch`], never auto-accept, never "tofu again".

use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use base64::Engine as _;
use russh::keys::{HashAlg, PublicKey, parse_public_key_base64};
use std::{fs, io, io::Write, os::unix::fs::OpenOptionsExt, path::Path};

use crate::error::Error;

/// Two throwaway ed25519 public keys used only by tests and examples.
pub const KEY1_BLOB: &str = "AAAAC3NzaC1lZDI1NTE5AAAAIN9ygka68uNlMQLDlEhBxlD+OrquQDyogT9y8gVHAk/Y";
pub const KEY2_BLOB: &str = "AAAAC3NzaC1lZDI1NTE5AAAAIKvi6wN6FmB6sY995w1o7rKBmunFgEJmQJ+L7Xm5XNUF";

/// One pinned host entry parsed from a known_hosts file.
///
/// Hashed (`|1|`) entries are not understood by this crate and are skipped, so
/// a file written by some other tool may under-report; entries this crate
/// writes are always plaintext host specs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostPin {
    /// Host specs from the line's first token, split on commas.
    pub specs: Vec<String>,
    /// Key type, e.g. `ssh-ed25519`.
    pub key_type: String,
    /// Base64 key blob.
    pub blob: String,
}

/// The OpenSSH hostspec for a host/port: bare host for port 22,
/// `[host]:port` otherwise.
pub fn spec_for(host: &str, port: u16) -> String {
    if port == 22 {
        host.to_owned()
    } else {
        format!("[{host}]:{port}")
    }
}

/// Format a SHA-256 digest as the fingerprint string the OpenSSH tools print:
/// `SHA256:` followed by unpadded standard base64.
pub fn format_fingerprint(digest: &[u8]) -> String {
    let encoded = BASE64_STANDARD.encode(digest);
    format!("SHA256:{}", encoded.trim_end_matches('='))
}

/// The `SHA256:...` fingerprint of a public key, in the OpenSSH form.
pub fn fingerprint_of(key: &PublicKey) -> String {
    key.fingerprint(HashAlg::Sha256).to_string()
}

/// Parse known_hosts text into pin entries. Comments, blank lines, hashed
/// entries, marker lines (`@cert-authority` and friends), and lines with
/// unparseable shapes are skipped, not errors.
pub fn parse_entries(text: &str) -> Vec<HostPin> {
    text.lines()
        .filter_map(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with('@') {
                return None;
            }
            let mut parts = line.split_whitespace();
            let first = parts.next()?;
            // Hashed (`|1|...`) hostnames are opaque to this crate.
            if first.starts_with('|') {
                return None;
            }
            let specs: Vec<String> = first.split(',').map(str::to_owned).collect();
            let key_type = parts.next()?.to_owned();
            let blob = parts.next()?.to_owned();
            // A blob that cannot decode is junk: skip the whole line.
            parse_public_key_base64(&blob).ok()?;
            Some(HostPin { specs, key_type, blob })
        })
        .collect()
}

/// The pin verdict for a presented server key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PinDecision {
    /// The presented key matches the pinned one. Connect.
    Match,
    /// No pin exists for this hostspec. Carry the presented fingerprint so the
    /// UI can ask for a decision.
    Unknown { presented: String },
    /// A pin exists and does not match. Name both fingerprints.
    Mismatch { pinned: String, presented: String },
}

/// Pure decision: no I/O, no side effects, fully unit-testable.
pub fn decide(entries: &[HostPin], host: &str, port: u16, presented: &PublicKey) -> PinDecision {
    let spec = spec_for(host, port);
    let presented = fingerprint_of(presented);
    let mut first_pinned: Option<String> = None;
    for entry in entries {
        if !entry.specs.iter().any(|candidate| candidate == &spec) {
            continue;
        }
        match parse_public_key_base64(&entry.blob) {
            Ok(key) if fingerprint_of(&key) == presented => return PinDecision::Match,
            Ok(key) => {
                if first_pinned.is_none() {
                    first_pinned = Some(fingerprint_of(&key));
                }
            }
            // An entry we cannot parse proves nothing; keep looking.
            Err(_) => continue,
        }
    }
    match first_pinned {
        Some(pinned) => PinDecision::Mismatch { pinned, presented },
        None => PinDecision::Unknown { presented },
    }
}

/// Map a refused verification (anything but [`PinDecision::Match`]) to the
/// typed connect error the UI matches on. `None` for a match.
pub fn refusal_error(host: &str, port: u16, decision: &PinDecision) -> Option<Error> {
    match decision {
        PinDecision::Match => None,
        PinDecision::Unknown { presented } => Some(Error::HostKeyUnknown {
            host: host.to_owned(),
            port,
            fingerprint: presented.clone(),
        }),
        PinDecision::Mismatch { pinned, presented } => Some(Error::HostKeyMismatch {
            host: host.to_owned(),
            port,
            pinned: pinned.clone(),
            presented: presented.clone(),
        }),
    }
}

/// Persist the presented key for `host:port` to `path` in OpenSSH known_hosts
/// format — the consent step *before* the next connect attempt succeeds.
///
/// Replaces any existing entry for the same hostspec (accepting a new key is an
/// explicit user decision that retires the old pin) and does not duplicate an
/// identical entry on a second call.
pub fn accept_host_key(
    path: &Path,
    host: &str,
    port: u16,
    presented: &PublicKey,
) -> crate::error::Result<()> {
    let spec = spec_for(host, port);
    let openssh = presented
        .to_openssh()
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
    let line = format!("{spec} {openssh}\n");
    let kept = match fs::read_to_string(path) {
        Ok(text) => keep_other_specs(&text, &spec),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Vec::new(),
        Err(error) => return Err(error.into()),
    };
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut content = kept.join("\n");
    if !kept.is_empty() {
        content.push('\n');
    }
    content.push_str(&line);
    let mut file = fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
    file.write_all(content.as_bytes())?;
    Ok(())
}

/// Lines of `text` whose first token does not name `spec`, kept verbatim.
fn keep_other_specs(text: &str, spec: &str) -> Vec<String> {
    text.lines()
        .filter(|line| {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('@') {
                return true;
            }
            match trimmed.split_whitespace().next() {
                Some(first) => !first.split(',').any(|candidate| candidate == spec),
                None => true,
            }
        })
        .map(str::to_owned)
        .collect()
}

/// Remove every entry naming `host:port` from the pin store — the Settings
/// screen's per-host `Forget`. Only lines whose first token names the spec
/// (bare host for port 22, `[host]:port` otherwise) are removed; comments,
/// markers and lines written by other tools survive untouched, and a file
/// with no matching entry is not rewritten at all. Returns `true` when at
/// least one entry was removed, so the next connection challenges the
/// fingerprint again ([`decide`] then reports [`PinDecision::Unknown`]).
pub fn forget_host(path: &Path, host: &str, port: u16) -> crate::error::Result<bool> {
    let spec = spec_for(host, port);
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error.into()),
    };
    let names_spec = |line: &str| -> bool {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('@') {
            return false;
        }
        match trimmed.split_whitespace().next() {
            Some(first) => first.split(',').any(|candidate| candidate == spec),
            None => false,
        }
    };
    if !text.lines().any(names_spec) {
        return Ok(false);
    }
    let kept = keep_other_specs(&text, &spec);
    let mut content = kept.join("\n");
    if !kept.is_empty() {
        content.push('\n');
    }
    let mut file = fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
    file.write_all(content.as_bytes())?;
    Ok(true)
}

/// Read the whole pin file, or an empty set when it does not exist yet.
pub fn load_entries(path: &Path) -> crate::error::Result<Vec<HostPin>> {
    match fs::read_to_string(path) {
        Ok(text) => Ok(parse_entries(&text)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(error) => Err(error.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn key(blob: &str) -> PublicKey {
        match parse_public_key_base64(blob) {
            Ok(key) => key,
            Err(_) => panic!("test blob must parse"),
        }
    }

    fn entries_with(spec: &str, blob: &str) -> Vec<HostPin> {
        parse_entries(&format!("# a comment\n{spec} ssh-ed25519 {blob}\n\n"))
    }

    #[test]
    fn fingerprint_format_is_openssh_sha256_form() {
        // SHA-256 digest of "abc", a stable vector.
        let digest = [
            0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae,
            0x22, 0x23, 0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61,
            0xf2, 0x00, 0x15, 0xad,
        ];
        assert_eq!(
            format_fingerprint(&digest),
            "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0"
        );
    }

    #[test]
    fn fingerprint_format_is_unpadded_base64() {
        // Any digest must lose its '=' padding: 32 bytes → 44 chars with one
        // padded '='; the fingerprint form has exactly 43.
        let digest = [0u8; 32];
        let formatted = format_fingerprint(&digest);
        assert!(formatted.starts_with("SHA256:"), "{formatted}");
        assert_eq!(formatted.len(), "SHA256:".len() + 43, "{formatted}");
        assert!(!formatted.contains('='), "{formatted}");
    }

    #[test]
    fn fingerprint_of_key_is_deterministic() {
        assert_eq!(fingerprint_of(&key(KEY1_BLOB)), fingerprint_of(&key(KEY1_BLOB)));
        assert_ne!(fingerprint_of(&key(KEY1_BLOB)), fingerprint_of(&key(KEY2_BLOB)));
    }

    #[test]
    fn spec_uses_brackets_only_for_non_default_ports() {
        assert_eq!(spec_for("host.example", 22), "host.example");
        assert_eq!(spec_for("127.0.0.1", 2222), "[127.0.0.1]:2222");
    }

    #[test]
    fn parse_entries_skips_comments_and_junk() {
        let text = "\
# comment
\n
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN9ygka68uNlMQLDlEhBxlD+OrquQDyogT9y8gVHAk/Y
|1|hashed|entry ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKvi6wN6FmB6sY995w1o7rKBmunFgEJmQJ+L7Xm5XNUF
host.example ssh-ed25519 not-base64!!
short-line";
        let entries = parse_entries(text);
        assert_eq!(entries.len(), 1, "{entries:#?}");
        assert_eq!(entries[0].specs, vec!["[127.0.0.1]:2222".to_owned()]);
        assert_eq!(entries[0].blob, KEY1_BLOB);
    }

    #[test]
    fn unknown_host_names_the_presented_fingerprint() {
        // Same key pinned for a different hostspec (host:port) is still an
        // unknown host: pins are per endpoint, not per key.
        let decision = decide(&entries_with("[h]:2222", KEY1_BLOB), "h", 2424, &key(KEY1_BLOB));
        assert_eq!(
            decision,
            PinDecision::Unknown { presented: fingerprint_of(&key(KEY1_BLOB)) }
        );
    }

    #[test]
    fn match_accepts_the_pinned_key() {
        let decision = decide(&entries_with("host.example", KEY1_BLOB), "host.example", 22, &key(KEY1_BLOB));
        assert_eq!(decision, PinDecision::Match);
    }

    #[test]
    fn on_mismatch_both_fingerprints_are_named_and_never_auto_accepted() {
        let decision = decide(&entries_with("host.example", KEY1_BLOB), "host.example", 22, &key(KEY2_BLOB));
        let pinned = fingerprint_of(&key(KEY1_BLOB));
        let presented = fingerprint_of(&key(KEY2_BLOB));
        assert_ne!(pinned, presented, "test keys must differ");
        assert_eq!(decision, PinDecision::Mismatch { pinned, presented });
    }

    #[test]
    fn refusal_error_maps_decisions_to_typed_errors() {
        let pinned_fp = fingerprint_of(&key(KEY2_BLOB));
        let presented_fp = fingerprint_of(&key(KEY1_BLOB));
        let unknown = refusal_error(
            "host.example",
            22,
            &PinDecision::Unknown { presented: presented_fp.clone() },
        );
        match unknown {
            Some(Error::HostKeyUnknown { host, port, fingerprint }) => {
                assert_eq!(
                    (host.as_str(), port, fingerprint.as_str()),
                    ("host.example", 22, presented_fp.as_str())
                );
            }
            other => panic!("expected HostKeyUnknown, got {other:?}"),
        }
        let mismatch = refusal_error(
            "host.example",
            2424,
            &PinDecision::Mismatch { pinned: pinned_fp.clone(), presented: presented_fp.clone() },
        );
        match mismatch {
            Some(Error::HostKeyMismatch { host, port, pinned, presented }) => {
                assert_eq!(
                    (host.as_str(), port, pinned.as_str(), presented.as_str()),
                    ("host.example", 2424, pinned_fp.as_str(), presented_fp.as_str())
                );
            }
            other => panic!("expected HostKeyMismatch, got {other:?}"),
        }
        assert!(refusal_error("host.example", 22, &PinDecision::Match).is_none());
    }

    #[test]
    fn accept_host_key_persists_and_thereafter_matches() -> Result<(), Box<dyn std::error::Error>> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("known_hosts");
        accept_host_key(&path, "127.0.0.1", 2424, &key(KEY1_BLOB))?;
        let text = fs::read_to_string(&path)?;
        assert!(text.contains("[127.0.0.1]:2424 ssh-ed25519"), "{text}");
        assert_eq!(path.metadata()?.permissions().mode() & 0o777, 0o600, "{text}");
        let decision = decide(&load_entries(&path)?, "127.0.0.1", 2424, &key(KEY1_BLOB));
        assert_eq!(decision, PinDecision::Match);
        Ok(())
    }

    #[test]
    fn accept_host_key_is_idempotent() -> Result<(), Box<dyn std::error::Error>> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("known_hosts");
        accept_host_key(&path, "127.0.0.1", 2424, &key(KEY1_BLOB))?;
        accept_host_key(&path, "127.0.0.1", 2424, &key(KEY1_BLOB))?;
        let text = fs::read_to_string(&path)?;
        assert_eq!(text.lines().count(), 1, "{text}");
        Ok(())
    }

    #[test]
    fn forget_host_removes_exactly_that_spec_and_the_next_connect_challenges(
    ) -> Result<(), Box<dyn std::error::Error>> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("known_hosts");
        fs::write(
            &path,
            format!(
                "[127.0.0.1]:2424 ssh-ed25519 {}\nother.example ssh-ed25519 {}\n# comment\ngarbage-not-a-pin\n",
                KEY1_BLOB, KEY2_BLOB
            ),
        )?;
        assert!(forget_host(&path, "127.0.0.1", 2424)?);
        // Everything except the forgotten spec survives, byte for byte.
        let text = fs::read_to_string(&path)?;
        assert!(text.contains("other.example"), "{text}");
        assert!(text.contains("# comment"), "{text}");
        assert!(text.contains("garbage-not-a-pin"), "{text}");
        assert!(!text.contains("[127.0.0.1]:2424"), "{text}");
        // The forgotten host challenges again on the next connect; the
        // untouched host still matches its pin.
        let entries = load_entries(&path)?;
        assert_eq!(
            decide(&entries, "127.0.0.1", 2424, &key(KEY1_BLOB)),
            PinDecision::Unknown { presented: fingerprint_of(&key(KEY1_BLOB)) }
        );
        assert_eq!(decide(&entries, "other.example", 22, &key(KEY2_BLOB)), PinDecision::Match);
        // Forgetting again finds nothing to remove.
        assert!(!forget_host(&path, "127.0.0.1", 2424)?);
        Ok(())
    }

    #[test]
    fn forget_host_leaves_a_malformed_file_untouched() -> Result<(), Box<dyn std::error::Error>> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("known_hosts");
        let text = "short-line\nhost.example ssh-ed25519 not-base64!!\n\n";
        fs::write(&path, text)?;
        // Nothing in the file names the requested spec: nothing removed.
        assert!(!forget_host(&path, "other.example", 22)?);
        assert_eq!(fs::read_to_string(&path)?, text, "the file must not be rewritten");
        // A missing file is not an error either.
        assert!(!forget_host(&dir.path().join("absent"), "h", 22)?);
        Ok(())
    }

    #[test]
    fn accepting_a_new_key_replaces_the_stale_pin() -> Result<(), Box<dyn std::error::Error>> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("known_hosts");
        accept_host_key(&path, "127.0.0.1", 2424, &key(KEY1_BLOB))?;
        accept_host_key(&path, "127.0.0.1", 2424, &key(KEY2_BLOB))?;
        let entries = load_entries(&path)?;
        let decision = decide(&entries, "127.0.0.1", 2424, &key(KEY2_BLOB));
        assert_eq!(decision, PinDecision::Match);
        // The old pin must be gone, not merely shadowed: presenting the old
        // key is now a mismatch against the retired-pin replacement.
        assert_eq!(
            decide(&entries, "127.0.0.1", 2424, &key(KEY1_BLOB)),
            PinDecision::Mismatch {
                pinned: fingerprint_of(&key(KEY2_BLOB)),
                presented: fingerprint_of(&key(KEY1_BLOB)),
            }
        );
        Ok(())
    }
}
