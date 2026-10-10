//! The Minecraft side: the bridge JAR and, when the `mods` folder has none, Fabric API, recorded in
//! `.stellar-bridge-install.json` beside them. Fabric Loader reads only visible `.jar` files, so the manifest and the
//! temporary names are never loaded.

use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use crate::safety::{sha256_file, sha256_hex};
use crate::{Guard, Kind, Ownership, Refusal, pins, platform};

pub const MANIFEST: &str = ".stellar-bridge-install.json";
const OWNER: &str = "stellar-bridge";

/// `.stellar-bridge-install.json`: the files this installer added to the folder, by name, with their sha256. Fabric
/// API is listed only when the installer added it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub owner: String,
    pub version: u32,
    pub files: BTreeMap<String, String>,
}

pub fn is_bridge_jar(name: &str) -> bool {
    let name = name.to_lowercase();
    name.starts_with("stellar-bridge") && name.ends_with(".jar")
}

pub fn is_fabric_api(name: &str) -> bool {
    let name = name.to_lowercase();
    name.starts_with("fabric-api-") && name.ends_with(".jar")
}

/// The `mods` folder with links resolved: instance folders often live on another disk.
fn folder(mods: &Path) -> Result<PathBuf> {
    let mods = fs::canonicalize(mods)?;
    if !mods.is_dir() {
        bail!(Refusal::new(Kind::NotDirectory, mods.display()));
    }
    Ok(mods)
}

fn names(mods: &Path) -> Result<Vec<String>> {
    let mut names = Vec::new();
    for entry in fs::read_dir(mods)? {
        names.push(entry?.file_name().to_string_lossy().into_owned());
    }
    names.sort();
    Ok(names)
}

/// The folder's manifest, when it is a valid one of ours.
pub fn manifest(mods: &Path) -> Result<Manifest> {
    let path = platform::real_file(&mods.join(MANIFEST))?;
    let data = fs::read(&path)?;
    let unknown = || Refusal::new(Kind::UnknownManifest, path.display());
    let manifest: Manifest = match serde_json::from_slice(&data) {
        Ok(manifest) if data.len() <= 4096 => manifest,
        _ => bail!(unknown()),
    };
    // Only the two names this installer ever writes, so a manifest can never claim another mod.
    let plain = |name: &String| {
        !name.starts_with('.')
            && !name.contains(['/', '\\'])
            && (is_bridge_jar(name) || is_fabric_api(name))
    };
    if manifest.owner != OWNER
        || manifest.version != 1
        || !manifest.files.keys().all(plain)
        || manifest.files.keys().filter(|n| is_bridge_jar(n)).count() != 1
    {
        bail!(unknown());
    }
    Ok(manifest)
}

/// Prism and MultiMC disable a mod by adding this to its name.
pub const DISABLED: &str = ".disabled";

/// Where a listed file is.
enum Listed {
    /// Unchanged, under its name or disabled.
    Present(PathBuf),
    Missing,
    Changed,
}

fn find(mods: &Path, name: &str, expected: &str) -> Result<Listed> {
    for path in [mods.join(name), mods.join(format!("{name}{DISABLED}"))] {
        match fs::symlink_metadata(&path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e.into()),
            Ok(m) if !m.is_file() => bail!(Refusal::new(Kind::NotRegular, path.display())),
            Ok(_) if sha256_file(&path, &mut |_| {})? == expected => {
                return Ok(Listed::Present(path));
            }
            Ok(_) => return Ok(Listed::Changed),
        }
    }
    Ok(Listed::Missing)
}

/// Every listed file with its expected sha256 and where it is. A bridge JAR the manifest does not list is refused.
fn listed(mods: &Path) -> Result<Vec<(String, String, Listed)>> {
    let manifest = manifest(mods)?;
    let names = names(mods)?;
    if let Some(name) = names
        .iter()
        .find(|n| is_bridge_jar(n) && !manifest.files.contains_key(*n))
    {
        bail!(Refusal::new(Kind::ForeignJar, mods.join(name).display()));
    }
    let mut found = Vec::new();
    for (name, expected) in manifest.files {
        let state = match find(mods, &name, &expected)? {
            // Prism's updater replaced the Fabric API we added: never put a second one back.
            Listed::Missing if is_fabric_api(&name) && names.iter().any(|n| is_fabric_api(n)) => {
                continue;
            }
            state => state,
        };
        found.push((name, expected, state));
    }
    Ok(found)
}

/// The listed files whose content is missing or differs, with the sha256 the manifest expects. A disabled copy counts
/// as present, a Fabric API replaced by a newer one is no longer wanted, and a listed path that is not a regular file,
/// or a bridge JAR the manifest does not list, cannot be repaired.
pub fn drifted(mods: &Path) -> Result<Vec<(String, String)>> {
    Ok(listed(mods)?
        .into_iter()
        .filter(|(_, _, state)| !matches!(state, Listed::Present(_)))
        .map(|(name, expected, _)| (name, expected))
        .collect())
}

/// The listed files that are there, unchanged, by name with their actual path (which may be the disabled one).
pub fn present(mods: &Path) -> Result<Vec<(String, PathBuf)>> {
    Ok(listed(mods)?
        .into_iter()
        .filter_map(|(name, _, state)| match state {
            Listed::Present(path) => Some((name, path)),
            _ => None,
        })
        .collect())
}

/// Who owns the bridge files in `mods`. A bridge JAR without our manifest is foreign: it is never replaced or removed.
/// Listed files that went missing leave the rest owned; only changed content makes it modified. A folder that does
/// not exist (a deleted instance) holds nothing.
pub fn ownership(mods: &Path) -> Result<Ownership> {
    if fs::symlink_metadata(mods).is_err() {
        return Ok(Ownership::Absent);
    }
    let path = mods.join(MANIFEST);
    if !path.exists() && !path.is_symlink() {
        return Ok(match names(mods)?.into_iter().find(|n| is_bridge_jar(n)) {
            Some(name) => {
                Ownership::Foreign(Refusal::new(Kind::ForeignJar, mods.join(name).display()))
            }
            None => Ownership::Absent,
        });
    }
    Ok(match listed(mods) {
        Ok(found) => {
            let changed: Vec<String> = found
                .iter()
                .filter(|(_, _, state)| matches!(state, Listed::Changed))
                .map(|(name, _, _)| mods.join(name).display().to_string())
                .collect();
            if changed.is_empty() {
                Ownership::Owned
            } else {
                Ownership::Modified(Refusal::new(Kind::ModifiedJar, changed.join(", ")))
            }
        }
        Err(error) => Ownership::Modified(crate::refusal(error, Kind::ModifiedJar, &path)),
    })
}

/// Writes `name` in `dir` through a hidden temporary name and a rename, so Minecraft never sees a partial JAR.
pub(crate) fn write_atomic(dir: &Path, name: &str, data: &[u8]) -> Result<()> {
    let temporary = dir.join(format!(".{name}.part"));
    let written = (|| -> Result<()> {
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        file.write_all(data)?;
        file.sync_all()?;
        fs::rename(&temporary, dir.join(name))?;
        Ok(())
    })();
    if written.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    written
}

/// Adds the bridge JAR to `mods`, and Fabric API when the folder has no `fabric-api-*.jar`. The manifest comes first
/// and the bridge JAR last, each after `closed`; any failure removes what this call created.
pub fn install(
    mods: &Path,
    jar: (&str, &[u8]),
    fabric_api: &[u8],
    closed: Guard,
) -> Result<PathBuf> {
    let mods = folder(mods)?;
    closed()?;
    if !is_bridge_jar(jar.0) || jar.0.starts_with('.') || jar.0.contains(['/', '\\']) {
        bail!("Invalid bridge JAR name: {}", jar.0);
    }
    match ownership(&mods)? {
        Ownership::Absent => {}
        Ownership::Owned => bail!(Refusal::new(Kind::RemoveFirst, mods.display())),
        Ownership::Foreign(refusal) | Ownership::Modified(refusal) => bail!(refusal),
    }
    let mut outputs = Vec::new();
    if !names(&mods)?.iter().any(|n| is_fabric_api(n)) {
        if fabric_api.is_empty() {
            bail!(Refusal::new(Kind::FabricMissing, mods.display()));
        }
        outputs.push((pins::FABRIC_API_FILE, fabric_api));
    }
    outputs.push(jar);
    if let Some((name, _)) = outputs
        .iter()
        .find(|(name, _)| mods.join(name).exists() || mods.join(name).is_symlink())
    {
        bail!(Refusal::new(Kind::Replace, mods.join(name).display()));
    }
    let manifest = Manifest {
        owner: OWNER.into(),
        version: 1,
        files: outputs
            .iter()
            .map(|(name, data)| (name.to_string(), sha256_hex(data)))
            .collect(),
    };
    let mut created = Vec::new();
    let mut written = || -> Result<()> {
        write_atomic(
            &mods,
            MANIFEST,
            (serde_json::to_string_pretty(&manifest)? + "\n").as_bytes(),
        )?;
        created.push(mods.join(MANIFEST));
        for (name, data) in &outputs {
            closed()?;
            write_atomic(&mods, name, data)?;
            created.push(mods.join(name));
        }
        Ok(())
    };
    if let Err(error) = written() {
        for path in created.iter().rev() {
            fs::remove_file(path)?;
        }
        return Err(error);
    }
    Ok(mods.join(MANIFEST))
}

/// Removes the listed files when none changed: the bridge JAR first (or its disabled copy), then Fabric API unless
/// `keep_fabric` (it is then left to the user, as if they had added it), then the manifest. Missing files are skipped.
pub fn remove(mods: &Path, keep_fabric: bool, closed: Guard) -> Result<()> {
    let mods = folder(mods)?;
    closed()?;
    match ownership(&mods)? {
        Ownership::Owned => {}
        Ownership::Absent => bail!(Refusal::new(Kind::NothingToRemove, mods.display())),
        Ownership::Foreign(refusal) | Ownership::Modified(refusal) => bail!(refusal),
    }
    let mut found = present(&mods)?;
    found.sort_by_key(|(name, _)| !is_bridge_jar(name));
    for (name, path) in found {
        if is_bridge_jar(&name) || !keep_fabric {
            fs::remove_file(path)?;
        }
    }
    fs::remove_file(mods.join(MANIFEST))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn open() -> Result<()> {
        Ok(())
    }

    fn mods() -> (tempfile::TempDir, PathBuf) {
        let root = tempfile::tempdir().unwrap();
        let mods = fs::canonicalize(root.path()).unwrap().join("mods");
        fs::create_dir(&mods).unwrap();
        fs::write(mods.join("sodium.jar"), "sodium").unwrap();
        (root, mods)
    }

    const JAR: (&str, &[u8]) = ("stellar-bridge-0.1.0.jar", b"bridge");

    #[test]
    fn adds_fabric_api_only_when_missing_and_removes_only_its_own() {
        let (_root, mods) = mods();
        install(&mods, JAR, b"fabric", &open).unwrap();
        assert_eq!(
            fs::read(mods.join(pins::FABRIC_API_FILE)).unwrap(),
            b"fabric"
        );
        assert_eq!(ownership(&mods).unwrap(), Ownership::Owned);
        remove(&mods, false, &open).unwrap();
        assert_eq!(names(&mods).unwrap(), vec!["sodium.jar"]);
        fs::write(mods.join("fabric-api-0.116.18+1.21.1.jar"), "theirs").unwrap();
        install(&mods, JAR, b"fabric", &open).unwrap();
        assert_eq!(manifest(&mods).unwrap().files.len(), 1);
        remove(&mods, false, &open).unwrap();
        assert_eq!(names(&mods).unwrap().len(), 2, "their Fabric API stays");
    }

    #[test]
    fn kept_fabric_api_is_left_to_the_user() {
        let (_root, mods) = mods();
        install(&mods, JAR, b"fabric", &open).unwrap();
        remove(&mods, true, &open).unwrap();
        assert_eq!(
            names(&mods).unwrap(),
            vec![pins::FABRIC_API_FILE, "sodium.jar"]
        );
        assert_eq!(ownership(&mods).unwrap(), Ownership::Absent);
        install(&mods, JAR, b"fabric", &open).unwrap();
        assert_eq!(manifest(&mods).unwrap().files.len(), 1, "theirs now");
    }

    #[test]
    fn foreign_and_modified_jars_are_refused_and_kept() {
        let (_root, mods) = mods();
        fs::write(mods.join("stellar-bridge-0.0.9.jar"), "by hand").unwrap();
        assert!(matches!(ownership(&mods).unwrap(), Ownership::Foreign(_)));
        assert!(install(&mods, JAR, b"fabric", &open).is_err());
        assert!(remove(&mods, false, &open).is_err());
        assert_eq!(names(&mods).unwrap().len(), 2);
        fs::remove_file(mods.join("stellar-bridge-0.0.9.jar")).unwrap();
        install(&mods, JAR, b"fabric", &open).unwrap();
        fs::write(mods.join(JAR.0), "changed").unwrap();
        assert!(matches!(ownership(&mods).unwrap(), Ownership::Modified(_)));
        assert_eq!(
            drifted(&mods).unwrap(),
            vec![(JAR.0.into(), sha256_hex(JAR.1))]
        );
        assert!(remove(&mods, false, &open).is_err());
        assert_eq!(fs::read(mods.join(JAR.0)).unwrap(), b"changed");
        let claim = format!(
            r#"{{"owner": "stellar-bridge", "version": 1, "files": {{"{}": "{}", "sodium.jar": "{}"}}}}"#,
            JAR.0,
            sha256_hex(b"changed"),
            sha256_hex(b"sodium")
        );
        fs::write(mods.join(MANIFEST), claim).unwrap();
        assert!(remove(&mods, false, &open).is_err(), "never another mod");
        assert!(mods.join("sodium.jar").exists());
    }

    #[test]
    fn prism_updates_and_disables_stay_removable() {
        let (_root, mods) = mods();
        install(&mods, JAR, b"fabric", &open).unwrap();
        // Prism's updater swaps the Fabric API we added for a newer one.
        fs::remove_file(mods.join(pins::FABRIC_API_FILE)).unwrap();
        fs::write(mods.join("fabric-api-0.116.18+1.21.1.jar"), "newer").unwrap();
        assert_eq!(ownership(&mods).unwrap(), Ownership::Owned);
        assert_eq!(
            drifted(&mods).unwrap(),
            Vec::new(),
            "never a second Fabric API"
        );
        // Prism's Disable renames the bridge JAR.
        let disabled = mods.join(format!("{}{DISABLED}", JAR.0));
        fs::rename(mods.join(JAR.0), &disabled).unwrap();
        assert_eq!(ownership(&mods).unwrap(), Ownership::Owned);
        assert_eq!(drifted(&mods).unwrap(), Vec::new());
        assert_eq!(present(&mods).unwrap(), vec![(JAR.0.to_string(), disabled)]);
        remove(&mods, false, &open).unwrap();
        assert_eq!(
            names(&mods).unwrap(),
            vec!["fabric-api-0.116.18+1.21.1.jar", "sodium.jar"]
        );

        // A missing bridge JAR is repairable; a removed Fabric API with no other one comes back too.
        fs::remove_file(mods.join("fabric-api-0.116.18+1.21.1.jar")).unwrap();
        install(&mods, JAR, b"fabric", &open).unwrap();
        fs::remove_file(mods.join(JAR.0)).unwrap();
        fs::remove_file(mods.join(pins::FABRIC_API_FILE)).unwrap();
        assert_eq!(ownership(&mods).unwrap(), Ownership::Owned);
        assert_eq!(drifted(&mods).unwrap().len(), 2);
        remove(&mods, false, &open).unwrap();
        assert_eq!(names(&mods).unwrap(), vec!["sodium.jar"]);
        assert_eq!(ownership(&mods.join("deleted")).unwrap(), Ownership::Absent);
    }

    #[test]
    fn running_game_or_failed_write_leaves_nothing() {
        let (_root, mods) = mods();
        let running = || -> Result<()> { bail!("Close Minecraft first") };
        assert!(install(&mods, JAR, b"fabric", &running).is_err());
        let calls = std::cell::Cell::new(0);
        let starts = || -> Result<()> {
            calls.set(calls.get() + 1);
            if calls.get() > 2 {
                bail!("Minecraft started")
            } else {
                Ok(())
            }
        };
        assert!(install(&mods, JAR, b"fabric", &starts).is_err());
        assert_eq!(names(&mods).unwrap(), vec!["sodium.jar"]);
        assert!(install(&mods, ("other.jar", b"x"), b"fabric", &open).is_err());
        let empty = tempfile::tempdir().unwrap();
        let bare = fs::canonicalize(empty.path()).unwrap();
        assert!(
            install(&bare, JAR, b"", &open).is_err(),
            "no Fabric API to add"
        );
    }
}
