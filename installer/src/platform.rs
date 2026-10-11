//! What differs per operating system, and the only module with `cfg(unix)` and `cfg(windows)` branches: the process
//! list, the path the game side sees, the private bridge directory, where Steam, Prism and the saves live, and the user's
//! home. On Linux the game runs through Proton; on Windows it runs natively and nothing here touches the registry
//! except to read where Steam is.

use anyhow::{Result, bail};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use crate::launcher;
use crate::{Kind, Refusal};

/// Whether Stellar Blade runs through Proton here: a prefix, `Z:` paths and a DLL override that only the player sets.
pub const PROTON: bool = cfg!(unix);

/// One running process: its short name (Linux truncates it to 15 bytes; Windows gives the image name, `.exe`
/// included), its command line (arguments joined by NUL) and its working folder, each `None` when unreadable.
pub struct Process {
    pub comm: String,
    pub cmdline: Option<Vec<u8>>,
    pub cwd: Option<PathBuf>,
}

/// The processes under a `/proc` tree. Processes that exit while listed are skipped.
#[cfg(unix)]
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
            cwd: fs::read_link(path.join("cwd")).ok(),
        });
    }
    Ok(found)
}

/// The processes running now; `_proc_root` is the Linux `/proc` and means nothing here. A process whose command line
/// cannot be read (an elevated one) has `cmdline: None`.
#[cfg(windows)]
pub fn processes(_proc_root: &Path) -> io::Result<Vec<Process>> {
    use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};
    let mut system = System::new();
    system.refresh_processes_specifics(
        ProcessesToUpdate::All,
        true,
        ProcessRefreshKind::nothing()
            .with_cmd(UpdateKind::Always)
            .with_cwd(UpdateKind::Always),
    );
    Ok(system
        .processes()
        .values()
        .map(|process| Process {
            comm: process.name().to_string_lossy().into_owned(),
            cmdline: (!process.cmd().is_empty()).then(|| {
                process
                    .cmd()
                    .iter()
                    .map(|a| a.to_string_lossy())
                    .collect::<Vec<_>>()
                    .join("\0")
                    .into_bytes()
            }),
            cwd: process.cwd().map(Path::to_path_buf),
        })
        .collect())
}

fn plain(bridge: &Path) -> Result<&str> {
    let Some(path) = bridge.to_str() else {
        bail!(Refusal::new(Kind::NonUtf8, bridge.display()))
    };
    if path.contains(['\n', '\r', '\0']) {
        bail!(Refusal::new(Kind::BridgeControl, path.escape_debug()));
    }
    Ok(path)
}

/// The bridge directory as the game sees it through Proton: `Z:` plus the Linux path. The Lua config and the adapter's
/// directory file hold this same string.
#[cfg(unix)]
pub fn game_path(bridge: &Path) -> Result<String> {
    Ok(format!("Z:{}", plain(bridge)?))
}

/// The bridge directory as the game opens it: UE4SS's Lua uses narrow `fopen`, so the path must be ASCII. A path with
/// other characters (`C:\Users\Clément\...`) is written with the 8.3 short name of its nearest existing folder; without
/// one it is refused. The Lua config and the adapter's directory file hold this same string.
#[cfg(windows)]
pub fn game_path(bridge: &Path) -> Result<String> {
    let path = plain(bridge)?;
    if !matches!(
        bridge.components().next(),
        Some(std::path::Component::Prefix(p)) if matches!(p.kind(), std::path::Prefix::Disk(_))
    ) {
        bail!(Refusal::new(Kind::BridgeVolume, path));
    }
    if path.is_ascii() {
        return Ok(path.to_string());
    }
    let refused = || Refusal::new(Kind::BridgeAscii, path);
    let Some(existing) = bridge.ancestors().find(|a| a.exists()) else {
        bail!(refused());
    };
    let rest = bridge.strip_prefix(existing)?;
    let short = short_path(existing).ok_or_else(refused)?;
    let full = if rest.as_os_str().is_empty() {
        PathBuf::from(short)
    } else {
        Path::new(&short).join(rest)
    };
    match full.to_str() {
        Some(text) if text.is_ascii() => Ok(text.to_string()),
        _ => bail!(refused()),
    }
}

#[cfg(windows)]
fn wide(path: &Path) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    path.as_os_str().encode_wide().chain([0]).collect()
}

/// The 8.3 short form of an existing path, when the volume keeps short names.
#[cfg(windows)]
fn short_path(path: &Path) -> Option<String> {
    use windows_sys::Win32::Storage::FileSystem::GetShortPathNameW;
    let long = wide(path);
    let mut buffer = vec![0u16; 1024];
    // SAFETY: both buffers are valid for the lengths given; `long` is NUL-terminated.
    let length =
        unsafe { GetShortPathNameW(long.as_ptr(), buffer.as_mut_ptr(), buffer.len() as u32) };
    if length == 0 || length as usize >= buffer.len() {
        return None;
    }
    String::from_utf16(&buffer[..length as usize]).ok()
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

/// `fs::canonicalize`, without the `\\?\` prefix Windows adds: the paths stay usable as written, with `/` separators
/// and in files the game reads.
#[cfg(unix)]
pub fn canonicalize(path: &Path) -> io::Result<PathBuf> {
    fs::canonicalize(path)
}

/// `fs::canonicalize`, without the `\\?\` prefix Windows adds: the paths stay usable as written, with `/` separators
/// and in files the game reads.
#[cfg(windows)]
pub fn canonicalize(path: &Path) -> io::Result<PathBuf> {
    let full = fs::canonicalize(path)?;
    let Some(text) = full.to_str() else {
        return Ok(full);
    };
    if let Some(share) = text.strip_prefix(r"\\?\UNC\") {
        return Ok(PathBuf::from(format!(r"\\{share}")));
    }
    match text.strip_prefix(r"\\?\") {
        Some(disk) if disk.as_bytes().get(1) == Some(&b':') => Ok(PathBuf::from(disk)),
        _ => Ok(full),
    }
}

/// Whether two paths name the same entry as written: byte for byte on Linux, ignoring ASCII case on Windows.
fn same(a: &Path, b: &Path) -> bool {
    if cfg!(windows) {
        a.as_os_str().eq_ignore_ascii_case(b.as_os_str())
    } else {
        a == b
    }
}

fn real(path: &Path, directory: bool) -> Result<PathBuf> {
    // On Windows `absolute` keeps a trailing separator as typed (`.\mc\`), which canonicalize drops; rebuilding
    // from the components drops it too.
    let path: PathBuf = std::path::absolute(path)?.components().collect();
    let metadata = fs::metadata(&path).ok();
    if !canonicalize(&path).is_ok_and(|c| same(&c, &path))
        || !metadata.is_some_and(|m| if directory { m.is_dir() } else { m.is_file() })
    {
        let kind = if directory {
            Kind::RealDir
        } else {
            Kind::RealFile
        };
        bail!(Refusal::new(kind, path.display()));
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

/// Linux: owned by this user and closed to others (mode 0700).
#[cfg(unix)]
fn private(path: &Path) -> Result<()> {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    let metadata = fs::metadata(path)?;
    // SAFETY: getuid has no preconditions and cannot fail.
    if metadata.uid() != unsafe { libc::getuid() } || metadata.permissions().mode() & 0o077 != 0 {
        bail!(Refusal::new(Kind::BridgeMode, path.display()));
    }
    Ok(())
}

/// Windows: on a local fixed NTFS disk, never a network share. Privacy comes from the user profile's inherited ACL,
/// so the folder stays under the profile; shared mappings are coherent only for local files.
#[cfg(windows)]
fn private(path: &Path) -> Result<()> {
    use windows_sys::Win32::Storage::FileSystem::{GetDriveTypeW, GetVolumeInformationW};
    const DRIVE_FIXED: u32 = 3;
    let refused = || Refusal::new(Kind::BridgeVolume, path.display());
    let Some(std::path::Component::Prefix(prefix)) = path.components().next() else {
        bail!(refused());
    };
    let std::path::Prefix::Disk(letter) = prefix.kind() else {
        bail!(refused());
    };
    let root = wide(Path::new(&format!("{}:\\", letter as char)));
    let mut name = [0u16; 32];
    // SAFETY: `root` is NUL-terminated and `name` is valid for its length; the other outputs are optional.
    let ntfs = unsafe {
        GetDriveTypeW(root.as_ptr()) == DRIVE_FIXED
            && GetVolumeInformationW(
                root.as_ptr(),
                std::ptr::null_mut(),
                0,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                name.as_mut_ptr(),
                name.len() as u32,
            ) != 0
    };
    let length = name.iter().position(|&c| c == 0).unwrap_or(name.len());
    if !ntfs || String::from_utf16_lossy(&name[..length]) != "NTFS" {
        bail!(refused());
    }
    Ok(())
}

/// The shared bridge directory: absolute, without symlinks, and private (Linux: this user's, mode 0700; Windows: a
/// local NTFS folder).
pub fn private_dir(path: &Path) -> Result<PathBuf> {
    if !path.is_absolute() {
        bail!(Refusal::new(Kind::BridgeNotAbsolute, path.display()));
    }
    let path = real_dir(path)?;
    private(&path)?;
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
        bail!(Refusal::new(Kind::BridgeNotAbsolute, path.display()));
    }
    // The nearest existing ancestor, a symbolic link included, must be a real directory.
    match path
        .ancestors()
        .skip(1)
        .find(|a| fs::symlink_metadata(a).is_ok())
    {
        Some(ancestor) => {
            real_dir(ancestor)?;
            // Windows: the new folder lands on the ancestor's volume. Linux: only the new folder gets mode 0700.
            if cfg!(windows) {
                private(ancestor)?;
            }
            Ok(())
        }
        None => bail!(Refusal::new(Kind::NoAncestor, path.display())),
    }
}

/// Creates the bridge directory itself, private from the start (Linux: mode 0700; Windows: the inherited ACL).
#[cfg(unix)]
pub fn create_private_dir(path: &Path) -> io::Result<()> {
    std::os::unix::fs::DirBuilderExt::mode(&mut fs::DirBuilder::new(), 0o700).create(path)
}

/// Creates the bridge directory itself, private from the start (Linux: mode 0700; Windows: the inherited ACL).
#[cfg(windows)]
pub fn create_private_dir(path: &Path) -> io::Result<()> {
    fs::create_dir(path)
}

/// Makes a script runnable (`start.sh`).
#[cfg(unix)]
pub fn make_executable(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))
}

/// Nothing to do: Windows runs `.bat` files by their name.
#[cfg(windows)]
pub fn make_executable(_path: &Path) -> io::Result<()> {
    Ok(())
}

/// A child command that opens no window: Windows would give a console program started from the window a console of
/// its own.
pub fn quiet(command: &mut std::process::Command) -> &mut std::process::Command {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}

/// How Steam was installed: Flatpak and Snap Steam run Proton in their own sandbox.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SteamKind {
    Native,
    Flatpak,
    Snap,
}

/// The places a Steam root can be under a home directory, first match first.
#[cfg(unix)]
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

/// Where Steam says it is installed (the registry, read only), then its default folder.
#[cfg(windows)]
pub fn steam_roots(_home: &Path) -> Vec<(PathBuf, SteamKind)> {
    use windows_sys::Win32::System::Registry::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE};
    let mut roots: Vec<(PathBuf, SteamKind)> = [
        (HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
        (
            HKEY_LOCAL_MACHINE,
            r"SOFTWARE\WOW6432Node\Valve\Steam",
            "InstallPath",
        ),
        (HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam", "InstallPath"),
    ]
    .into_iter()
    .filter_map(|(key, subkey, value)| registry_string(key, subkey, value))
    .map(|path| (PathBuf::from(path), SteamKind::Native))
    .collect();
    if let Some(programs) = std::env::var_os("ProgramFiles(x86)") {
        roots.push((PathBuf::from(programs).join("Steam"), SteamKind::Native));
    }
    roots
}

/// A string value of the registry, read only.
#[cfg(windows)]
fn registry_string(
    key: windows_sys::Win32::System::Registry::HKEY,
    subkey: &str,
    value: &str,
) -> Option<String> {
    use windows_sys::Win32::System::Registry::{RRF_RT_REG_SZ, RegGetValueW};
    let text = |s: &str| s.encode_utf16().chain([0]).collect::<Vec<u16>>();
    let (subkey, value) = (text(subkey), text(value));
    let mut buffer = vec![0u16; 1024];
    let mut bytes = (buffer.len() * 2) as u32;
    // SAFETY: the strings are NUL-terminated and `buffer` holds `bytes` bytes; RegGetValueW only reads the key.
    let status = unsafe {
        RegGetValueW(
            key,
            subkey.as_ptr(),
            value.as_ptr(),
            RRF_RT_REG_SZ,
            std::ptr::null_mut(),
            buffer.as_mut_ptr().cast(),
            &mut bytes,
        )
    };
    if status != 0 {
        return None;
    }
    let length = buffer.iter().position(|&c| c == 0).unwrap_or(buffer.len());
    String::from_utf16(&buffer[..length])
        .ok()
        .filter(|s| !s.is_empty())
}

/// Where Prism keeps its data, native first. MultiMC is portable: its folder is picked by hand.
#[cfg(unix)]
pub fn prism_roots(home: &Path) -> Vec<(PathBuf, launcher::Kind)> {
    vec![
        (
            home.join(".local/share/PrismLauncher"),
            launcher::Kind::Prism,
        ),
        (
            home.join(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"),
            launcher::Kind::PrismFlatpak,
        ),
    ]
}

/// Where Prism keeps its data: `%APPDATA%\PrismLauncher`. MultiMC is portable: its folder is picked by hand.
// ponytail: the default %APPDATA% under the profile; a redirected AppData is reached with the manual mods folder.
#[cfg(windows)]
pub fn prism_roots(home: &Path) -> Vec<(PathBuf, launcher::Kind)> {
    vec![(
        home.join(r"AppData\Roaming\PrismLauncher"),
        launcher::Kind::Prism,
    )]
}

/// Where Stellar Blade may keep its `SaveGames`, the likeliest first: in the Proton prefix on Linux. The SteamID64
/// below it is never logged.
#[cfg(unix)]
pub fn save_dirs(prefix: Option<&Path>, _home: &Path) -> Vec<PathBuf> {
    prefix
        .map(|p| p.join("drive_c/users/steamuser/AppData/Local/SB/Saved/SaveGames"))
        .into_iter()
        .collect()
}

/// Where Stellar Blade may keep its saves on Windows, the likeliest first: `%LOCALAPPDATA%\SB\Saved\SaveGames` (what
/// the Proton prefix mirrors), then two folders some guides name (unconfirmed; they are only read). The SteamID64
/// below them is never logged.
#[cfg(windows)]
pub fn save_dirs(_prefix: Option<&Path>, home: &Path) -> Vec<PathBuf> {
    [
        r"AppData\Local\SB\Saved\SaveGames",
        r"Documents\StellarBlade",
        r"AppData\Local\Yaksha\StellarBlade\Save",
    ]
    .into_iter()
    .map(|relative| home.join(relative))
    .collect()
}

/// The memory free for new work, in bytes: `MemAvailable` from `/proc/meminfo`. `None` when unreadable.
#[cfg(unix)]
pub fn available_memory() -> Option<u64> {
    let text = fs::read_to_string("/proc/meminfo").ok()?;
    let line = text.lines().find(|l| l.starts_with("MemAvailable:"))?;
    let kib: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
    kib.checked_mul(1024)
}

/// The memory free for new work, in bytes: the available physical memory. `None` when unreadable.
#[cfg(windows)]
pub fn available_memory() -> Option<u64> {
    use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
    // SAFETY: an all-zero MEMORYSTATUSEX is valid; dwLength is set as the call requires.
    let mut status: MEMORYSTATUSEX = unsafe { std::mem::zeroed() };
    status.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
    // SAFETY: `status` is a valid, writable MEMORYSTATUSEX with its length set.
    (unsafe { GlobalMemoryStatusEx(&mut status) } != 0).then_some(status.ullAvailPhys)
}

/// The user's home directory: `HOME` on Linux, the profile folder (`USERPROFILE`) on Windows.
pub fn home() -> Result<PathBuf> {
    let name = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    match std::env::var_os(name) {
        Some(home) if !home.is_empty() => Ok(PathBuf::from(home)),
        _ => bail!("{name} is not set"),
    }
}

/// Windows: when the window was started on its own (a double click), Windows gave it a console of its own; hide it.
/// A console the program shares (cmd, PowerShell, a terminal) stays, with the help and the server mode's output.
pub fn leave_own_console() {
    #[cfg(windows)]
    {
        use windows_sys::Win32::System::Console::{FreeConsole, GetConsoleProcessList};
        let mut ids = [0u32; 2];
        // SAFETY: `ids` is valid for its length; both calls only query or detach this process's console.
        unsafe {
            if GetConsoleProcessList(ids.as_mut_ptr(), ids.len() as u32) == 1 {
                FreeConsole();
            }
        }
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
    fn a_directory_typed_with_a_trailing_separator_is_real() {
        let root = tempfile::tempdir().unwrap();
        let dir = canonicalize(root.path()).unwrap();
        for typed in [
            format!("{}{}", dir.display(), std::path::MAIN_SEPARATOR),
            format!("{}/", dir.display()),
        ] {
            assert_eq!(real_dir(Path::new(&typed)).unwrap(), dir, "{typed}");
            let folder = crate::server::inspect(Path::new(&typed)).unwrap();
            assert_eq!(folder.layout, crate::server::Layout::New, "{typed}");
        }
    }

    #[cfg(unix)]
    #[test]
    fn private_dir_refuses_shared_and_symlinked_directories() {
        use std::os::unix::fs::PermissionsExt;
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
        let created = canonical.join("created");
        create_private_dir(&created).unwrap();
        assert_eq!(private_dir(&created).unwrap(), created);
    }

    #[cfg(unix)]
    #[test]
    fn processes_read_comm_cmdline_and_cwd() {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir_all(root.path().join("12")).unwrap();
        fs::write(root.path().join("12/comm"), "java\n").unwrap();
        fs::write(root.path().join("12/cmdline"), b"java\0-cp\0x\0").unwrap();
        std::os::unix::fs::symlink("/srv/mc", root.path().join("12/cwd")).unwrap();
        fs::create_dir_all(root.path().join("self")).unwrap();
        fs::create_dir_all(root.path().join("13")).unwrap(); // exited: no comm
        let found = processes(root.path()).unwrap();
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].comm, "java");
        assert_eq!(found[0].cmdline.as_deref(), Some(&b"java\0-cp\0x\0"[..]));
        assert_eq!(found[0].cwd.as_deref(), Some(Path::new("/srv/mc")));
    }

    #[cfg(windows)]
    #[test]
    fn windows_paths_are_plain_ascii_or_short_and_local() {
        let root = tempfile::tempdir().unwrap();
        let base = canonicalize(root.path()).unwrap();
        assert!(!base.to_str().unwrap().starts_with(r"\\?\"), "{base:?}");
        assert!(same(
            &base,
            Path::new(&base.to_str().unwrap().to_uppercase())
        ));
        let ascii = base.join("bridge");
        assert_eq!(game_path(&ascii).unwrap(), ascii.to_str().unwrap());
        assert!(game_path(Path::new(r"\\server\share\bridge")).is_err());
        assert!(game_path(Path::new(r"relative\bridge")).is_err());
        assert!(bridge_ready(&ascii).is_ok());
        create_private_dir(&ascii).unwrap();
        assert_eq!(private_dir(&ascii).unwrap(), ascii);
        assert!(private_dir(Path::new(r"\\server\share")).is_err());
        // A non-ASCII folder is written with its short name, the same string before and after its child exists.
        let accented = base.join("Clément");
        fs::create_dir(&accented).unwrap();
        // A volume that keeps no 8.3 names (fsutil 8dot3name) gives the long name back: the path is refused.
        if !short_path(&accented).is_some_and(|s| s.is_ascii()) {
            let refused = game_path(&accented.join("bridge")).unwrap_err();
            assert_eq!(
                refused.downcast::<Refusal>().unwrap().kind,
                Kind::BridgeAscii
            );
            return;
        }
        let missing = game_path(&accented.join("bridge")).unwrap();
        assert!(
            missing.is_ascii() && missing.ends_with(r"\bridge"),
            "{missing}"
        );
        create_private_dir(&accented.join("bridge")).unwrap();
        let present = game_path(&accented.join("bridge")).unwrap();
        assert!(present.is_ascii(), "{present}");
        fs::write(Path::new(&present).join("probe"), "same folder").unwrap();
        assert_eq!(
            fs::read(accented.join("bridge").join("probe")).unwrap(),
            b"same folder"
        );
    }

    #[cfg(windows)]
    #[test]
    fn the_live_process_list_includes_this_test() {
        let found = processes(Path::new("/proc")).unwrap();
        assert!(
            found
                .iter()
                .any(|p| p.comm.to_lowercase().ends_with(".exe"))
        );
    }
}
