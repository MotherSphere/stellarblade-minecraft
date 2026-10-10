//! The UE4SS Lua mod: a port of `tools/deploy.py`. It writes and checks the same `.deployment.json`, so this installer
//! and the Python tool each remove what the other installed.

use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Component, Path, PathBuf};

use crate::safety::{self, sha256_hex, slash};
use crate::{Guard, Kind, Ownership, Refusal, platform};

pub const MOD_NAME: &str = "StellarCollisionProbe";
pub const MANIFEST: &str = ".deployment.json";
pub const CONFIG: &str = "Scripts/bridge_config.lua";
/// Where [`install`] builds the mod before renaming it into place; only this installer creates it.
pub const STAGING: &str = ".StellarCollisionProbe.partial";
const ENABLED: &str = "enabled.txt";

/// The Lua tree to install: file bytes by '/'-joined relative path.
pub type Tree = BTreeMap<String, Vec<u8>>;

/// `.deployment.json`. Compared parsed, never as bytes: key order and spacing may differ between the two tools.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub owner: String,
    pub version: u32,
    /// sha256 by relative path, including `enabled.txt` and `bridge_config.lua`, without the manifest itself.
    pub files: BTreeMap<String, String>,
    /// Every directory, in Python pathlib's sorted order (by path component).
    pub directories: Vec<String>,
}

impl Manifest {
    fn of(files: BTreeMap<String, String>, directories: Vec<String>) -> Self {
        Self {
            owner: MOD_NAME.into(),
            version: 1,
            files,
            directories,
        }
    }
}

/// The files (with sha256) and directories under `root`, refusing symbolic links and special files.
pub fn inventory(root: &Path) -> Result<(BTreeMap<String, String>, Vec<String>)> {
    if !fs::symlink_metadata(root).is_ok_and(|m| m.is_dir()) {
        bail!(Refusal::new(Kind::NotDirectory, root.display()));
    }
    let (mut files, mut directories) = (BTreeMap::new(), Vec::new());
    safety::walk(root, &mut |relative, metadata| {
        if metadata.is_symlink() {
            bail!(Refusal::new(Kind::Symlink, root.join(relative).display()));
        } else if metadata.is_dir() {
            directories.push(relative.to_path_buf());
        } else if metadata.is_file() {
            files.insert(
                slash(relative)?,
                sha256_hex(&fs::read(root.join(relative))?),
            );
        } else {
            bail!(Refusal::new(
                Kind::NotRegular,
                root.join(relative).display()
            ));
        }
        Ok(())
    })?;
    // Path order compares components, as pathlib's sorted() does: "a/b" before "a-b".
    directories.sort();
    Ok((
        files,
        directories
            .iter()
            .map(|d| slash(d))
            .collect::<Result<_>>()?,
    ))
}

/// The tree under `root` as an installable [`Tree`] (a development payload, or a saved previous set).
pub fn read_tree(root: &Path) -> Result<Tree> {
    let (files, _) = inventory(root)?;
    files
        .into_keys()
        .map(|relative| Ok((relative.clone(), fs::read(root.join(&relative))?)))
        .collect()
}

/// The machine-specific `bridge_config.lua` pointing the mod at the private directory `bridge` through Proton's `Z:`.
pub fn config(bridge: &Path) -> Result<Vec<u8>> {
    let path = platform::game_path(&platform::private_dir(bridge)?)?;
    Ok(format!(
        "return {{directory={},scale=100}}\n",
        platform::lua_quote(&path)
    )
    .into_bytes())
}

/// Installs the Lua mod into `mods_dir/StellarCollisionProbe`, which must not exist. With `bridge`, writes the
/// machine-specific `bridge_config.lua` that points the mod at that private directory through Proton's `Z:`.
/// The complete tree, `enabled.txt` last, is built in [`STAGING`] and then renamed into place, so a killed run never
/// leaves a mod folder without its manifest; any failure removes the staging folder, and the next run removes a
/// leftover one.
pub fn install(
    mods_dir: &Path,
    source: &Tree,
    bridge: Option<&Path>,
    closed: Guard,
) -> Result<PathBuf> {
    closed()?;
    if source.contains_key(MANIFEST)
        || !source.contains_key("Scripts/main.lua")
        || !source.contains_key(ENABLED)
    {
        bail!(
            "Source must contain Scripts/main.lua and enabled.txt, without a deployment manifest"
        );
    }
    let mut directories = Vec::new();
    for relative in source.keys() {
        let path = Path::new(relative);
        if relative.contains('\\') || !path.components().all(|c| matches!(c, Component::Normal(_)))
        {
            bail!("Refusing an unsafe source path: {relative}");
        }
        directories.extend(
            path.ancestors()
                .skip(1)
                .filter(|a| !a.as_os_str().is_empty())
                .map(Path::to_path_buf),
        );
    }
    directories.sort();
    directories.dedup();
    let configuration = match bridge {
        None => None,
        Some(bridge) => {
            let configuration = config(bridge)?;
            if source.contains_key(CONFIG) {
                bail!("Source must not include machine-specific bridge_config.lua");
            }
            Some(configuration)
        }
    };
    if !fs::symlink_metadata(mods_dir).is_ok_and(|m| m.is_dir()) {
        bail!(Refusal::new(Kind::NotDirectory, mods_dir.display()));
    }
    let destination = mods_dir.join(MOD_NAME);
    if fs::symlink_metadata(&destination).is_ok() {
        bail!(Refusal::new(Kind::Replace, destination.display()));
    }
    let staging = mods_dir.join(STAGING);
    if fs::symlink_metadata(&staging).is_ok() {
        fs::remove_dir_all(&staging)?;
    }
    fs::create_dir(&staging)?;
    let written = (|| -> Result<()> {
        for relative in &directories {
            fs::create_dir(staging.join(relative))?;
        }
        let mut files = BTreeMap::new();
        for (relative, bytes) in source {
            if relative != ENABLED {
                fs::write(staging.join(relative), bytes)?;
            }
            files.insert(relative.clone(), sha256_hex(bytes));
        }
        if let Some(configuration) = &configuration {
            fs::write(staging.join(CONFIG), configuration)?;
            files.insert(CONFIG.into(), sha256_hex(configuration));
        }
        let directories = directories
            .iter()
            .map(|d| slash(d))
            .collect::<Result<_>>()?;
        let manifest = serde_json::to_string_pretty(&Manifest::of(files, directories))? + "\n";
        fs::write(staging.join(MANIFEST), manifest)?;
        closed()?;
        fs::write(staging.join(ENABLED), &source[ENABLED])?;
        fs::rename(&staging, &destination)?;
        Ok(())
    })();
    if let Err(error) = written {
        fs::remove_dir_all(&staging)?;
        return Err(error);
    }
    Ok(destination)
}

/// Who owns `mods_dir/StellarCollisionProbe`.
pub fn ownership(mods_dir: &Path) -> Result<Ownership> {
    let destination = mods_dir.join(MOD_NAME);
    match fs::symlink_metadata(&destination) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Ownership::Absent),
        Err(e) => return Err(e.into()),
        Ok(m) if !m.is_dir() => {
            return Ok(Ownership::Foreign(Refusal::new(
                Kind::NotDirectory,
                destination.display(),
            )));
        }
        Ok(_) => {}
    }
    let (mut files, directories) = match inventory(&destination) {
        Ok(found) => found,
        Err(error) => {
            return Ok(Ownership::Modified(crate::refusal(
                error,
                Kind::ModifiedLua,
                &destination,
            )));
        }
    };
    if files.remove(MANIFEST).is_none() {
        return Ok(Ownership::Foreign(Refusal::new(
            Kind::ForeignLua,
            destination.display(),
        )));
    }
    let manifest: Option<Manifest> =
        serde_json::from_slice(&fs::read(destination.join(MANIFEST))?).ok();
    Ok(if manifest == Some(Manifest::of(files, directories)) {
        Ownership::Owned
    } else {
        Ownership::Modified(Refusal::new(Kind::ModifiedLua, destination.display()))
    })
}

/// The installed mod's manifest, when it parses as ours.
pub fn manifest(mods_dir: &Path) -> Result<Manifest> {
    let path = platform::real_file(&mods_dir.join(MOD_NAME).join(MANIFEST))?;
    match serde_json::from_slice::<Manifest>(&fs::read(&path)?) {
        Ok(manifest) if manifest.owner == MOD_NAME && manifest.version == 1 => Ok(manifest),
        _ => bail!(Refusal::new(Kind::UnknownManifest, path.display())),
    }
}

/// The listed files whose content is missing or differs, with the sha256 the manifest expects. Unlisted files, changed
/// folders and symbolic links cannot be repaired.
pub fn drifted(mods_dir: &Path) -> Result<Vec<(String, String)>> {
    let manifest = manifest(mods_dir)?;
    let root = mods_dir.join(MOD_NAME);
    let (mut files, directories) = inventory(&root)?;
    files.remove(MANIFEST);
    if directories != manifest.directories {
        bail!(Refusal::new(Kind::ChangedLuaFolders, root.display()));
    }
    if let Some(extra) = files.keys().find(|f| !manifest.files.contains_key(*f)) {
        bail!(Refusal::new(
            Kind::UnlistedLuaFile,
            root.join(extra).display()
        ));
    }
    Ok(manifest
        .files
        .into_iter()
        .filter(|(relative, expected)| files.get(relative) != Some(expected))
        .collect())
}

/// Removes the Lua mod only when it is exactly what its manifest records; any other content is refused, never forced.
pub fn remove(mods_dir: &Path, closed: Guard) -> Result<PathBuf> {
    closed()?;
    match ownership(mods_dir)? {
        Ownership::Owned => {
            let destination = mods_dir.join(MOD_NAME);
            fs::remove_dir_all(&destination)?;
            Ok(destination)
        }
        Ownership::Absent => bail!(Refusal::new(Kind::NothingToRemove, mods_dir.display())),
        Ownership::Foreign(refusal) | Ownership::Modified(refusal) => bail!(refusal),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::{PermissionsExt, symlink};

    fn open() -> Result<()> {
        Ok(())
    }

    fn source() -> Tree {
        Tree::from([
            ("Scripts/main.lua".into(), b"print('probe')\n".to_vec()),
            (ENABLED.into(), Vec::new()),
        ])
    }

    fn mods() -> (tempfile::TempDir, PathBuf) {
        let root = tempfile::tempdir().unwrap();
        let mods = root.path().join("Mods");
        fs::create_dir(&mods).unwrap();
        fs::write(mods.join("mods.txt"), "OtherMod : 1\n").unwrap();
        (root, mods)
    }

    #[test]
    fn install_records_hashes_and_remove_deletes_only_the_mod() {
        let (_root, mods) = mods();
        let destination = install(&mods, &source(), None, &open).unwrap();
        let manifest: Manifest =
            serde_json::from_slice(&fs::read(destination.join(MANIFEST)).unwrap()).unwrap();
        assert_eq!(
            manifest.files["Scripts/main.lua"],
            sha256_hex(b"print('probe')\n")
        );
        assert_eq!(manifest.directories, vec!["Scripts"]);
        assert_eq!(ownership(&mods).unwrap(), Ownership::Owned);
        remove(&mods, &open).unwrap();
        assert_eq!(ownership(&mods).unwrap(), Ownership::Absent);
        assert_eq!(
            fs::read_to_string(mods.join("mods.txt")).unwrap(),
            "OtherMod : 1\n"
        );
    }

    #[test]
    fn foreign_modified_and_extra_content_are_refused_and_kept() {
        let (_root, mods) = mods();
        let destination = mods.join(MOD_NAME);
        fs::create_dir(&destination).unwrap();
        fs::write(destination.join("foreign.txt"), "keep").unwrap();
        assert!(install(&mods, &source(), None, &open).is_err());
        assert!(matches!(ownership(&mods).unwrap(), Ownership::Foreign(_)));
        assert!(remove(&mods, &open).is_err());
        fs::remove_dir_all(&destination).unwrap();
        install(&mods, &source(), None, &open).unwrap();
        assert!(
            install(&mods, &source(), None, &open).is_err(),
            "never overwrites its own install"
        );
        fs::create_dir(destination.join("extra")).unwrap();
        assert!(matches!(ownership(&mods).unwrap(), Ownership::Modified(_)));
        assert!(remove(&mods, &open).is_err());
        fs::remove_dir(destination.join("extra")).unwrap();
        let script = destination.join("Scripts/main.lua");
        fs::remove_file(&script).unwrap();
        symlink(mods.join("mods.txt"), &script).unwrap();
        assert!(remove(&mods, &open).is_err());
        assert!(script.is_symlink());
    }

    #[test]
    fn running_game_or_failed_write_leaves_no_mod() {
        let (_root, mods) = mods();
        let running = || -> Result<()> { bail!("Close Stellar Blade first") };
        assert!(install(&mods, &source(), None, &running).is_err());
        let calls = std::cell::Cell::new(0);
        let starts_midway = || -> Result<()> {
            calls.set(calls.get() + 1);
            if calls.get() > 1 {
                bail!("Stellar Blade started")
            } else {
                Ok(())
            }
        };
        assert!(install(&mods, &source(), None, &starts_midway).is_err());
        assert!(!mods.join(MOD_NAME).exists());
        assert!(!mods.join(STAGING).exists());
        // A run killed while writing left its staging folder: the next one starts clean.
        fs::create_dir_all(mods.join(STAGING).join("Scripts")).unwrap();
        fs::write(mods.join(STAGING).join("Scripts/main.lua"), "half").unwrap();
        install(&mods, &source(), None, &open).unwrap();
        assert!(!mods.join(STAGING).exists());
        assert_eq!(ownership(&mods).unwrap(), Ownership::Owned);
        assert!(remove(&mods, &running).is_err());
        assert!(mods.join(MOD_NAME).exists());
    }

    #[test]
    fn bridge_configuration_is_owned_and_needs_a_private_directory() {
        let (root, mods) = mods();
        let bridge = fs::canonicalize(root.path())
            .unwrap()
            .join("bridge 'quoted'");
        fs::create_dir(&bridge).unwrap();
        fs::set_permissions(&bridge, fs::Permissions::from_mode(0o755)).unwrap();
        assert!(install(&mods, &source(), Some(&bridge), &open).is_err());
        assert!(!mods.join(MOD_NAME).exists());
        fs::set_permissions(&bridge, fs::Permissions::from_mode(0o700)).unwrap();
        let destination = install(&mods, &source(), Some(&bridge), &open).unwrap();
        let config = fs::read_to_string(destination.join(CONFIG)).unwrap();
        let expected = format!("Z:{}", bridge.display());
        assert_eq!(
            config,
            format!(
                "return {{directory={},scale=100}}\n",
                platform::lua_quote(&expected)
            )
        );
        assert_eq!(ownership(&mods).unwrap(), Ownership::Owned);
        let mut with_config = source();
        with_config.insert(CONFIG.into(), b"return {}".to_vec());
        remove(&mods, &open).unwrap();
        assert!(install(&mods, &with_config, Some(&bridge), &open).is_err());
        let mut unsafe_path = source();
        unsafe_path.insert("../escape.lua".into(), Vec::new());
        assert!(install(&mods, &unsafe_path, None, &open).is_err());
        assert!(!mods.join(MOD_NAME).exists());
    }
}
