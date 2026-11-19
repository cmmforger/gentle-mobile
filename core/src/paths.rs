//! Path validation over the SFTP lane (S26-3): before the app ever asks the
//! daemon to create a workspace, the typed path is checked on the host —
//! exists, is a directory, is readable (permission), and whether it is a git
//! repository (a `.git` marker; a file in linked-worktree checkouts).
//!
//! Why the check is ours: measured live, `workspace.create` with a `cwd`
//! that does not exist **does not fail** — the daemon silently falls back to
//! `$HOME` and opens a workspace in the wrong place. The validation here is
//! the guard, and every rejection maps to a plain sentence in the UI (path
//! missing, not a directory, no permission).
//!
//! The wire side rides the same one-shot pinned SSH + SFTP lane the lease
//! work uses ([`connect_sftp`]): no daemon call, no new transport, and the
//! session is torn down whatever the outcome.

use crate::profile::HostProfile;

/// What the check concluded, in the words the UI maps to sentences.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DirVerdict {
    /// The path exists, is a directory, and could be opened.
    Ok,
    /// No such path on the host.
    Missing,
    /// The path exists but is a file (or another non-directory).
    NotADirectory,
    /// The directory exists but could not be opened (permission denied).
    NoPermission,
}

/// The full outcome of one host-directory check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirCheck {
    pub verdict: DirVerdict,
    /// `true` only for a readable directory carrying a `.git` marker — a
    /// directory entry in a normal checkout, a file in a linked worktree.
    pub is_git_repo: bool,
}

/// The pure decision from the SFTP facts. Tested directly; the SFTP call
/// only gathers these facts.
pub fn dir_check(exists: bool, is_dir: bool, readable: bool, git_marker_present: bool) -> DirCheck {
    if !exists {
        return DirCheck { verdict: DirVerdict::Missing, is_git_repo: false };
    }
    if !is_dir {
        return DirCheck { verdict: DirVerdict::NotADirectory, is_git_repo: false };
    }
    if !readable {
        return DirCheck { verdict: DirVerdict::NoPermission, is_git_repo: false };
    }
    DirCheck { verdict: DirVerdict::Ok, is_git_repo: git_marker_present }
}

/// The `.git` marker inside a directory: a directory in a normal checkout,
/// a file in a linked worktree.
const GIT_MARKER: &str = "/.git";

/// Check one absolute path on the host over the SFTP lane: one one-shot
/// pinned session, four facts, then the pure decision. Transport failures
/// propagate; every host-side fact gathers honestly (a fact we cannot read
/// reads as "not this thing", never as a guess).
pub async fn check_host_dir_over_sftp(
    profile: &HostProfile,
    path: &str,
) -> Result<DirCheck, crate::requests::HubError> {
    use crate::requests::{HubError, RequestError};
    let (ssh, sftp) = crate::sessions::connect_sftp(profile).await?;
    let outcome = async {
        let exists = sftp
            .try_exists(path)
            .await
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the path could not be checked on the host: {error}"),
            }))?
        ;
        if !exists {
            return Ok(dir_check(false, false, false, false));
        }
        let metadata = sftp
            .metadata(path)
            .await
            .map_err(|error| HubError::Request(RequestError::Transport {
                detail: format!("the path could not be inspected on the host: {error}"),
            }))?
        ;
        let is_dir = metadata.is_dir();
        // Permission: opening (reading) the directory is what the daemon
        // will do when it spawns a workspace there — stat alone can succeed
        // on a directory whose bits deny the owner everything.
        let readable = if is_dir {
            sftp.read_dir(path)
                .await
                .is_ok()
        } else {
            false
        };
        let git_marker_present = sftp
            .try_exists(format!("{}{}", path.trim_end_matches('/'), GIT_MARKER))
            .await
            .unwrap_or(false);
        Ok(dir_check(true, is_dir, readable, git_marker_present))
    }
    .await;
    let _ = ssh
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_missing_path_is_missing() {
        let check = dir_check(false, false, false, false);
        assert_eq!(check.verdict, DirVerdict::Missing);
        assert!(!check.is_git_repo);
    }

    #[test]
    fn a_file_is_not_a_directory() {
        let check = dir_check(true, false, false, false);
        assert_eq!(check.verdict, DirVerdict::NotADirectory);
        assert!(!check.is_git_repo);
    }

    #[test]
    fn an_unreadable_directory_has_no_permission() {
        // chmod 000 on the host: stat succeeds (the parent allows it),
        // opening the directory does not.
        let check = dir_check(true, true, false, false);
        assert_eq!(check.verdict, DirVerdict::NoPermission);
    }

    #[test]
    fn a_plain_directory_is_ok_and_not_git() {
        let check = dir_check(true, true, true, false);
        assert_eq!(check.verdict, DirVerdict::Ok);
        assert!(!check.is_git_repo);
    }

    #[test]
    fn a_directory_with_a_git_marker_is_a_git_repo() {
        let check = dir_check(true, true, true, true);
        assert_eq!(check.verdict, DirVerdict::Ok);
        assert!(check.is_git_repo);
    }

    #[test]
    fn an_unreadable_directory_never_claims_git() {
        let check = dir_check(true, true, false, true);
        assert_eq!(check.verdict, DirVerdict::NoPermission);
        assert!(!check.is_git_repo);
    }
}
