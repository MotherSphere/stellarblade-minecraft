//! The game-local XInput adapter: a port of `tools/input_proxy.py`. It writes and checks the same
//! `.stellar-input-deployment.json`, so this installer and the Python tool each remove what the other installed. It
//! never changes other loaders, overrides or launch options.

use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use crate::safety::{sha256_file, sha256_hex};
use crate::{Guard, Kind, Ownership, Refusal, platform};

pub const MANIFEST: &str = ".stellar-input-deployment.json";
pub const DLL: &str = "xinput1_3.dll";
pub const CONFIG: &str = "stellar-input-directory.txt";
pub const EXE: &str = "SB-Win64-Shipping.exe";
const OWNER: &str = "stellar-input";
const MAX_DLL: usize = 4 * 1024 * 1024;

/// `.stellar-input-deployment.json`, compared parsed.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub owner: String,
    pub version: u32,
    /// The executable pin the adapter was installed for.
    pub executable: String,
    pub files: BTreeMap<String, String>,
}

fn game_directory(exe: &Path) -> Result<PathBuf> {
    let exe = platform::real_file(exe)?;
    if exe.file_name().and_then(|n| n.to_str()) != Some(EXE) {
        bail!(Refusal::new(Kind::ExpectedExe, exe.display()));
    }
    Ok(exe.parent().expect("a file has a parent").to_path_buf())
}

/// The production adapter only: an x64 Windows DLL within the size limit, never the isolated fake-host build.
pub fn check_dll(data: &[u8]) -> Result<()> {
    if data.len() > MAX_DLL {
        bail!("Input adapter exceeds size limit");
    }
    let marker: Vec<u8> = "stellar_input_fake_host.exe"
        .encode_utf16()
        .flat_map(u16::to_le_bytes)
        .collect();
    if data.windows(marker.len()).any(|w| w == marker) {
        bail!("The isolated fake-host adapter must never be installed in Stellar Blade");
    }
    let u16_at = |at: usize| {
        data.get(at..at + 2)
            .map(|b| u16::from_le_bytes([b[0], b[1]]))
    };
    let pe = if data.len() >= 64 {
        u32::from_le_bytes(data[0x3c..0x40].try_into().unwrap()) as usize
    } else {
        data.len()
    };
    let valid = data.starts_with(b"MZ")
        && pe.checked_add(24).is_some_and(|end| end <= data.len())
        && data[pe..pe + 4] == *b"PE\0\0"
        && u16_at(pe + 4) == Some(0x8664)
        && u16_at(pe + 22).is_some_and(|flags| flags & 0x2000 != 0);
    if !valid {
        bail!("Input adapter must be an x64 Windows DLL");
    }
    Ok(())
}

/// Creates `path`, which must not exist; a failed write or flush removes it.
fn write_new(path: &Path, data: &[u8]) -> Result<()> {
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)?;
    if let Err(error) = file.write_all(data).and_then(|()| file.sync_all()) {
        drop(file);
        fs::remove_file(path)?;
        return Err(error.into());
    }
    Ok(())
}

/// Installs the adapter beside `exe` when the executable matches `pin`. Writes the manifest first, then the directory
/// file, then the DLL (activation last), re-checking `closed` before each; any failure removes what it created.
pub fn install(exe: &Path, dll: &[u8], bridge: &Path, pin: &str, closed: Guard) -> Result<PathBuf> {
    let destination = game_directory(exe)?;
    closed()?;
    if sha256_file(exe, &mut |_| {})? != pin {
        bail!(Refusal::new(Kind::UnsupportedExe, exe.display()));
    }
    check_dll(dll)?;
    let directory = directory_file(bridge)?;
    for entry in fs::read_dir(&destination)? {
        let found = entry?.file_name();
        let name = found.to_string_lossy().to_lowercase();
        if [DLL, CONFIG, MANIFEST].contains(&name.as_str()) {
            bail!(Refusal::new(
                Kind::ForeignAdapter,
                destination.join(found).display()
            ));
        }
    }
    let outputs = [(CONFIG, directory.as_slice()), (DLL, dll)];
    let manifest = Manifest {
        owner: OWNER.into(),
        version: 1,
        executable: pin.into(),
        files: outputs
            .iter()
            .map(|(name, data)| (name.to_string(), sha256_hex(data)))
            .collect(),
    };
    let mut created = Vec::new();
    let mut written = || -> Result<()> {
        let path = destination.join(MANIFEST);
        write_new(
            &path,
            (serde_json::to_string_pretty(&manifest)? + "\n").as_bytes(),
        )?;
        created.push(path);
        for (name, data) in outputs {
            closed()?;
            let path = destination.join(name);
            write_new(&path, data)?;
            created.push(path);
        }
        Ok(())
    };
    if let Err(error) = written() {
        for path in created.iter().rev() {
            fs::remove_file(path)?;
        }
        return Err(error);
    }
    Ok(destination.join(MANIFEST))
}

/// The directory file's content: the bridge directory as the game sees it.
pub fn directory_file(bridge: &Path) -> Result<Vec<u8>> {
    Ok((platform::game_path(&platform::private_dir(bridge)?)? + "\n").into_bytes())
}

/// The adapter's manifest in `win64`, when it is ours and was made for the executable `pin`.
pub fn manifest(win64: &Path, pin: &str) -> Result<Manifest> {
    let unknown = || Refusal::new(Kind::UnknownManifest, win64.join(MANIFEST).display());
    let Ok(path) = platform::real_file(&win64.join(MANIFEST)) else {
        bail!(unknown());
    };
    let data = fs::read(&path)?;
    if data.len() > 4096 {
        bail!(unknown());
    }
    match serde_json::from_slice::<Manifest>(&data) {
        Ok(manifest)
            if manifest.owner == OWNER
                && manifest.version == 1
                && manifest.executable == pin
                && manifest.files.keys().map(String::as_str).eq([CONFIG, DLL]) =>
        {
            Ok(manifest)
        }
        _ => bail!(unknown()),
    }
}

/// The listed files whose content is missing or differs, with the sha256 the manifest expects. A listed path that is
/// not a regular file cannot be repaired.
pub fn drifted(win64: &Path, pin: &str) -> Result<Vec<(String, String)>> {
    let mut drift = Vec::new();
    for (name, expected) in manifest(win64, pin)?.files {
        let path = win64.join(&name);
        match fs::symlink_metadata(&path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => drift.push((name, expected)),
            Err(e) => return Err(e.into()),
            Ok(m) if !m.is_file() => bail!(Refusal::new(Kind::NotRegular, path.display())),
            Ok(_) if sha256_file(&path, &mut |_| {})? != expected => drift.push((name, expected)),
            Ok(_) => {}
        }
    }
    Ok(drift)
}

/// Who owns the adapter files in the executable's folder `win64`, for an install made against `pin`.
pub fn ownership(win64: &Path, pin: &str) -> Result<Ownership> {
    let manifest_path = win64.join(MANIFEST);
    if !manifest_path.exists() && !manifest_path.is_symlink() {
        for entry in fs::read_dir(win64)? {
            let found = entry?.file_name();
            let name = found.to_string_lossy().to_lowercase();
            if name == DLL || name == CONFIG {
                return Ok(Ownership::Foreign(Refusal::new(
                    Kind::ForeignAdapter,
                    win64.join(found).display(),
                )));
            }
        }
        return Ok(Ownership::Absent);
    }
    Ok(match drifted(win64, pin) {
        Ok(drift) => match drift.first() {
            None => Ownership::Owned,
            Some((name, _)) => Ownership::Modified(Refusal::new(
                Kind::ModifiedAdapter,
                win64.join(name).display(),
            )),
        },
        Err(error) => {
            Ownership::Modified(crate::refusal(error, Kind::ModifiedAdapter, &manifest_path))
        }
    })
}

/// Removes the adapter only when every file matches its manifest: the DLL first, then its directory file, then the
/// manifest.
pub fn remove(exe: &Path, pin: &str, closed: Guard) -> Result<PathBuf> {
    let destination = game_directory(exe)?;
    closed()?;
    match ownership(&destination, pin)? {
        Ownership::Owned => {
            for name in [DLL, CONFIG, MANIFEST] {
                fs::remove_file(destination.join(name))?;
            }
            Ok(destination)
        }
        Ownership::Absent => bail!(Refusal::new(Kind::NothingToRemove, destination.display())),
        Ownership::Foreign(refusal) | Ownership::Modified(refusal) => bail!(refusal),
    }
}

/// A minimal x64 DLL header, as the Python tests build it.
#[doc(hidden)]
pub fn test_dll() -> Vec<u8> {
    let mut pe = vec![0u8; 256];
    pe[..2].copy_from_slice(b"MZ");
    pe[0x3c..0x40].copy_from_slice(&128u32.to_le_bytes());
    pe[128..132].copy_from_slice(b"PE\0\0");
    pe[132..134].copy_from_slice(&0x8664u16.to_le_bytes());
    pe[150..152].copy_from_slice(&0x2000u16.to_le_bytes());
    pe
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn open() -> Result<()> {
        Ok(())
    }

    struct Game {
        _root: tempfile::TempDir,
        dir: PathBuf,
        exe: PathBuf,
        bridge: PathBuf,
        pin: String,
    }

    fn game() -> Game {
        let root = tempfile::tempdir().unwrap();
        let base = fs::canonicalize(root.path()).unwrap();
        let dir = base.join("game");
        fs::create_dir(&dir).unwrap();
        let exe = dir.join(EXE);
        fs::write(&exe, b"test executable").unwrap();
        fs::write(dir.join("dinput8.dll"), b"preserve Xion").unwrap();
        fs::write(dir.join("dwmapi.dll"), b"preserve UE4SS").unwrap();
        let bridge = base.join("bridge");
        fs::create_dir(&bridge).unwrap();
        fs::set_permissions(&bridge, fs::Permissions::from_mode(0o700)).unwrap();
        Game {
            _root: root,
            dir,
            exe,
            bridge,
            pin: sha256_hex(b"test executable"),
        }
    }

    fn names(dir: &Path) -> Vec<String> {
        let mut names: Vec<_> = fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        names.sort();
        names
    }

    #[test]
    fn round_trip_preserves_other_loaders() {
        let g = game();
        install(&g.exe, &test_dll(), &g.bridge, &g.pin, &open).unwrap();
        assert_eq!(fs::read(g.dir.join(DLL)).unwrap(), test_dll());
        assert_eq!(
            fs::read_to_string(g.dir.join(CONFIG)).unwrap(),
            format!("Z:{}\n", g.bridge.display())
        );
        assert_eq!(ownership(&g.dir, &g.pin).unwrap(), Ownership::Owned);
        remove(&g.exe, &g.pin, &open).unwrap();
        assert_eq!(names(&g.dir), vec![EXE, "dinput8.dll", "dwmapi.dll"]);
        assert_eq!(
            fs::read(g.dir.join("dwmapi.dll")).unwrap(),
            b"preserve UE4SS"
        );
    }

    #[test]
    fn foreign_or_changed_files_are_never_overwritten_or_removed() {
        let g = game();
        fs::write(g.dir.join("XInput1_3.dll"), b"foreign").unwrap();
        assert!(install(&g.exe, &test_dll(), &g.bridge, &g.pin, &open).is_err());
        assert!(matches!(
            ownership(&g.dir, &g.pin).unwrap(),
            Ownership::Foreign(_)
        ));
        fs::remove_file(g.dir.join("XInput1_3.dll")).unwrap();
        install(&g.exe, &test_dll(), &g.bridge, &g.pin, &open).unwrap();
        fs::write(g.dir.join(DLL), b"changed").unwrap();
        assert!(matches!(
            ownership(&g.dir, &g.pin).unwrap(),
            Ownership::Modified(_)
        ));
        assert!(remove(&g.exe, &g.pin, &open).is_err());
        assert_eq!(fs::read(g.dir.join(DLL)).unwrap(), b"changed");
        assert!(matches!(
            ownership(&g.dir, &"0".repeat(64)).unwrap(),
            Ownership::Modified(_)
        ));
    }

    #[test]
    fn unsupported_game_or_dll_shared_bridge_and_live_game_fail_before_writes() {
        let g = game();
        let running = || -> Result<()> { bail!("Close Stellar Blade first") };
        assert!(install(&g.exe, &test_dll(), &g.bridge, &g.pin, &running).is_err());
        assert!(install(&g.exe, &test_dll(), &g.bridge, &"0".repeat(64), &open).is_err());
        assert!(install(&g.exe, b"not a DLL", &g.bridge, &g.pin, &open).is_err());
        let mut fake_host = test_dll();
        fake_host.extend(
            "stellar_input_fake_host.exe"
                .encode_utf16()
                .flat_map(u16::to_le_bytes),
        );
        assert!(install(&g.exe, &fake_host, &g.bridge, &g.pin, &open).is_err());
        fs::set_permissions(&g.bridge, fs::Permissions::from_mode(0o755)).unwrap();
        assert!(install(&g.exe, &test_dll(), &g.bridge, &g.pin, &open).is_err());
        assert_eq!(names(&g.dir).len(), 3);
    }

    #[test]
    fn game_starting_midway_rolls_back_only_new_files() {
        let g = game();
        let calls = std::cell::Cell::new(0);
        let starts = || -> Result<()> {
            calls.set(calls.get() + 1);
            if calls.get() > 2 {
                bail!("Stellar Blade started during installation")
            } else {
                Ok(())
            }
        };
        assert!(install(&g.exe, &test_dll(), &g.bridge, &g.pin, &starts).is_err());
        assert_eq!(names(&g.dir), vec![EXE, "dinput8.dll", "dwmapi.dll"]);
    }
}
