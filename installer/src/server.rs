//! Server folder mode: the bridge JAR and Fabric API in the `mods` folder of a Fabric server for Minecraft 1.21.1,
//! either a server folder set up by other means or a new one made with the embedded Fabric server launcher. Nothing
//! here starts the server or downloads anything.
//!
//! Mojang's EULA stays the operator's act: `eula.txt` is written only when they ask for it after reading
//! [`pins::MINECRAFT_EULA`], and never changed once it exists. `server.properties` and the worlds are never written, so
//! `online-mode` keeps its value (`true` on a new server, as Minecraft writes it at the first start).

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::ffi::OsStr;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

use crate::payload::Payload;
use crate::safety::sha256_hex;
use crate::{Guard, Kind, Ownership, Refusal, launcher, mods, ops, pins, platform};

/// The launcher's name in a new server folder, the name Fabric's own installer gives it.
pub const LAUNCHER: &str = "fabric-server-launch.jar";
pub const MANIFEST: &str = ".stellar-bridge-server.json";
const OWNER: &str = "stellar-bridge-server";

/// The embedded launcher; checked against [`pins::FABRIC_SERVER_LAUNCHER_SHA256`] before it is written.
static LAUNCHER_JAR: &[u8] = include_bytes!("../vendor/fabric-server/fabric-server-launch.jar");
/// Fabric Installer's licence, printed by `--licenses`.
pub const LAUNCHER_LICENSE: &str = include_str!("../vendor/fabric-server/LICENSE");
pub const LAUNCHER_NOTICE: &str = include_str!("../vendor/fabric-server/NOTICE");

const START_SH: &str = "#!/bin/sh\n\
# Starts this Fabric server for Minecraft 1.21.1. Accept Mojang's EULA in eula.txt first.\n\
cd \"$(dirname \"$0\")\" || exit 1\n\
exec java -Xmx2G -jar fabric-server-launch.jar nogui \"$@\"\n";
const START_BAT: &str = "@echo off\r\n\
rem Starts this Fabric server for Minecraft 1.21.1. Accept Mojang's EULA in eula.txt first.\r\n\
cd /d \"%~dp0\"\r\n\
java -Xmx2G -jar fabric-server-launch.jar nogui %*\r\n\
pause\r\n";
const EULA: &str = "# Accepted by the server operator in stellar-bridge-installer, after reading \
https://aka.ms/MinecraftEULA\neula=true\n";

/// `.stellar-bridge-server.json`: what the installer created at the root of a new server folder, with sha256. The
/// bridge files in `mods/` have their own manifest ([`mods::MANIFEST`]).
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub owner: String,
    pub version: u32,
    pub files: BTreeMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Layout {
    /// Missing or empty: made from the embedded launcher.
    New,
    /// Made by this installer: its manifest is there.
    Ours,
    /// A Fabric server for Minecraft 1.21.1 set up by other means, with its Fabric Loader version when it shows.
    Existing { loader: Option<String> },
}

/// What a server folder holds, read only.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Folder {
    /// Absolute, links resolved when it exists.
    pub dir: PathBuf,
    pub layout: Layout,
    /// What `eula.txt` says; `None` without the file.
    pub eula: Option<bool>,
    /// `online-mode` in `server.properties`; `None` without the file or the key.
    pub online_mode: Option<bool>,
    /// The bridge files in `mods/`: absent, or owned (foreign and modified ones are refused).
    pub bridge: Ownership,
}

impl Folder {
    pub fn mods(&self) -> PathBuf {
        self.dir.join("mods")
    }
}

/// The `key=value` of a properties file (`eula.txt`, `server.properties`), comments skipped.
fn property(path: &Path, key: &str) -> Option<String> {
    fs::read_to_string(path)
        .ok()?
        .lines()
        .filter(|line| !line.trim_start().starts_with('#'))
        .filter_map(|line| line.split_once('='))
        .find(|(k, _)| k.trim() == key)
        .map(|(_, value)| value.trim().to_string())
}

fn flag(path: &Path, key: &str) -> Option<bool> {
    property(path, key).map(|value| value.eq_ignore_ascii_case("true"))
}

fn names(dir: &Path) -> io::Result<Vec<String>> {
    let mut names = Vec::new();
    for entry in fs::read_dir(dir)? {
        names.push(entry?.file_name().to_string_lossy().into_owned());
    }
    Ok(names)
}

/// The version folders under `dir` (`libraries/net/fabricmc/fabric-loader/<version>` and the like).
fn versions(dir: &Path) -> Vec<String> {
    names(dir).unwrap_or_default()
}

/// `fabric-server-mc.<game>-loader.<loader>-launcher.<launcher>.jar`, the name Fabric's site gives its launcher: the
/// game and loader versions.
fn launcher_versions(name: &str) -> Option<(String, String)> {
    let rest = name
        .strip_prefix("fabric-server-mc.")?
        .strip_suffix(".jar")?;
    let (game, rest) = rest.split_once("-loader.")?;
    let (loader, _) = rest.split_once("-launcher.")?;
    Some((game.to_string(), loader.to_string()))
}

/// A Fabric server for Minecraft 1.21.1 in the non-empty folder `dir`, with its loader version when it shows: its
/// launcher (`fabric-server-launch*.jar`, `fabric-server-mc.*.jar`, `fabric-server-launcher.properties`, `.fabric/`)
/// and the game version from the launcher's name, `versions/` or the intermediary mappings in `libraries/`.
// ponytail: a server moved to another game version keeps its old version folders and still passes; Fabric Loader then
// refuses the bridge at start, which the server log shows.
fn existing(dir: &Path, names: &[String]) -> Result<Layout> {
    let refused = || Refusal::new(Kind::ServerFolder, dir.display());
    let named: Vec<(String, String)> = names.iter().filter_map(|n| launcher_versions(n)).collect();
    let libraries = dir.join("libraries/net/fabricmc");
    let fabric = !named.is_empty()
        || names.iter().any(|n| {
            (n.starts_with("fabric-server-launch") && n.ends_with(".jar"))
                || n == "fabric-server-launcher.properties"
                || n == ".fabric"
        })
        || libraries.join("fabric-loader").is_dir();
    let mut games: Vec<String> = named.iter().map(|(game, _)| game.clone()).collect();
    games.extend(versions(&dir.join("versions")));
    games.extend(versions(&libraries.join("intermediary")));
    if !fabric || !games.iter().any(|g| g == pins::MINECRAFT) {
        bail!(refused());
    }
    let mut loaders: Vec<String> = named.into_iter().map(|(_, loader)| loader).collect();
    loaders.extend(versions(&libraries.join("fabric-loader")));
    let loader = loaders
        .into_iter()
        .reduce(|a, b| if launcher::at_least(&a, &b) { a } else { b });
    if let Some(loader) = &loader
        && !launcher::at_least(loader, pins::FABRIC_LOADER_MIN)
    {
        bail!(Refusal::new(
            Kind::ServerLoader,
            format!("{} (Fabric Loader {loader})", dir.display())
        ));
    }
    Ok(Layout::Existing { loader })
}

/// The installer's manifest in `dir`, when it is a valid one of ours.
fn manifest(dir: &Path) -> Option<Manifest> {
    let path = platform::real_file(&dir.join(MANIFEST)).ok()?;
    serde_json::from_slice::<Manifest>(&fs::read(path).ok()?)
        .ok()
        .filter(|m| m.owner == OWNER && m.version == 1)
}

/// Reads what the server folder `dir` holds and refuses what an install would refuse: a file, a folder that is
/// neither empty nor a Fabric server for Minecraft 1.21.1, a path through a symbolic link, a missing parent, or bridge
/// files the installer did not write. Writes nothing.
pub fn inspect(dir: &Path) -> Result<Folder> {
    let dir = std::path::absolute(dir)?;
    let (dir, layout) = match fs::symlink_metadata(&dir) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            let parent = dir
                .parent()
                .filter(|p| !p.as_os_str().is_empty())
                .with_context(|| Refusal::new(Kind::NoAncestor, dir.display()))?;
            platform::real_dir(parent)?;
            (dir, Layout::New)
        }
        Err(e) => return Err(e.into()),
        Ok(_) => {
            let dir = platform::real_dir(&dir)?;
            let names = names(&dir)?;
            let layout = if names.is_empty() {
                Layout::New
            } else if manifest(&dir).is_some() {
                Layout::Ours
            } else {
                existing(&dir, &names)?
            };
            (dir, layout)
        }
    };
    let bridge = mods::ownership(&dir.join("mods"))?;
    if let Ownership::Foreign(refusal) | Ownership::Modified(refusal) = bridge {
        bail!(refusal);
    }
    Ok(Folder {
        eula: flag(&dir.join("eula.txt"), "eula"),
        online_mode: flag(&dir.join("server.properties"), "online-mode"),
        dir,
        layout,
        bridge,
    })
}

/// The major version in `java -version`'s output: `"21.0.5"` gives 21, `"1.8.0_412"` gives 8.
pub fn parse_java_version(output: &str) -> Option<u32> {
    let quoted = output.split('"').nth(1)?;
    let mut parts = quoted.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// The major version of the Java that `java` runs (the server's start scripts call `java`), `None` without one.
pub fn java_version(java: &OsStr) -> Option<u32> {
    let output = platform::quiet(Command::new(java).arg("-version"))
        .output()
        .ok()?;
    parse_java_version(&String::from_utf8_lossy(&output.stderr))
        .or_else(|| parse_java_version(&String::from_utf8_lossy(&output.stdout)))
}

/// Refuses unless `java` runs Java [`pins::JAVA_MIN`] or newer; returns its major version.
pub fn check_java(java: &OsStr) -> Result<u32> {
    match java_version(java) {
        Some(version) if version >= pins::JAVA_MIN => Ok(version),
        found => bail!(Refusal::new(
            Kind::JavaVersion,
            found.map_or("none".to_string(), |v| format!("Java {v}"))
        )),
    }
}

/// What [`install`] did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Outcome {
    /// The folder as it was before.
    pub before: Folder,
    /// The bridge files were already this release's: nothing changed in `mods/`.
    pub unchanged: bool,
    /// `eula.txt` was written now, at the operator's request.
    pub eula_written: bool,
    pub java: u32,
}

/// Puts the bridge JAR and Fabric API into the server folder `dir`, replacing an older bridge JAR of ours. A missing
/// or empty folder becomes a new server: the launcher, `start.sh`, `start.bat` and `mods/` are made in a hidden
/// sibling and renamed into place, so a failed run leaves nothing half made. `accept_eula` writes `eula.txt` when
/// there is none: only after the operator read and accepted Mojang's EULA. `java` is the command the start scripts
/// run; `closed` refuses while the server runs and is called before every write.
pub fn install(
    dir: &Path,
    payload: &Payload,
    accept_eula: bool,
    java: &OsStr,
    closed: Guard,
) -> Result<Outcome> {
    if payload.jar.1.is_empty() {
        bail!(Refusal::new(Kind::NoJar, &payload.release));
    }
    let java = check_java(java)?;
    let before = inspect(dir)?;
    closed()?;
    let mut outcome = Outcome {
        unchanged: false,
        eula_written: false,
        java,
        before: before.clone(),
    };
    if before.layout == Layout::New {
        create(&before.dir, payload, accept_eula, closed)?;
        outcome.eula_written = accept_eula;
        return Ok(outcome);
    }
    let mods_dir = before.mods();
    if !mods_dir.exists() {
        // A server that never started has no mods folder yet.
        fs::create_dir(&mods_dir)?;
    }
    let jar = (payload.jar.0.as_str(), payload.jar.1.as_slice());
    if before.bridge == Ownership::Owned {
        if ops::jar_current(&mods_dir, &before.bridge, payload)? {
            outcome.unchanged = true;
        } else {
            // ponytail: a failure between the two leaves no bridge JAR; running the install again puts it back.
            mods::remove(&mods_dir, false, closed)?;
            mods::install(&mods_dir, jar, &payload.fabric_api, closed)?;
        }
    } else {
        mods::install(&mods_dir, jar, &payload.fabric_api, closed)?;
    }
    let eula = before.dir.join("eula.txt");
    if accept_eula && fs::symlink_metadata(&eula).is_err() {
        closed()?;
        fs::write(&eula, EULA)?;
        outcome.eula_written = true;
    }
    Ok(outcome)
}

/// Makes the new server folder `dir` (missing or empty) in a hidden sibling, then renames it into place.
fn create(dir: &Path, payload: &Payload, accept_eula: bool, closed: Guard) -> Result<()> {
    if sha256_hex(LAUNCHER_JAR) != pins::FABRIC_SERVER_LAUNCHER_SHA256 {
        bail!("The embedded Fabric server launcher does not match its pin");
    }
    let (Some(parent), Some(name)) = (dir.parent(), dir.file_name()) else {
        bail!(Refusal::new(Kind::ServerFolder, dir.display()));
    };
    let staging = parent.join(format!(".{}.partial", name.to_string_lossy()));
    closed()?;
    if fs::symlink_metadata(&staging).is_ok() {
        // Left by a run that was killed; only this installer makes it.
        fs::remove_dir_all(&staging)?;
    }
    fs::create_dir(&staging)?;
    let mut replaced_empty = false;
    let written = (|| -> Result<()> {
        let mut files = BTreeMap::new();
        for (file, bytes) in [
            (LAUNCHER, LAUNCHER_JAR),
            ("start.sh", START_SH.as_bytes()),
            ("start.bat", START_BAT.as_bytes()),
        ] {
            fs::write(staging.join(file), bytes)?;
            files.insert(file.to_string(), sha256_hex(bytes));
        }
        platform::make_executable(&staging.join("start.sh"))?;
        fs::create_dir(staging.join("mods"))?;
        mods::install(
            &staging.join("mods"),
            (&payload.jar.0, &payload.jar.1),
            &payload.fabric_api,
            closed,
        )?;
        if accept_eula {
            fs::write(staging.join("eula.txt"), EULA)?;
        }
        let manifest = Manifest {
            owner: OWNER.into(),
            version: 1,
            files,
        };
        fs::write(
            staging.join(MANIFEST),
            serde_json::to_string_pretty(&manifest)? + "\n",
        )?;
        closed()?;
        if dir.exists() {
            // Empty, as inspect found it: remove_dir refuses anything else.
            fs::remove_dir(dir)?;
            replaced_empty = true;
        }
        fs::rename(&staging, dir)?;
        Ok(())
    })();
    if let Err(error) = written {
        let _ = fs::remove_dir_all(&staging);
        if replaced_empty && !dir.exists() {
            let _ = fs::create_dir(dir);
        }
        return Err(error);
    }
    Ok(())
}

/// Removes the bridge JAR from the server folder `dir` when it is unchanged; Fabric API, the launcher, the start
/// scripts, `eula.txt` and the worlds stay: the server is the operator's.
pub fn uninstall(dir: &Path, closed: Guard) -> Result<()> {
    let dir = platform::real_dir(dir)?;
    mods::remove(&dir.join("mods"), true, closed)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(path: &Path, text: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }

    fn open() -> Result<()> {
        Ok(())
    }

    fn payload(version: u32) -> Payload {
        Payload {
            release: format!("0.{version}.0+test"),
            jar: (
                "stellar-bridge-0.1.0.jar".into(),
                format!("jar v{version}").into_bytes(),
            ),
            fabric_api: b"fabric api".to_vec(),
            ..Payload::default()
        }
    }

    /// The tests run a fake `java`: a script printing `version` as `java -version` does (a batch file on Windows).
    fn fake_java(root: &Path, version: &str) -> std::ffi::OsString {
        let (name, script) = if cfg!(windows) {
            (
                format!("java-{version}.bat"),
                format!("@echo openjdk version \"{version}\" 2024-07-16 1>&2\r\n"),
            )
        } else {
            (
                format!("java-{version}"),
                format!("#!/bin/sh\necho 'openjdk version \"{version}\" 2024-07-16' >&2\n"),
            )
        };
        let java = root.join(name);
        write(&java, &script);
        platform::make_executable(&java).unwrap();
        java.into_os_string()
    }

    #[test]
    fn the_embedded_launcher_matches_its_pin_and_notice() {
        assert_eq!(
            sha256_hex(LAUNCHER_JAR),
            pins::FABRIC_SERVER_LAUNCHER_SHA256
        );
        assert!(LAUNCHER_NOTICE.contains(pins::FABRIC_SERVER_LAUNCHER_SHA256));
        assert!(LAUNCHER_LICENSE.contains("Apache License"));
        assert!(START_BAT.split_inclusive('\n').all(|l| l.ends_with("\r\n")));
        assert!(!START_SH.contains('\r'));
    }

    #[test]
    fn java_versions_parse_old_and_new_schemes() {
        let modern = "openjdk version \"21.0.5\" 2024-10-15 LTS\nOpenJDK Runtime Environment Temurin-21.0.5+11";
        assert_eq!(parse_java_version(modern), Some(21));
        assert_eq!(parse_java_version("java version \"1.8.0_412\""), Some(8));
        assert_eq!(
            parse_java_version("openjdk version \"25-ea\" 2025-09-16"),
            Some(25)
        );
        assert_eq!(parse_java_version("no java here"), None);
        assert_eq!(
            java_version(OsStr::new("stellar-bridge-no-such-java")),
            None
        );
        let missing = check_java(OsStr::new("stellar-bridge-no-such-java")).unwrap_err();
        assert_eq!(
            missing.downcast_ref::<Refusal>().unwrap().kind,
            Kind::JavaVersion
        );
    }

    #[test]
    fn layouts_are_told_apart_and_odd_folders_refused() {
        let root = tempfile::tempdir().unwrap();
        let base = platform::canonicalize(root.path()).unwrap();
        let kind = |dir: &Path| inspect(dir).map(|f| f.layout);
        assert_eq!(kind(&base.join("new")).unwrap(), Layout::New);
        assert!(inspect(&base.join("no/parent")).is_err());
        fs::create_dir(base.join("empty")).unwrap();
        assert_eq!(kind(&base.join("empty")).unwrap(), Layout::New);

        // Fabric's own installer: launcher, properties and the libraries it fetched.
        let classic = base.join("classic");
        write(&classic.join(LAUNCHER), "jar");
        write(
            &classic.join("fabric-server-launcher.properties"),
            "serverJar=server.jar\n",
        );
        fs::create_dir_all(classic.join("libraries/net/fabricmc/intermediary/1.21.1")).unwrap();
        fs::create_dir_all(classic.join("libraries/net/fabricmc/fabric-loader/0.19.5")).unwrap();
        fs::create_dir_all(classic.join("libraries/net/fabricmc/fabric-loader/0.19.10")).unwrap();
        write(
            &classic.join("server.properties"),
            "#Minecraft server properties\nonline-mode=true\n",
        );
        write(&classic.join("eula.txt"), "#By changing...\neula=false\n");
        let folder = inspect(&classic).unwrap();
        assert_eq!(
            folder.layout,
            Layout::Existing {
                loader: Some("0.19.10".into())
            }
        );
        assert_eq!(
            (folder.eula, folder.online_mode, &folder.bridge),
            (Some(false), Some(true), &Ownership::Absent)
        );

        // The single-file launcher from Fabric's site, never started.
        let site = base.join("site");
        write(
            &site.join("fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar"),
            "jar",
        );
        assert_eq!(
            kind(&site).unwrap(),
            Layout::Existing {
                loader: Some("0.19.5".into())
            }
        );
        let old = base.join("old");
        write(
            &old.join("fabric-server-mc.1.21.1-loader.0.16.10-launcher.1.0.1.jar"),
            "jar",
        );
        let error = inspect(&old).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().unwrap().kind,
            Kind::ServerLoader
        );
        let other_game = base.join("other");
        write(
            &other_game.join("fabric-server-mc.1.20.4-loader.0.19.5-launcher.1.1.2.jar"),
            "jar",
        );
        let vanilla = base.join("vanilla");
        write(&vanilla.join("server.jar"), "jar");
        fs::create_dir_all(vanilla.join("versions/1.21.1")).unwrap();
        let documents = base.join("documents");
        write(&documents.join("notes.txt"), "mine");
        for refused in [&other_game, &vanilla, &documents] {
            let error = inspect(refused).unwrap_err();
            assert_eq!(
                error.downcast_ref::<Refusal>().unwrap().kind,
                Kind::ServerFolder,
                "{refused:?}"
            );
        }
        write(&base.join("file"), "not a folder");
        assert!(inspect(&base.join("file")).is_err());
        // A bridge JAR copied by hand is never replaced.
        write(&site.join("mods/stellar-bridge-0.0.9.jar"), "by hand");
        let error = inspect(&site).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().unwrap().kind,
            Kind::ForeignJar
        );
    }

    #[test]
    fn a_new_folder_gets_the_launcher_scripts_and_mods_and_the_eula_only_when_asked() {
        let root = tempfile::tempdir().unwrap();
        let base = platform::canonicalize(root.path()).unwrap();
        let java = fake_java(&base, "21.0.5");
        let dir = base.join("server");
        let outcome = install(&dir, &payload(1), false, &java, &open).unwrap();
        assert_eq!(
            (outcome.before.layout, outcome.eula_written, outcome.java),
            (Layout::New, false, 21)
        );
        assert_eq!(fs::read(dir.join(LAUNCHER)).unwrap(), LAUNCHER_JAR);
        assert!(!dir.join("eula.txt").exists(), "never accepted for them");
        assert!(!dir.join("server.properties").exists());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(dir.join("start.sh"))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o111, 0o111);
        }
        assert!(
            fs::read_to_string(dir.join("start.bat"))
                .unwrap()
                .contains("\r\n")
        );
        assert_eq!(
            fs::read(dir.join("mods/stellar-bridge-0.1.0.jar")).unwrap(),
            b"jar v1"
        );
        assert_eq!(
            fs::read(dir.join("mods").join(pins::FABRIC_API_FILE)).unwrap(),
            b"fabric api"
        );
        assert!(!base.join(".server.partial").exists());
        let folder = inspect(&dir).unwrap();
        assert_eq!(
            (folder.layout, folder.bridge),
            (Layout::Ours, Ownership::Owned)
        );
        let listed = manifest(&dir).unwrap().files;
        assert_eq!(
            listed.keys().collect::<Vec<_>>(),
            vec![LAUNCHER, "start.bat", "start.sh"]
        );

        // Asked later: written once, never changed after.
        let again = install(&dir, &payload(1), true, &java, &open).unwrap();
        assert!(again.unchanged && again.eula_written);
        assert!(
            fs::read_to_string(dir.join("eula.txt"))
                .unwrap()
                .contains("eula=true")
        );
        fs::write(dir.join("eula.txt"), "eula=false\n").unwrap();
        assert!(
            !install(&dir, &payload(1), true, &java, &open)
                .unwrap()
                .eula_written
        );
        assert_eq!(
            fs::read_to_string(dir.join("eula.txt")).unwrap(),
            "eula=false\n"
        );

        // A new folder with the box ticked, in an empty folder that already exists.
        let ticked = base.join("ticked");
        fs::create_dir(&ticked).unwrap();
        assert!(
            install(&ticked, &payload(1), true, &java, &open)
                .unwrap()
                .eula_written
        );
        assert_eq!(flag(&ticked.join("eula.txt"), "eula"), Some(true));
    }

    #[test]
    fn an_existing_server_gets_mods_only_and_updates_in_place() {
        let root = tempfile::tempdir().unwrap();
        let base = platform::canonicalize(root.path()).unwrap();
        let java = fake_java(&base, "21.0.5");
        let dir = base.join("server");
        write(
            &dir.join("fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar"),
            "their launcher",
        );
        write(&dir.join("server.properties"), "online-mode=false\n");
        write(&dir.join("world/level.dat"), "world");
        let before = crate::safety::tree(&dir).unwrap();
        let outcome = install(&dir, &payload(1), false, &java, &open).unwrap();
        assert_eq!(outcome.before.online_mode, Some(false));
        assert!(!dir.join(LAUNCHER).exists() && !dir.join("start.sh").exists());
        assert!(!dir.join(MANIFEST).exists());
        let mut after = crate::safety::tree(&dir).unwrap();
        after.retain(|path, _| !path.starts_with("mods/"));
        assert_eq!(after, before, "only mods/ changed");

        let updated = install(&dir, &payload(2), false, &java, &open).unwrap();
        assert!(!updated.unchanged);
        assert_eq!(
            fs::read(dir.join("mods/stellar-bridge-0.1.0.jar")).unwrap(),
            b"jar v2"
        );
        assert!(
            install(&dir, &payload(2), false, &java, &open)
                .unwrap()
                .unchanged
        );

        // Refusals before any write: an old Java, a running server, no payload JAR.
        let files = crate::safety::tree(&dir).unwrap();
        let old_java = fake_java(&base, "17.0.2");
        let error = install(&dir, &payload(3), false, &old_java, &open).unwrap_err();
        assert!(error.to_string().contains("found: Java 17"), "{error}");
        let running = || -> Result<()> { bail!(Refusal::new(Kind::ServerRunning, "server")) };
        assert!(install(&dir, &payload(3), false, &java, &running).is_err());
        assert!(install(&dir, &Payload::default(), false, &java, &open).is_err());
        assert_eq!(crate::safety::tree(&dir).unwrap(), files);

        uninstall(&dir, &open).unwrap();
        assert!(!dir.join("mods/stellar-bridge-0.1.0.jar").exists());
        assert!(
            dir.join("mods").join(pins::FABRIC_API_FILE).exists(),
            "Fabric API stays"
        );
        assert!(dir.join("world/level.dat").exists());
    }

    #[test]
    fn a_failed_new_folder_leaves_nothing() {
        let root = tempfile::tempdir().unwrap();
        let base = platform::canonicalize(root.path()).unwrap();
        let java = fake_java(&base, "21.0.5");
        let calls = std::cell::Cell::new(0);
        let starts = || -> Result<()> {
            calls.set(calls.get() + 1);
            if calls.get() > 3 {
                bail!("the server started")
            }
            Ok(())
        };
        assert!(install(&base.join("server"), &payload(1), true, &java, &starts).is_err());
        let left: Vec<_> = names(&base).unwrap();
        assert!(
            left.len() == 1 && left[0].starts_with("java-21.0.5"),
            "{left:?}"
        );
    }
}
