//! The safety net around every write: games closed, protected files unchanged, saves never written, verified copies.

use anyhow::{Context, Result, bail};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use crate::platform;

/// Lower-case hex of a digest (sha2 0.11 digests have no `{:x}`).
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}

/// A file's sha256, reporting the bytes hashed so far (the executable is 359 MB).
pub fn sha256_file(path: &Path, progress: &mut dyn FnMut(u64)) -> io::Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0; 1 << 20];
    let mut done = 0;
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            return Ok(hex(&hasher.finalize()));
        }
        hasher.update(&buffer[..read]);
        done += read as u64;
        progress(done);
    }
}

/// Every entry under `root`, depth first in sorted order, by path relative to it; symbolic links are reported, never
/// followed.
pub(crate) fn walk(
    root: &Path,
    visit: &mut dyn FnMut(&Path, &fs::Metadata) -> Result<()>,
) -> Result<()> {
    fn inner(
        root: &Path,
        dir: &Path,
        visit: &mut dyn FnMut(&Path, &fs::Metadata) -> Result<()>,
    ) -> Result<()> {
        let mut entries = fs::read_dir(dir)?
            .map(|e| e.map(|e| e.path()))
            .collect::<io::Result<Vec<_>>>()?;
        entries.sort();
        for path in entries {
            let metadata = fs::symlink_metadata(&path)?;
            visit(path.strip_prefix(root)?, &metadata)?;
            if metadata.is_dir() {
                inner(root, &path, visit)?;
            }
        }
        Ok(())
    }
    inner(root, root, visit)
}

/// A relative path in the manifests' form: '/'-joined UTF-8.
pub(crate) fn slash(relative: &Path) -> Result<String> {
    let Some(text) = relative.to_str() else {
        bail!("Refusing a non-UTF-8 path: {}", relative.display())
    };
    Ok(text.to_string())
}

/// The sha256 of every regular file under `dir` by relative path: what "unchanged" means for the saves.
pub fn tree(dir: &Path) -> Result<BTreeMap<String, String>> {
    let mut files = BTreeMap::new();
    walk(dir, &mut |relative, metadata| {
        if metadata.is_file() {
            files.insert(
                slash(relative)?,
                sha256_file(&dir.join(relative), &mut |_| {})?,
            );
        }
        Ok(())
    })?;
    Ok(files)
}

/// A process that must not run while the installer writes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Running {
    StellarBlade,
    Minecraft,
    /// Prism or MultiMC: only matters while their instance files change.
    Launcher,
}

const MINECRAFT_MARKERS: &[&[u8]] = &[
    b"org.prismlauncher.EntryPoint",
    b"org.multimc.EntryPoint",
    b"net.minecraft.client.main.Main",
    b"net.fabricmc.loader.impl.launch.knot.KnotClient",
];

/// What runs among `processes`. A Java process whose command line cannot be read counts as Minecraft (fail closed).
pub fn running(processes: &[platform::Process], launchers: bool) -> Vec<Running> {
    let mut found: Vec<Running> = processes
        .iter()
        .filter_map(|process| {
            let comm = process.comm.as_str();
            if comm.starts_with("SB-Win64") || comm == "SB.exe" {
                Some(Running::StellarBlade)
            } else if comm == "java" || comm == "javaw" {
                let cmdline = process.cmdline.as_deref().unwrap_or_default();
                (cmdline.is_empty()
                    || MINECRAFT_MARKERS
                        .iter()
                        .any(|m| cmdline.windows(m.len()).any(|w| w == *m)))
                .then_some(Running::Minecraft)
            } else if launchers && (comm == "prismlauncher" || comm.starts_with("MultiMC")) {
                Some(Running::Launcher)
            } else {
                None
            }
        })
        .collect();
    found.sort();
    found.dedup();
    found
}

/// What runs now among the processes under `proc_root` (`/proc` outside tests).
pub fn running_at(proc_root: &Path, launchers: bool) -> Result<Vec<Running>> {
    let processes = platform::processes(proc_root)
        .context("Cannot list processes to check that the games are closed")?;
    Ok(running(&processes, launchers))
}

/// Refuses while Stellar Blade or Minecraft runs, and the launchers too when `launchers` is set. Call it right before
/// every write.
pub fn games_closed(launchers: bool) -> Result<()> {
    closed_at(Path::new("/proc"), launchers)
}

/// [`games_closed`] with the process list read under `proc_root`.
pub fn closed_at(proc_root: &Path, launchers: bool) -> Result<()> {
    let found = running_at(proc_root, launchers)?;
    if found.is_empty() {
        return Ok(());
    }
    let names: Vec<&str> = found
        .iter()
        .map(|r| match r {
            Running::StellarBlade => "Stellar Blade",
            Running::Minecraft => "Minecraft",
            Running::Launcher => "the Minecraft launcher",
        })
        .collect();
    bail!("Close {} first", names.join(", "))
}

/// Files and folders the installer must leave exactly as they were. Each location is optional: an EVE player has all
/// of them, a viewer only Minecraft's.
#[derive(Debug, Default, Clone)]
pub struct Protected {
    /// `SB/Binaries/Win64`: the executable, other loaders (`dwmapi.dll`, `dinput8.dll`) and every other top-level file.
    pub win64: Option<PathBuf>,
    /// UE4SS's own folder (`Win64/ue4ss`, or `Win64` in the old layout).
    pub ue4ss: Option<PathBuf>,
    /// The UE4SS `Mods` folder: its lists and the other mods' folder names.
    pub mods: Option<PathBuf>,
    /// The Proton prefix (`pfx`): its registry files.
    pub prefix: Option<PathBuf>,
    /// The instance's game folder (`minecraft/`): `options.txt` and the other mods.
    pub minecraft: Option<PathBuf>,
    /// File names in `minecraft/mods` that this install owns and may change.
    pub own_jars: Vec<String>,
}

/// Hashes of the protected files and listings of the protected folders, by path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot(pub BTreeMap<String, String>);

const ADAPTER_FILES: &[&str] = &[
    "xinput1_3.dll",
    "stellar-input-directory.txt",
    ".stellar-input-deployment.json",
];
const UE4SS_FILES: &[&str] = &[
    "UE4SS.dll",
    "UE4SS-settings.ini",
    "VTableLayout.ini",
    "MemberVariableLayout.ini",
];

impl Protected {
    pub fn snapshot(&self) -> Result<Snapshot> {
        let mut entries = BTreeMap::new();
        let mut file = |path: PathBuf| -> Result<()> {
            let value = match fs::symlink_metadata(&path) {
                Err(e) if e.kind() == io::ErrorKind::NotFound => "missing".to_string(),
                Err(e) => return Err(e.into()),
                Ok(m) if m.is_file() => sha256_file(&path, &mut |_| {})?,
                Ok(_) => "not a regular file".to_string(),
            };
            entries.insert(path.display().to_string(), value);
            Ok(())
        };
        // A listing of names in `dir`, minus those this install owns.
        let listing = |dir: &Path, skip: &dyn Fn(&str) -> bool| -> Result<(PathBuf, Vec<String>)> {
            let mut names = Vec::new();
            match fs::read_dir(dir) {
                Err(e) if e.kind() == io::ErrorKind::NotFound => {}
                Err(e) => return Err(e.into()),
                Ok(read) => {
                    for entry in read {
                        let name = entry?.file_name().to_string_lossy().into_owned();
                        if !skip(&name) {
                            names.push(name);
                        }
                    }
                }
            }
            names.sort();
            Ok((dir.to_path_buf(), names))
        };
        let mut listings = Vec::new();
        if let Some(win64) = &self.win64 {
            let own = |name: &str| ADAPTER_FILES.contains(&name.to_lowercase().as_str());
            let (dir, names) = listing(win64, &own)?;
            for name in &names {
                if dir.join(name).is_file() {
                    file(dir.join(name))?;
                }
            }
            listings.push((dir, names));
        }
        if let Some(ue4ss) = &self.ue4ss {
            for name in UE4SS_FILES {
                file(ue4ss.join(name))?;
            }
            let (dir, names) = listing(&ue4ss.join("UE4SS_Signatures"), &|_| false)?;
            for name in &names {
                file(dir.join(name))?;
            }
            listings.push((dir, names));
        }
        if let Some(mods) = &self.mods {
            file(mods.join("mods.txt"))?;
            file(mods.join("mods.json"))?;
            listings.push(listing(mods, &|name| {
                name == crate::deploy::MOD_NAME || name == crate::deploy::STAGING
            })?);
        }
        if let Some(prefix) = &self.prefix {
            for name in ["user.reg", "system.reg", "userdef.reg"] {
                file(prefix.join(name))?;
            }
        }
        if let Some(minecraft) = &self.minecraft {
            file(minecraft.join("options.txt"))?;
            let (dir, names) = listing(&minecraft.join("mods"), &|name| {
                self.own_jars.iter().any(|own| own == name)
            })?;
            for name in &names {
                if name.ends_with(".jar") {
                    file(dir.join(name))?;
                }
            }
            listings.push((dir, names));
        }
        for (dir, names) in listings {
            entries.insert(format!("{}/ (names)", dir.display()), names.join("\n"));
        }
        Ok(Snapshot(entries))
    }
}

impl Snapshot {
    /// The protected paths that differ in `after`.
    pub fn changed(&self, after: &Snapshot) -> Vec<String> {
        let keys: std::collections::BTreeSet<&String> =
            self.0.keys().chain(after.0.keys()).collect();
        keys.into_iter()
            .filter(|key| self.0.get(*key) != after.0.get(*key))
            .cloned()
            .collect()
    }
}

/// Copies `source` (a file or a folder without symbolic links) to the new path `target`.
pub fn copy_tree(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.is_file() {
        fs::copy(source, target)?;
        return Ok(());
    }
    if !metadata.is_dir() {
        bail!(
            "Refusing to copy a symbolic link or special file: {}",
            source.display()
        );
    }
    fs::create_dir(target)?;
    copy_contents(source, target)
}

/// Copies what the folder `source` holds into the existing folder `target`, refusing symbolic links.
fn copy_contents(source: &Path, target: &Path) -> Result<()> {
    walk(source, &mut |relative, metadata| {
        if metadata.is_dir() {
            fs::create_dir(target.join(relative))?;
        } else if metadata.is_file() {
            fs::copy(source.join(relative), target.join(relative))?;
        } else {
            bail!(
                "Refusing to copy a symbolic link or special file: {}",
                source.join(relative).display()
            );
        }
        Ok(())
    })
}

/// Copies the Stellar Blade `SaveGames` folder into `backups/<unix ms>-SaveGames` and checks the copy against the
/// source. Reads the saves only; a failed or differing copy is deleted. Never restored automatically: Steam Cloud may
/// sync over a restore, so restoring is the user's act with Steam closed.
pub fn backup_saves(saves: &Path, backups: &Path) -> Result<PathBuf> {
    let before = tree(saves)?;
    fs::create_dir_all(backups)?;
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_millis();
    let target = backups.join(format!("{stamp}-SaveGames"));
    // Created here, so only this copy is ever deleted on failure.
    fs::create_dir(&target)?;
    let copied = copy_contents(saves, &target).and_then(|()| {
        if tree(&target)? != before || tree(saves)? != before {
            bail!("The save backup differs from the saves");
        }
        Ok(())
    });
    if let Err(error) = copied {
        let _ = fs::remove_dir_all(&target);
        return Err(error);
    }
    Ok(target)
}

#[cfg(test)]
mod tests {
    use super::*;
    use platform::Process;

    fn process(comm: &str, cmdline: Option<&[u8]>) -> Process {
        Process {
            comm: comm.into(),
            cmdline: cmdline.map(<[u8]>::to_vec),
        }
    }

    #[test]
    fn running_matches_recorded_command_lines_and_fails_closed() {
        let prism = process(
            "java",
            Some(b"/usr/bin/java\0-Xmx6000m\0org.prismlauncher.EntryPoint\0"),
        );
        let knot = process(
            "java",
            Some(b"java\0net.fabricmc.loader.impl.launch.knot.KnotClient\0"),
        );
        let gradle = process(
            "java",
            Some(b"java\0org.gradle.launcher.daemon.bootstrap.GradleDaemon\0"),
        );
        let unreadable = process("java", None);
        let game = process("SB-Win64-Shippi", Some(b""));
        let bootstrap = process("SB.exe", Some(b""));
        let launcher = process("prismlauncher", Some(b"prismlauncher\0"));
        assert_eq!(running(&[gradle], true), vec![]);
        assert_eq!(running(&[prism], false), vec![Running::Minecraft]);
        assert_eq!(running(&[knot], false), vec![Running::Minecraft]);
        assert_eq!(running(&[unreadable], false), vec![Running::Minecraft]);
        assert_eq!(
            running(&[game, bootstrap], false),
            vec![Running::StellarBlade]
        );
        assert_eq!(running(std::slice::from_ref(&launcher), false), vec![]);
        assert_eq!(running(&[launcher], true), vec![Running::Launcher]);
    }

    #[test]
    fn snapshot_reports_changed_protected_files_and_new_names_only() {
        let root = tempfile::tempdir().unwrap();
        let win64 = root.path().join("Win64");
        let mods = win64.join("ue4ss/Mods");
        let minecraft = root.path().join("minecraft");
        fs::create_dir_all(mods.join("OtherMod")).unwrap();
        fs::create_dir_all(minecraft.join("mods")).unwrap();
        fs::write(win64.join("dwmapi.dll"), "ue4ss").unwrap();
        fs::write(win64.join("SB-Win64-Shipping.exe"), "exe").unwrap();
        fs::write(mods.join("mods.txt"), "OtherMod : 1\n").unwrap();
        fs::write(minecraft.join("options.txt"), "fov:0.5\n").unwrap();
        fs::write(minecraft.join("mods/sodium.jar"), "sodium").unwrap();
        let protected = Protected {
            win64: Some(win64.clone()),
            ue4ss: Some(win64.join("ue4ss")),
            mods: Some(mods.clone()),
            minecraft: Some(minecraft.clone()),
            own_jars: vec!["stellar-bridge-0.1.0.jar".into()],
            ..Protected::default()
        };
        let before = protected.snapshot().unwrap();
        // Our own files may change.
        fs::write(win64.join("xinput1_3.dll"), "ours").unwrap();
        fs::create_dir(mods.join("StellarCollisionProbe")).unwrap();
        fs::write(minecraft.join("mods/stellar-bridge-0.1.0.jar"), "ours").unwrap();
        assert_eq!(
            before.changed(&protected.snapshot().unwrap()),
            Vec::<String>::new()
        );
        fs::write(win64.join("dwmapi.dll"), "replaced").unwrap();
        fs::write(win64.join("dinput8.dll"), "new loader").unwrap();
        fs::write(minecraft.join("mods/sodium.jar"), "changed").unwrap();
        fs::create_dir(mods.join("NewMod")).unwrap();
        let changed = before.changed(&protected.snapshot().unwrap());
        let ends: Vec<_> = [
            "Win64/dinput8.dll",
            "Win64/dwmapi.dll",
            "Win64/ (names)",
            "Mods/ (names)",
            "mods/sodium.jar",
        ]
        .into_iter()
        .filter(|end| changed.iter().any(|c| c.ends_with(end)))
        .collect();
        assert_eq!(ends.len(), 5, "{changed:?}");
        assert_eq!(changed.len(), 5, "{changed:?}");
    }

    #[test]
    fn save_backup_is_a_verified_copy_and_leaves_the_saves_alone() {
        let root = tempfile::tempdir().unwrap();
        let saves = root.path().join("SaveGames/76561190000000000");
        fs::create_dir_all(saves.join("Backup")).unwrap();
        fs::write(saves.join("StellarBladeSave00.sav"), "save").unwrap();
        fs::write(saves.join("Backup/old.sav"), "older").unwrap();
        let saves = saves.parent().unwrap();
        let before = tree(saves).unwrap();
        let copy = backup_saves(saves, &root.path().join("data/save-backups")).unwrap();
        assert_eq!(tree(&copy).unwrap(), before);
        assert_eq!(tree(saves).unwrap(), before);
        std::os::unix::fs::symlink("/etc/hostname", saves.join("link")).unwrap();
        assert!(backup_saves(saves, &root.path().join("data/save-backups")).is_err());
        assert_eq!(
            fs::read_dir(root.path().join("data/save-backups"))
                .unwrap()
                .count(),
            1
        );
    }
}
