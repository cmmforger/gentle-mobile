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
//!
//! T27 adds the browsing half of the `+` flow (S30.3): the machine's disks,
//! parsed from `/proc/mounts` on the host (never shelled out), and a
//! directory's folders — folders only, `.git` detection, symlinks reported
//! with their target and never followed. Both are pure over the minimal
//! [`SftpLike`] trait, so the parsing and filtering are unit-tested against
//! a fake with fixture text and listings, with no network anywhere.

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

// ─── T27: disk and folder browsing for the `+` flow ──────────────────────
//
// The `+` walks the machine's real storage instead of asking for an absolute
// path (S30.3): first the disks — `/proc/mounts`, parsed here, never shelled
// out — then a directory's folders, tapped through one level at a time.
// Both operations are pure over the minimal [`SftpLike`] trait, so the
// parsing and filtering are unit-tested against a fake with no network at
// all; the real SFTP handle implements the same trait over the pinned lane.

/// The device class behind a mount, when knowable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeviceType {
    /// A local block device (`/dev/…`): one of the machine's own disks.
    LocalDisk,
    /// A network filesystem (nfs, cifs, sshfs, …).
    NetworkShare,
}

/// One mount the `+` picker offers as a place to start a project.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Disk {
    /// Absolute mount point, octal escapes resolved, no trailing slash
    /// (except `/` itself).
    pub mount_point: String,
    /// The short human label the list shows: the last path component
    /// (`D-SSD`), or `System` for the root.
    pub label: String,
    /// The device class when the fs source and fs type make it knowable.
    pub device_type: Option<DeviceType>,
}

/// One entry of a browsed directory: what the SFTP READDIR reports, with no
/// following of symlinks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BrowseEntry {
    pub name: String,
    pub is_dir: bool,
    pub is_symlink: bool,
}

/// One folder the browser offers. A symlink is reported with its target and
/// is never followed, so it never claims a git verdict.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FolderEntry {
    pub name: String,
    /// Absolute path built from the browsed directory.
    pub path: String,
    pub is_symlink: bool,
    /// The symlink's target, when the entry is a symlink and the target
    /// could be read. Never followed.
    pub target: Option<String>,
    /// `true` only when the folder itself carries a `.git` entry (a
    /// directory in a normal checkout, a file in a linked worktree).
    pub is_git_repo: bool,
}

/// A typed browsing failure — a sentence for the UI, never a panic and
/// never an empty list pretending success.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BrowseError {
    /// The path does not exist on the host.
    Missing { path: String },
    /// The path exists but is not a directory.
    NotADirectory { path: String },
    /// The directory exists but could not be read.
    NoPermission { path: String },
    /// The host answered with something else (transport, protocol, server).
    Host { detail: String },
}

impl std::fmt::Display for BrowseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BrowseError::Missing { path } => write!(f, "{path} does not exist on the host"),
            BrowseError::NotADirectory { path } => write!(f, "{path} is not a directory"),
            BrowseError::NoPermission { path } => {
                write!(f, "{path} cannot be read (permission denied)")
            }
            BrowseError::Host { detail } => {
                write!(f, "the host could not answer the listing: {detail}")
            }
        }
    }
}

/// The minimal SFTP surface browsing needs. Implemented for the real
/// [`russh_sftp::client::SftpSession`] and, in tests, for a fake with
/// fixture files and listings — so every decision below is testable
/// without a network.
//
// The trait is always used through concrete handles (the real SFTP session,
// the test fake) and never as a trait object, so native `async fn` is what
// we want here, not `async_trait` boxing.
#[allow(async_fn_in_trait)]
pub trait SftpLike {
    /// Whole contents of a small file (e.g. `/proc/mounts`).
    async fn read_file(&self, path: &str) -> Result<Vec<u8>, BrowseError>;
    /// One level of a directory, entry kinds as reported by the server
    /// (readdir never follows symlinks).
    async fn list_dir(&self, path: &str) -> Result<Vec<BrowseEntry>, BrowseError>;
    /// Whether a path exists (e.g. `<folder>/.git`).
    async fn path_exists(&self, path: &str) -> Result<bool, BrowseError>;
    /// The target of a symlink, without following it.
    async fn link_target(&self, path: &str) -> Result<String, BrowseError>;
}

/// Map one SFTP error to the typed browsing error, by server status code.
fn sftp_browse_error(path: &str, error: russh_sftp::client::error::Error) -> BrowseError {
    if let russh_sftp::client::error::Error::Status(status) = &error {
        match status.status_code {
            russh_sftp::protocol::StatusCode::NoSuchFile => {
                return BrowseError::Missing { path: path.to_owned() };
            }
            russh_sftp::protocol::StatusCode::PermissionDenied => {
                return BrowseError::NoPermission { path: path.to_owned() };
            }
            _ => {}
        }
    }
    BrowseError::Host { detail: error.to_string() }
}

impl SftpLike for russh_sftp::client::SftpSession {
    async fn read_file(&self, path: &str) -> Result<Vec<u8>, BrowseError> {
        self.read(path).await.map_err(|error| sftp_browse_error(path, error))
    }

    async fn list_dir(&self, path: &str) -> Result<Vec<BrowseEntry>, BrowseError> {
        // Stat first: a path that exists but is a file is a typed
        // NotADirectory, not a server-side generic failure.
        let metadata = self
            .metadata(path)
            .await
            .map_err(|error| sftp_browse_error(path, error))?;
        if !metadata.is_dir() {
            return Err(BrowseError::NotADirectory { path: path.to_owned() });
        }
        let entries = self
            .read_dir(path)
            .await
            .map_err(|error| sftp_browse_error(path, error))?;
        Ok(entries
            .map(|entry| {
                let file_type = entry.file_type();
                BrowseEntry {
                    name: entry.file_name(),
                    is_dir: file_type.is_dir(),
                    is_symlink: file_type.is_symlink(),
                }
            })
            .collect())
    }

    async fn path_exists(&self, path: &str) -> Result<bool, BrowseError> {
        self.try_exists(path).await.map_err(|error| sftp_browse_error(path, error))
    }

    async fn link_target(&self, path: &str) -> Result<String, BrowseError> {
        self.read_link(path).await.map_err(|error| sftp_browse_error(path, error))
    }
}

/// Where `/proc/mounts` lives on the host.
pub const PROC_MOUNTS: &str = "/proc/mounts";

/// Filesystem types that are kernel plumbing, never a place to start a
/// project. `tmpfs` is handled separately (kept for `/` only) and any
/// `fuse.<daemon>` mount is treated as pseudo too — a FUSE *daemon* (gvfs,
/// portal, snap helpers) is not storage, while real FUSE-backed disks
/// report `fuseblk` and stay.
const PSEUDO_FSTYPES: &[&str] = &[
    "proc",
    "sysfs",
    "devtmpfs",
    "devpts",
    "devfs",
    "mqueue",
    "hugetlbfs",
    "ramfs",
    "cgroup",
    "cgroup2",
    "squashfs",
    "overlay",
    "securityfs",
    "debugfs",
    "tracefs",
    "pstore",
    "bpf",
    "configfs",
    "autofs",
    "binfmt_misc",
    "fusectl",
    "efivarfs",
    "nsfs",
    "rpc_pipefs",
    "none",
];

/// Mount-point prefixes that are system plumbing or kernel images, not
/// places a human starts a project. `/boot/efi` and `/run/user/…` fall
/// under these.
const PSEUDO_MOUNT_ROOTS: &[&str] = &["/proc", "/sys", "/dev", "/run", "/snap", "/boot"];

/// Filesystem types that live on another machine.
const NETWORK_FSTYPES: &[&str] = &[
    "nfs", "nfs4", "cifs", "smbfs", "smb2", "smb3", "sshfs", "fuse.sshfs", "9p", "ceph",
    "cephfs", "afp", "davfs", "ncpfs", "afs",
];

/// True when the mount is pseudo plumbing: a pseudo fs type, a
/// `fuse.<daemon>` type, a tmpfs outside the root, or a mount point under
/// the system trees. The root is always kept, even when it is tmpfs.
fn is_pseudo(fstype: &str, mount_point: &str) -> bool {
    if mount_point == "/" {
        return false;
    }
    if fstype == "tmpfs"
        || PSEUDO_FSTYPES.contains(&fstype)
        || fstype.starts_with("fuse.")
    {
        return true;
    }
    PSEUDO_MOUNT_ROOTS.iter().any(|root| {
        mount_point == *root || mount_point.starts_with(&format!("{root}/"))
    })
}

fn device_type_for(fstype: &str, source: &str) -> Option<DeviceType> {
    if source.starts_with("/dev/") {
        return Some(DeviceType::LocalDisk);
    }
    if NETWORK_FSTYPES.contains(&fstype) {
        return Some(DeviceType::NetworkShare);
    }
    None
}

fn human_label(mount_point: &str) -> String {
    if mount_point == "/" {
        return "System".to_owned();
    }
    mount_point
        .rsplit('/')
        .next()
        .unwrap_or(mount_point)
        .to_owned()
}

/// Resolve the octal escapes `/proc/mounts` uses for special bytes
/// (`\040` space, `\011` tab, `\012` newline, `\134` backslash, …).
fn unescape_mount_field(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'\\'
            && index + 3 < bytes.len()
            && bytes[index + 1..index + 4]
                .iter()
                .all(|b| (b'0'..=b'7').contains(b))
        {
            let value = (bytes[index + 1] - b'0') * 64
                + (bytes[index + 2] - b'0') * 8
                + (bytes[index + 3] - b'0');
            out.push(value);
            index += 4;
        } else {
            out.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Parse `/proc/mounts` into the mounts a human recognises as their
/// storage: root first, then by mount point, pseudo filesystems and
/// pseudo mount trees skipped, the same device mounted twice collapsed to
/// its first (shallowest) mount point. Pure — tested against fixture text.
pub fn parse_mounts(mounts_text: &str) -> Vec<Disk> {
    // (source, parsed disk) so the device dedupe can key on the fs source.
    let mut parsed: Vec<(String, Disk)> = Vec::new();
    for line in mounts_text.lines() {
        let mut fields = line.split_whitespace();
        let (Some(raw_source), Some(raw_point), Some(fstype)) =
            (fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        let source = unescape_mount_field(raw_source);
        let mount_point = unescape_mount_field(raw_point);
        if is_pseudo(fstype, &mount_point) {
            continue;
        }
        let device_type = device_type_for(fstype, &source);
        parsed.push((
            source,
            Disk {
                mount_point,
                label: String::new(),
                device_type,
            },
        ));
    }
    // Root first, then by mount point. Sorting before the device dedupe
    // means the shallowest mount point of a duplicated device survives.
    parsed.sort_by(|a, b| {
        let root = |point: &String| point != "/";
        (root(&a.1.mount_point), &a.1.mount_point)
            .cmp(&(root(&b.1.mount_point), &b.1.mount_point))
    });
    let mut seen_devices: std::collections::HashSet<String> =
        std::collections::HashSet::new();
    let mut seen_points: std::collections::HashSet<String> =
        std::collections::HashSet::new();
    let mut disks: Vec<Disk> = Vec::new();
    for (source, mut disk) in parsed {
        if !seen_points.insert(disk.mount_point.clone()) {
            continue;
        }
        // "The same device mounted twice": only mounts whose fs source is
        // a real block device participate; `/` keeps its subvolume mounts
        // out and bind mounts collapse onto their original.
        if source.starts_with("/dev/") && !seen_devices.insert(source.clone()) {
            continue;
        }
        disk.label = human_label(&disk.mount_point);
        disks.push(disk);
    }
    disks
}

/// The pure shape of one directory level: folders only, hidden entries and
/// the `.git` marker itself skipped, symlinks kept (their target is filled
/// in by [`list_folders`], never followed), names sorted. Tested directly
/// against fixture listings.
pub fn folder_rows(dir_path: &str, entries: &[BrowseEntry]) -> Vec<FolderEntry> {
    let base = dir_path.trim_end_matches('/');
    let base = if base.is_empty() { "/" } else { base };
    let mut rows: Vec<FolderEntry> = entries
        .iter()
        .filter(|entry| {
            entry.name != "."
                && entry.name != ".."
                && !entry.name.starts_with('.')
                && (entry.is_dir || entry.is_symlink)
        })
        .map(|entry| FolderEntry {
            name: entry.name.clone(),
            path: if base == "/" {
                format!("/{}", entry.name)
            } else {
                format!("{base}/{}", entry.name)
            },
            is_symlink: entry.is_symlink,
            target: None,
            is_git_repo: false,
        })
        .collect();
    rows.sort_by(|a, b| a.name.cmp(&b.name));
    rows
}

/// List the machine's disks over the trait: read `/proc/mounts`, parse it.
/// A missing or unreadable mount file is a typed error.
pub async fn list_disks(sftp: &impl SftpLike) -> Result<Vec<Disk>, BrowseError> {
    let bytes = sftp.read_file(PROC_MOUNTS).await?;
    Ok(parse_mounts(&String::from_utf8_lossy(&bytes)))
}

/// List one directory's folders over the trait: the pure [`folder_rows`]
/// shape, plus per-folder facts gathered honestly — a `.git` entry check
/// for real folders (unreadable markers read as "not a repo", never a
/// guess), and the symlink target read without following. An unreadable,
/// missing or non-directory path is a typed error.
pub async fn list_folders(
    sftp: &impl SftpLike,
    dir_path: &str,
) -> Result<Vec<FolderEntry>, BrowseError> {
    let entries = sftp.list_dir(dir_path).await?;
    let mut rows = folder_rows(dir_path, &entries);
    for row in &mut rows {
        if row.is_symlink {
            // Never followed: the target is reported when readable, and a
            // broken symlink stays an honest entry with no target.
            row.target = sftp.link_target(&row.path).await.ok();
        } else {
            let marker = format!("{}/.git", row.path);
            row.is_git_repo = sftp.path_exists(&marker).await.unwrap_or(false);
        }
    }
    Ok(rows)
}

fn hub_error_to_browse(error: crate::requests::HubError) -> BrowseError {
    BrowseError::Host { detail: error.to_string() }
}

/// The disks of the profile's host over the pinned one-shot SSH + SFTP
/// lane — the same transport contract as [`check_host_dir_over_sftp`].
pub async fn list_host_disks_over_sftp(
    profile: &HostProfile,
) -> Result<Vec<Disk>, BrowseError> {
    let (ssh, sftp) =
        crate::sessions::connect_sftp(profile).await.map_err(hub_error_to_browse)?;
    let outcome = list_disks(&sftp).await;
    let _ = ssh
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
    outcome
}

/// The folders of one host directory over the pinned one-shot SSH + SFTP
/// lane, same contract as [`list_host_disks_over_sftp`].
pub async fn list_host_folders_over_sftp(
    profile: &HostProfile,
    dir_path: &str,
) -> Result<Vec<FolderEntry>, BrowseError> {
    let (ssh, sftp) =
        crate::sessions::connect_sftp(profile).await.map_err(hub_error_to_browse)?;
    let outcome = list_folders(&sftp, dir_path).await;
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

    /// The disk and folder browsing of T27: parsing and filtering are pure
    /// and every async path runs against a fake [`SftpLike`], no network.
    mod browsing {
        use super::*;
        use std::collections::HashMap;

        /// A fixture `/proc/mounts` shaped like the real machine this
        /// worktree runs on: a LUKS root with btrfs subvolume mounts, two
        /// extra physical disks under `/mnt`, and the usual kernel noise.
        const FIXTURE_MOUNTS: &str = concat!(
            "/dev/mapper/luks-root / btrfs rw,noatime,subvol=/@ 0 0\n",
            "devtmpfs /dev devtmpfs rw,nosuid 0 0\n",
            "tmpfs /dev/shm tmpfs rw 0 0\n",
            "devpts /dev/pts devpts rw 0 0\n",
            "sysfs /sys sysfs rw 0 0\n",
            "cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\n",
            "proc /proc proc rw 0 0\n",
            "tmpfs /run tmpfs rw 0 0\n",
            "/dev/nvme0n1p1 /boot vfat rw 0 0\n",
            "/dev/nvme0n1p1 /boot/efi vfat rw 0 0\n",
            "/dev/sda1 /mnt/D-SSD ext4 rw,noatime 0 0\n",
            "/dev/sdb1 /mnt/WD-Black ext4 rw,noatime 0 0\n",
            "/dev/mapper/luks-root /home btrfs rw 0 0\n",
            "/dev/mapper/luks-root /srv btrfs rw 0 0\n",
            "/dev/mapper/luks-root /var/log btrfs rw 0 0\n",
            "systemd-1 /proc/sys/fs/binfmt_misc autofs rw 0 0\n",
            "/dev/loop0 /snap/chromium/2909 squashfs ro 0 0\n",
            "overlay /var/lib/docker/overlay2/x/merged overlay rw 0 0\n",
            "tmpfs /tmp tmpfs rw 0 0\n",
            "portal /run/user/1000/doc fuse.portal rw 0 0\n",
            "gvfsd-fuse /run/user/1000/gvfs fuse.gvfsd-fuse rw 0 0\n",
            "192.168.1.20:/volume1/media /mnt/store nfs4 rw 0 0\n",
        );

        #[test]
        fn parse_mounts_keeps_the_real_disks_in_stable_order() {
            let disks = parse_mounts(FIXTURE_MOUNTS);
            let points: Vec<&str> =
                disks.iter().map(|disk| disk.mount_point.as_str()).collect();
            // Root first, then by mount point. /tmp (tmpfs) is skipped,
            // /home and /srv share the root device and are deduplicated.
            assert_eq!(points, vec!["/", "/mnt/D-SSD", "/mnt/WD-Black", "/mnt/store"]);
            assert_eq!(disks[0].label, "System");
            assert_eq!(disks[1].label, "D-SSD");
            assert_eq!(disks[2].label, "WD-Black");
            assert_eq!(disks[1].device_type, Some(DeviceType::LocalDisk));
        }

        #[test]
        fn parse_mounts_skips_pseudo_filesystems() {
            let pseudo = concat!(
                "proc /proc proc rw 0 0\n",
                "sysfs /sys sysfs rw 0 0\n",
                "devtmpfs /dev devtmpfs rw 0 0\n",
                "devpts /dev/pts devpts rw 0 0\n",
                "cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\n",
                "cgroup /sys/fs/cgroup/unified cgroup rw 0 0\n",
                "/dev/loop0 /snap/chromium/2909 squashfs ro 0 0\n",
                "overlay /var/lib/docker/merged overlay rw 0 0\n",
                "systemd-1 /proc/sys/fs/binfmt_misc autofs rw 0 0\n",
                "binfmt_misc /proc/sys/fs/binfmt_misc binfmt_misc rw 0 0\n",
                "securityfs /sys/kernel/security securityfs rw 0 0\n",
                "tracefs /sys/kernel/tracing tracefs rw 0 0\n",
                "none /sys/fs/bpf bpf rw 0 0\n",
                "efivarfs /sys/firmware/efi/efivars efivarfs rw 0 0\n",
                "none /run/credentials/systemd-journald.service tmpfs ro 0 0\n",
                "mqueue /dev/mqueue mqueue rw 0 0\n",
                "hugetlbfs /dev/hugepages hugetlbfs rw 0 0\n",
                "fusectl /sys/fs/fuse/connections fusectl rw 0 0\n",
                "configfs /sys/kernel/config configfs rw 0 0\n",
                "pstore /sys/fs/pstore pstore rw 0 0\n",
                "portal /run/user/1000/doc fuse.portal rw 0 0\n",
                "gvfsd-fuse /run/user/1000/gvfs fuse.gvfsd-fuse rw 0 0\n",
                "none /run/xyz tmpfs rw 0 0\n",
            );
            assert!(parse_mounts(pseudo).is_empty());
        }

        #[test]
        fn parse_mounts_keeps_root_even_when_it_is_tmpfs() {
            let text = concat!(
                "tmpfs / tmpfs rw 0 0\n",
                "/dev/sda1 /mnt/D-SSD ext4 rw 0 0\n",
            );
            let disks = parse_mounts(text);
            assert_eq!(disks.len(), 2);
            assert_eq!(disks[0].mount_point, "/");
            assert_eq!(disks[0].label, "System");
        }

        #[test]
        fn parse_mounts_dedupes_the_same_device() {
            let text = concat!(
                "/dev/mapper/luks-root / btrfs rw 0 0\n",
                "/dev/mapper/luks-root /home btrfs rw 0 0\n",
                "/dev/mapper/luks-root /var/log btrfs rw 0 0\n",
                "/dev/sda1 /mnt/D-SSD ext4 rw 0 0\n",
            );
            let disks = parse_mounts(text);
            let points: Vec<&str> =
                disks.iter().map(|disk| disk.mount_point.as_str()).collect();
            assert_eq!(points, vec!["/", "/mnt/D-SSD"]);
        }

        #[test]
        fn parse_mounts_unescapes_octal_sequences() {
            let text = concat!(
                "/dev/sdc1 /mnt/My\\040Disk ext4 rw 0 0\n",
                "/dev/mapper/luks-ff\\134x2d42 / btrfs rw 0 0\n",
            );
            let disks = parse_mounts(text);
            let points: Vec<&str> =
                disks.iter().map(|disk| disk.mount_point.as_str()).collect();
            assert_eq!(points, vec!["/", "/mnt/My Disk"]);
            assert_eq!(disks[0].label, "System");
            assert_eq!(disks[1].label, "My Disk");
        }

        #[test]
        fn parse_mounts_reports_network_shares_and_unknowns() {
            let text = concat!(
                "192.168.1.20:/volume1 /mnt/store nfs4 rw 0 0\n",
                "//nas/share /mnt/backup cifs rw 0 0\n",
                "tank/projects /mnt/zfs zfs rw 0 0\n",
            );
            let disks = parse_mounts(text);
            assert_eq!(disks[0].device_type, Some(DeviceType::NetworkShare));
            assert_eq!(disks[1].device_type, Some(DeviceType::NetworkShare));
            assert_eq!(disks[2].device_type, None);
        }

        #[test]
        fn parse_mounts_skips_pseudo_mount_points() {
            let text = concat!(
                "/dev/nvme0n1p1 /boot vfat rw 0 0\n",
                "/dev/nvme0n1p1 /boot/efi vfat rw 0 0\n",
                "tmpfs /run/user/1000 tmpfs rw 0 0\n",
                "proc /proc/x proc rw 0 0\n",
            );
            assert!(parse_mounts(text).is_empty());
        }

        fn entry(name: &str, is_dir: bool, is_symlink: bool) -> BrowseEntry {
            BrowseEntry { name: name.to_owned(), is_dir, is_symlink }
        }

        #[test]
        fn folder_rows_lists_folders_only_and_skips_hidden() {
            let entries = vec![
                entry("notes.txt", false, false),
                entry("work", true, false),
                entry("projects", true, false),
                entry(".cache", true, false),
                entry(".git", true, false),
                entry("link", false, true),
                entry("socket", false, false),
            ];
            let rows = folder_rows("/mnt/D-SSD/", &entries);
            let names: Vec<&str> = rows.iter().map(|row| row.name.as_str()).collect();
            // Files are not places to start a project; hidden entries (and
            // the `.git` marker itself) are skipped.
            assert_eq!(names, vec!["link", "projects", "work"]);
            assert_eq!(rows[0].path, "/mnt/D-SSD/link");
            assert!(rows[0].is_symlink);
            assert!(rows.iter().all(|row| row.target.is_none()));
        }

        #[test]
        fn folder_rows_builds_absolute_paths_from_the_root() {
            let entries = vec![entry("home", true, false)];
            let rows = folder_rows("/", &entries);
            assert_eq!(rows[0].path, "/home");
        }

        /// The fake the async paths run against: fixture files, listings
        /// and symlink targets in plain tables, errors shaped like the
        /// SFTP server would report them.
        struct FakeSftp {
            files: HashMap<String, Vec<u8>>,
            listings: HashMap<String, Vec<BrowseEntry>>,
            links: HashMap<String, String>,
            denied: Vec<String>,
        }

        impl FakeSftp {
            fn deny(mut self, path: &str) -> Self {
                self.denied.push(path.to_owned());
                self
            }

            fn is_denied(&self, path: &str) -> bool {
                self.denied.iter().any(|denied| denied == path)
            }
        }

        impl SftpLike for FakeSftp {
            async fn read_file(&self, path: &str) -> Result<Vec<u8>, BrowseError> {
                if self.is_denied(path) {
                    return Err(BrowseError::NoPermission { path: path.to_owned() });
                }
                self.files
                    .get(path)
                    .cloned()
                    .ok_or_else(|| BrowseError::Missing { path: path.to_owned() })
            }

            async fn list_dir(&self, path: &str) -> Result<Vec<BrowseEntry>, BrowseError> {
                if self.is_denied(path) {
                    return Err(BrowseError::NoPermission { path: path.to_owned() });
                }
                if self.files.contains_key(path) {
                    return Err(BrowseError::NotADirectory { path: path.to_owned() });
                }
                self.listings
                    .get(path)
                    .cloned()
                    .ok_or_else(|| BrowseError::Missing { path: path.to_owned() })
            }

            async fn path_exists(&self, path: &str) -> Result<bool, BrowseError> {
                if self.files.contains_key(path) {
                    return Ok(true);
                }
                let (parent, name) = match path.rsplit_once('/') {
                    Some(("", name)) => ("/", name),
                    Some((parent, name)) => (parent, name),
                    None => return Ok(false),
                };
                Ok(self
                    .listings
                    .get(parent)
                    .is_some_and(|entries| entries.iter().any(|e| e.name == name)))
            }

            async fn link_target(&self, path: &str) -> Result<String, BrowseError> {
                self.links
                    .get(path)
                    .cloned()
                    .ok_or_else(|| BrowseError::Missing { path: path.to_owned() })
            }
        }

        fn fake_with_mounts() -> FakeSftp {
            FakeSftp {
                files: HashMap::from([
                    ("/proc/mounts".to_owned(), FIXTURE_MOUNTS.as_bytes().to_vec()),
                ]),
                listings: HashMap::new(),
                links: HashMap::new(),
                denied: Vec::new(),
            }
        }

        #[tokio::test]
        async fn list_disks_reads_proc_mounts_through_the_trait() {
            let disks = list_disks(&fake_with_mounts()).await.unwrap();
            let points: Vec<&str> =
                disks.iter().map(|disk| disk.mount_point.as_str()).collect();
            assert_eq!(points, vec!["/", "/mnt/D-SSD", "/mnt/WD-Black", "/mnt/store"]);
        }

        #[tokio::test]
        async fn a_missing_mount_file_is_a_typed_error() {
            let fake = FakeSftp {
                files: HashMap::new(),
                listings: HashMap::new(),
                links: HashMap::new(),
                denied: Vec::new(),
            };
            assert_eq!(
                list_disks(&fake).await.unwrap_err(),
                BrowseError::Missing { path: "/proc/mounts".to_owned() },
            );
        }

        fn browsing_fake() -> FakeSftp {
            FakeSftp {
                files: HashMap::new(),
                listings: HashMap::from([
                    (
                        "/repos".to_owned(),
                        vec![
                            entry("work", true, false),
                            entry("scratch", true, false),
                            entry("old", false, true),
                            entry("README.md", false, false),
                            entry(".git", true, false),
                        ],
                    ),
                    (
                        "/repos/work".to_owned(),
                        vec![entry(".git", true, false), entry("src", true, false)],
                    ),
                    ("/repos/scratch".to_owned(), vec![entry("tmp", true, false)]),
                ]),
                links: HashMap::from([
                    ("/repos/old".to_owned(), "/srv/archive/repos/old".to_owned()),
                ]),
                denied: Vec::new(),
            }
        }

        #[tokio::test]
        async fn list_folders_detects_git_repos_and_reports_symlinks() {
            let rows = list_folders(&browsing_fake(), "/repos").await.unwrap();
            assert_eq!(rows.len(), 3);
            assert_eq!(rows[0].name, "old");
            assert_eq!(rows[0].path, "/repos/old");
            assert!(rows[0].is_symlink);
            assert_eq!(rows[0].target.as_deref(), Some("/srv/archive/repos/old"));
            // A symlink is never followed, so no git verdict and no mistake.
            assert!(!rows[0].is_git_repo);
            assert_eq!(rows[1].name, "scratch");
            assert!(!rows[1].is_git_repo);
            assert_eq!(rows[2].name, "work");
            assert!(rows[2].is_git_repo);
        }

        #[tokio::test]
        async fn an_unreadable_directory_is_a_typed_error() {
            let fake = browsing_fake().deny("/repos");
            assert_eq!(
                list_folders(&fake, "/repos").await.unwrap_err(),
                BrowseError::NoPermission { path: "/repos".to_owned() },
            );
        }

        #[tokio::test]
        async fn a_missing_directory_is_a_typed_error() {
            assert_eq!(
                list_folders(&browsing_fake(), "/repos/nope").await.unwrap_err(),
                BrowseError::Missing { path: "/repos/nope".to_owned() },
            );
        }

        #[tokio::test]
        async fn a_file_is_not_a_directory_to_browse() {
            let mut fake = browsing_fake();
            fake.files.insert(
                "/repos/README.md".to_owned(),
                b"read me".to_vec(),
            );
            assert_eq!(
                list_folders(&fake, "/repos/README.md").await.unwrap_err(),
                BrowseError::NotADirectory { path: "/repos/README.md".to_owned() },
            );
        }

        /// Host verification only, never a CI expectation: when the caller
        /// sets `GENTLE_T27_REAL_DISKS=1`, parse this machine's actual
        /// `/proc/mounts` and check the invariants the UI relies on.
        #[test]
        fn real_host_disks_when_asked() {
            if std::env::var("GENTLE_T27_REAL_DISKS").as_deref() != Ok("1") {
                return;
            }
            let text = std::fs::read_to_string("/proc/mounts")
                .expect("the host running with GENTLE_T27_REAL_DISKS=1 has /proc/mounts");
            let disks = parse_mounts(&text);
            assert_eq!(disks[0].mount_point, "/");
            assert_eq!(disks[0].label, "System");
            for disk in &disks {
                assert!(!disk.mount_point.starts_with("/proc"));
                assert!(!disk.mount_point.starts_with("/sys"));
                assert!(!disk.mount_point.starts_with("/dev"));
                assert!(!disk.mount_point.starts_with("/run"));
                assert!(!disk.mount_point.starts_with("/snap"));
                assert!(!disk.mount_point.starts_with("/boot"));
            }
            let points: Vec<&str> =
                disks.iter().map(|disk| disk.mount_point.as_str()).collect();
            assert!(points.contains(&"/mnt/D-SSD"), "points: {points:?}");
            assert!(points.contains(&"/mnt/WD-Black"), "points: {points:?}");
        }
    }
}
