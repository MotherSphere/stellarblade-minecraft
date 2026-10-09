//! What differs per operating system: the process list, the path the game side sees, the private bridge directory and
//! where Steam lives. Linux only for now (G1); the Windows branches come with the Windows build.

use anyhow::{Result, bail};
use std::fs;
use std::io;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};

/// One running process: its short name (Linux truncates it to 15 bytes) and its command line, `None` when unreadable.
pub struct Process {
    pub comm: String,
    pub cmdline: Option<Vec<u8>>,
}

/// The processes under a `/proc` tree. Processes that exit while listed are skipped.
pub fn processes(proc_root: &Path) -> io::Result<Vec<Process>> {
    let mut found = Vec::new();
    for entry in fs::read_dir(proc_root)? {
        let path = entry?.path();
        if !path
            .file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.bytes().all(|b| b.is_ascii_digit()))
        {
            continue;
        }
        let Ok(comm) = fs::read_to_string(path.join("comm")) else {
            continue;
        };
        found.push(Process {
            comm: comm.trim_end_matches('\n').to_string(),
            cmdline: fs::read(path.join("cmdline")).ok(),
        });
    }
    Ok(found)
}

/// The bridge directory as the game sees it through Proton: `Z:` plus the Linux path. The Lua config and the adapter's
/// directory file hold this same string.
pub fn game_path(bridge: &Path) -> Result<String> {
    let Some(path) = bridge.to_str() else {
        bail!("Bridge path must be UTF-8: {}", bridge.display())
    };
    if path.contains(['\n', '\r', '\0']) {
        bail!("Bridge path cannot contain control separators");
    }
    Ok(format!("Z:{path}"))
}

/// A Lua string literal of `text`: decimal escapes are valid Lua for every UTF-8 byte, including quotes and newlines.
pub fn lua_quote(text: &str) -> String {
    let mut quoted = String::from("\"");
    for byte in text.bytes() {
        quoted.push_str(&format!("\\{byte:03}"));
    }
    quoted.push('"');
    quoted
}

fn real(path: &Path, directory: bool) -> Result<PathBuf> {
    let path = std::path::absolute(path)?;
    let kind = if directory { "directory" } else { "file" };
    let metadata = fs::metadata(&path).ok();
    if fs::canonicalize(&path).ok().as_ref() != Some(&path)
        || !metadata.is_some_and(|m| if directory { m.is_dir() } else { m.is_file() })
    {
        bail!(
            "Expected a real {kind} without symlinks: {}",
            path.display()
        );
    }
    Ok(path)
}

/// An existing file whose path holds no symbolic link, as `input_proxy.py` requires.
pub fn real_file(path: &Path) -> Result<PathBuf> {
    real(path, false)
}

/// An existing directory whose path holds no symbolic link.
pub fn real_dir(path: &Path) -> Result<PathBuf> {
    real(path, true)
}

/// The shared bridge directory: absolute, without symlinks, owned by this user and closed to others (mode 0700).
pub fn private_dir(path: &Path) -> Result<PathBuf> {
    if !path.is_absolute() {
        bail!("Bridge directory must be an existing absolute directory without symlinks");
    }
    let path = real_dir(path)?;
    let metadata = fs::metadata(&path)?;
    // SAFETY: getuid has no preconditions and cannot fail.
    if metadata.uid() != unsafe { libc::getuid() } || metadata.permissions().mode() & 0o077 != 0 {
        bail!("Bridge directory must belong to this user with mode 0700");
    }
    Ok(path)
}

/// Checks the bridge directory before anything is written: an existing one must already be private
/// ([`private_dir`]), and a missing one must be creatable as one, under real directories only.
pub fn bridge_ready(path: &Path) -> Result<()> {
    game_path(path)?;
    if fs::symlink_metadata(path).is_ok() {
        private_dir(path)?;
        return Ok(());
    }
    if !path.is_absolute() {
        bail!("The bridge directory must be absolute: {}", path.display());
    }
    // The nearest existing ancestor, a symbolic link included, must be a real directory.
    match path
        .ancestors()
        .skip(1)
        .find(|a| fs::symlink_metadata(a).is_ok())
    {
        Some(ancestor) => real_dir(ancestor).map(drop),
        None => bail!("No existing folder above {}", path.display()),
    }
}

/// How Steam was installed: Flatpak and Snap Steam run Proton in their own sandbox.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SteamKind {
    Native,
    Flatpak,
    Snap,
}

/// The places a Steam root can be under a home directory, first match first.
pub fn steam_roots(home: &Path) -> Vec<(PathBuf, SteamKind)> {
    [
        (".local/share/Steam", SteamKind::Native),
        (".steam/steam", SteamKind::Native),
        (".steam/root", SteamKind::Native),
        (".steam/debian-installation", SteamKind::Native),
        (
            ".var/app/com.valvesoftware.Steam/.local/share/Steam",
            SteamKind::Flatpak,
        ),
        ("snap/steam/common/.local/share/Steam", SteamKind::Snap),
    ]
    .into_iter()
    .map(|(relative, kind)| (home.join(relative), kind))
    .collect()
}

/// The user's home directory.
pub fn home() -> Result<PathBuf> {
    match std::env::var_os("HOME") {
        Some(home) if !home.is_empty() => Ok(PathBuf::from(home)),
        _ => bail!("HOME is not set"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lua_quote_escapes_every_byte() {
        assert_eq!(
            lua_quote("Z:/a\"é"),
            "\"\\090\\058\\047\\097\\034\\195\\169\""
        );
    }

    #[test]
    fn private_dir_refuses_shared_and_symlinked_directories() {
        let root = tempfile::tempdir().unwrap();
        let bridge = root.path().join("bridge");
        fs::create_dir(&bridge).unwrap();
        fs::set_permissions(&bridge, fs::Permissions::from_mode(0o755)).unwrap();
        assert!(private_dir(&bridge).is_err());
        fs::set_permissions(&bridge, fs::Permissions::from_mode(0o700)).unwrap();
        let canonical = fs::canonicalize(&bridge).unwrap();
        assert_eq!(private_dir(&canonical).unwrap(), canonical);
        let alias = root.path().join("alias");
        std::os::unix::fs::symlink(&bridge, &alias).unwrap();
        assert!(private_dir(&alias).is_err());
        assert!(private_dir(Path::new("relative")).is_err());
        assert!(game_path(Path::new("/a\nb")).is_err());
        assert!(bridge_ready(&alias).is_err());
        assert!(
            bridge_ready(&alias.join("new")).is_err(),
            "created behind a link"
        );
        assert!(bridge_ready(&canonical.join("new/bridge")).is_ok());
        fs::set_permissions(&bridge, fs::Permissions::from_mode(0o755)).unwrap();
        assert!(bridge_ready(&canonical).is_err());
    }

    #[test]
    fn processes_read_comm_and_cmdline() {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir_all(root.path().join("12")).unwrap();
        fs::write(root.path().join("12/comm"), "java\n").unwrap();
        fs::write(root.path().join("12/cmdline"), b"java\0-cp\0x\0").unwrap();
        fs::create_dir_all(root.path().join("self")).unwrap();
        fs::create_dir_all(root.path().join("13")).unwrap(); // exited: no comm
        let found = processes(root.path()).unwrap();
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].comm, "java");
        assert_eq!(found[0].cmdline.as_deref(), Some(&b"java\0-cp\0x\0"[..]));
    }
}
