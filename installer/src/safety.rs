//! The safety net around every write: games closed, protected files unchanged, saves never written, verified copies.

use anyhow::{Context, Result, bail};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use crate::{Kind, Refusal, platform};

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

/// A relative path in the manifests' form: '/'-joined UTF-8, as the Python tools write it on Linux.
pub(crate) fn slash(relative: &Path) -> Result<String> {
    let Some(text) = relative.to_str() else {
        bail!(Refusal::new(Kind::NonUtf8, relative.display()))
    };
    // A Windows file name never holds '\'; a Linux one may, and keeps it.
    Ok(if cfg!(windows) {
        text.replace('\\', "/")
    } else {
        text.to_string()
    })
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

/// A Java process: `java`/`javaw` on Linux, `java.exe`/`javaw.exe` on Windows.
fn is_java(comm: &str) -> bool {
    matches!(comm, "java" | "javaw")
        || comm.eq_ignore_ascii_case("java.exe")
        || comm.eq_ignore_ascii_case("javaw.exe")
}

fn holds(cmdline: &[u8], markers: &[&[u8]]) -> bool {
    markers
        .iter()
        .any(|m| cmdline.windows(m.len()).any(|w| w == *m))
}

/// What runs among `processes`. A Java process whose command line cannot be read counts as Minecraft (fail closed).
pub fn running(processes: &[platform::Process], launchers: bool) -> Vec<Running> {
    let mut found: Vec<Running> = processes
        .iter()
        .filter_map(|process| {
            let comm = process.comm.as_str();
            if comm.starts_with("SB-Win64") || comm == "SB.exe" {
                Some(Running::StellarBlade)
            } else if is_java(comm) {
                let cmdline = process.cmdline.as_deref().unwrap_or_default();
                (cmdline.is_empty() || holds(cmdline, MINECRAFT_MARKERS))
                    .then_some(Running::Minecraft)
            } else if launchers
                && (comm == "prismlauncher"
                    || comm.eq_ignore_ascii_case("prismlauncher.exe")
                    || comm.starts_with("MultiMC"))
            {
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
    let processes = platform::processes(proc_root).context(Refusal::new(Kind::ProcessList, ""))?;
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
    // Names only, the same in every language.
    let names: Vec<&str> = found
        .iter()
        .map(|r| match r {
            Running::StellarBlade => "Stellar Blade",
            Running::Minecraft => "Minecraft",
            Running::Launcher => "Prism Launcher / MultiMC",
        })
        .collect();
    bail!(Refusal::new(Kind::CloseFirst, names.join(", ")))
}

const SERVER_MARKERS: &[&[u8]] = &[
    b"net.fabricmc.loader.impl.launch.knot.KnotServer",
    b"net.fabricmc.installer.ServerLauncher",
    b"fabric-server-launch",
    b"fabric-server-mc.",
];

/// Whether a Minecraft server runs in the server folder `dir` (canonical): a Java process working in it. A Java process
/// whose working folder cannot be read counts when its command line looks like a server or cannot be read either
/// (fail closed).
pub fn server_running(processes: &[platform::Process], dir: &Path) -> bool {
    processes.iter().any(|process| {
        is_java(&process.comm)
            && match &process.cwd {
                Some(cwd) => platform::canonicalize(cwd)
                    .unwrap_or_else(|_| cwd.clone())
                    .starts_with(dir),
                None => process
                    .cmdline
                    .as_deref()
                    .is_none_or(|c| c.is_empty() || holds(c, SERVER_MARKERS)),
            }
    })
}

/// Refuses while a Minecraft server runs in `dir`, with the process list read under `proc_root` (`/proc` on Linux).
/// Call it right before every write in a server folder.
pub fn server_closed_at(proc_root: &Path, dir: &Path) -> Result<()> {
    let processes = platform::processes(proc_root).context(Refusal::new(Kind::ProcessList, ""))?;
    let dir = platform::canonicalize(dir).unwrap_or_else(|_| dir.to_path_buf());
    if server_running(&processes, &dir) {
        bail!(Refusal::new(Kind::ServerRunning, dir.display()));
    }
    Ok(())
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

/// `name`, or the name a temporary of a write through `mods::write_atomic` stands for: a run killed before its rename
/// leaves one, and the next write replaces it.
fn own_name(name: &str) -> &str {
    name.strip_prefix('.')
        .and_then(|n| n.strip_suffix(".part"))
        .unwrap_or(name)
}

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
            let own = |name: &str| {
                let name = name.to_lowercase();
                ADAPTER_FILES.contains(&own_name(&name))
            };
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
                self.own_jars.iter().any(|own| own == own_name(name))
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
        bail!(Refusal::new(Kind::NotRegular, source.display()));
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
            bail!(Refusal::new(
                Kind::NotRegular,
                source.join(relative).display()
            ));
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
            bail!(Refusal::new(Kind::SaveBackupDiffers, target.display()));
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
            cwd: None,
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
        // Windows image names.
        let javaw = process(
            "javaw.exe",
            Some(b"C:\\Java\\bin\\javaw.exe\0org.prismlauncher.EntryPoint"),
        );
        let elevated = process("java.exe", None);
        let game = process("SB-Win64-Shipping.exe", Some(b"SB-Win64-Shipping.exe"));
        let prism = process("prismlauncher.exe", Some(b"prismlauncher.exe"));
        assert_eq!(running(&[javaw], false), vec![Running::Minecraft]);
        assert_eq!(running(&[elevated], false), vec![Running::Minecraft]);
        assert_eq!(running(&[game], false), vec![Running::StellarBlade]);
        assert_eq!(running(&[prism], true), vec![Running::Launcher]);
    }

    #[test]
    fn a_server_runs_where_its_java_works_and_unknown_ones_fail_closed() {
        let root = tempfile::tempdir().unwrap();
        let dir = platform::canonicalize(root.path()).unwrap();
        let at = |cwd: &Path, cmdline: Option<&[u8]>| Process {
            cwd: Some(cwd.to_path_buf()),
            ..process("java", cmdline)
        };
        let server = at(&dir, Some(b"java\0-jar\0fabric-server-launch.jar\0nogui"));
        let inside = at(&dir.join("world"), Some(b"java\0-jar\0server.jar"));
        let client = at(&dir.with_file_name("elsewhere"), Some(b"java\0KnotClient"));
        let hidden_server = process("java.exe", Some(b"java\0-jar\0fabric-server-launch.jar"));
        let hidden_other = process("java", Some(b"java\0-jar\0jenkins.war"));
        let unreadable = process("javaw.exe", None);
        let not_java = Process {
            cwd: Some(dir.clone()),
            ..process("bash", Some(b"bash"))
        };
        assert!(server_running(&[server], &dir));
        assert!(server_running(&[inside], &dir));
        assert!(!server_running(&[client], &dir));
        assert!(server_running(&[hidden_server], &dir));
        assert!(!server_running(&[hidden_other], &dir));
        assert!(server_running(&[unreadable], &dir));
        assert!(!server_running(&[not_java], &dir));
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
        let changed: Vec<String> = before
            .changed(&protected.snapshot().unwrap())
            .iter()
            .map(|c| c.replace('\\', "/"))
            .collect();
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
        #[cfg(unix)]
        {
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
}
