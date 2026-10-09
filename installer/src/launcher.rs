//! Minecraft launchers and their instances, read only: Prism (native and Flatpak) and MultiMC, whose instances are
//! plain files, and a manual `mods` folder for every other launcher. `accounts.json` (tokens) is never opened.

use anyhow::{Result, bail};
use std::fs;
use std::path::{Path, PathBuf};

use crate::{pins, platform};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Prism,
    /// Its sandbox cannot see `~/.local/share`: the bridge folders move inside it (`detect::bridge_home`).
    PrismFlatpak,
    MultiMc,
}

/// A launcher's data folder.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Launcher {
    pub kind: Kind,
    pub root: PathBuf,
    /// Where its instances live (`InstanceDir`, relative to the root unless absolute).
    pub instances: PathBuf,
}

/// The `key=value` of a launcher's INI-style file (`prismlauncher.cfg`, `instance.cfg`), surrounding quotes removed.
fn ini_value(text: &str, key: &str) -> Option<String> {
    text.lines()
        .filter_map(|line| line.split_once('='))
        .find(|(k, _)| k.trim() == key)
        .map(|(_, value)| value.trim().trim_matches('"').to_string())
}

/// The launcher whose data folder is `root`: Prism's holds `prismlauncher.cfg`, MultiMC's `multimc.cfg`.
pub fn at(root: &Path, kind: Kind) -> Option<Launcher> {
    let config = root.join(if kind == Kind::MultiMc {
        "multimc.cfg"
    } else {
        "prismlauncher.cfg"
    });
    let text = fs::read_to_string(&config).ok();
    if text.is_none() && !root.join("instances").is_dir() {
        return None;
    }
    let dir = text
        .and_then(|t| ini_value(&t, "InstanceDir"))
        .filter(|d| !d.is_empty())
        .unwrap_or_else(|| "instances".into());
    Some(Launcher {
        kind,
        root: root.to_path_buf(),
        instances: root.join(dir),
    })
}

/// The Prism installations under `home`, native first.
pub fn prism(home: &Path) -> Vec<Launcher> {
    [
        (home.join(".local/share/PrismLauncher"), Kind::Prism),
        (
            home.join(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"),
            Kind::PrismFlatpak,
        ),
    ]
    .into_iter()
    .filter_map(|(root, kind)| at(&root, kind))
    .collect()
}

/// A Prism or MultiMC instance.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Instance {
    pub dir: PathBuf,
    /// `name=` in `instance.cfg`, else the folder name.
    pub name: String,
    pub minecraft: Option<String>,
    pub loader: Option<String>,
    /// The game folder (`minecraft/`, or `.minecraft/` in older instances), holding `mods/`.
    pub game: PathBuf,
}

impl Instance {
    /// Minecraft 1.21.1 with Fabric Loader 0.19.5 or newer: the bridge JAR loads without changing the instance.
    pub fn compatible(&self) -> bool {
        self.minecraft.as_deref() == Some(pins::MINECRAFT)
            && self
                .loader
                .as_deref()
                .is_some_and(|v| at_least(v, pins::FABRIC_LOADER_MIN))
    }

    pub fn mods(&self) -> PathBuf {
        self.game.join("mods")
    }
}

/// Whether the dotted version `version` is `min` or newer (build metadata after `+` or `-` is ignored).
pub fn at_least(version: &str, min: &str) -> bool {
    let parts = |v: &str| -> Vec<u64> {
        v.split(['+', '-'])
            .next()
            .unwrap_or_default()
            .split('.')
            .map(|p| p.parse().unwrap_or(0))
            .collect()
    };
    let (version, min) = (parts(version), parts(min));
    (0..version.len().max(min.len()))
        .map(|i| (version.get(i).unwrap_or(&0), min.get(i).unwrap_or(&0)))
        .find(|(a, b)| a != b)
        .is_none_or(|(a, b)| a > b)
}

/// The instances of a launcher, by name.
pub fn instances(launcher: &Launcher) -> Vec<Instance> {
    let Ok(entries) = fs::read_dir(&launcher.instances) else {
        return Vec::new();
    };
    let mut found: Vec<Instance> = entries
        .filter_map(|e| e.ok())
        .filter_map(|e| instance(&e.path()))
        .collect();
    found.sort_by(|a, b| a.name.cmp(&b.name));
    found
}

fn instance(dir: &Path) -> Option<Instance> {
    let config = fs::read_to_string(dir.join("instance.cfg")).ok()?;
    let pack: serde_json::Value = fs::read(dir.join("mmc-pack.json"))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    let component = |uid: &str| {
        pack["components"]
            .as_array()?
            .iter()
            .find(|c| c["uid"] == uid)?["version"]
            .as_str()
            .map(str::to_string)
    };
    let game = [dir.join("minecraft"), dir.join(".minecraft")]
        .into_iter()
        .find(|g| g.is_dir())
        .unwrap_or_else(|| dir.join("minecraft"));
    Some(Instance {
        name: ini_value(&config, "name").unwrap_or_else(|| {
            dir.file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned()
        }),
        minecraft: component("net.minecraft"),
        loader: component("net.fabricmc.fabric-loader"),
        dir: dir.to_path_buf(),
        game,
    })
}

/// A `mods` folder picked by hand for another launcher. Its loader cannot be checked.
pub fn manual_mods(path: &Path) -> Result<PathBuf> {
    let path = platform::real_dir(path)?;
    if path.file_name().and_then(|n| n.to_str()) != Some("mods") {
        bail!("Pick the instance's mods folder");
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(path: &Path, text: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }

    fn fake_instance(instances: &Path, folder: &str, name: &str, loader: Option<&str>) {
        let dir = instances.join(folder);
        write(
            &dir.join("instance.cfg"),
            &format!("[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname={name}\n"),
        );
        let mut components =
            vec![r#"{"uid": "net.minecraft", "version": "1.21.1", "important": true}"#.to_string()];
        if let Some(loader) = loader {
            components.push(format!(
                r#"{{"uid": "net.fabricmc.fabric-loader", "version": "{loader}"}}"#
            ));
        }
        write(
            &dir.join("mmc-pack.json"),
            &format!(
                r#"{{"formatVersion": 1, "components": [{}]}}"#,
                components.join(",")
            ),
        );
        fs::create_dir_all(dir.join("minecraft/mods")).unwrap();
    }

    #[test]
    fn prism_native_and_flatpak_instances_and_their_loaders() {
        let root = tempfile::tempdir().unwrap();
        let home = root.path();
        let native = home.join(".local/share/PrismLauncher");
        write(
            &native.join("prismlauncher.cfg"),
            "[General]\nInstanceDir=instances\n",
        );
        write(&native.join("accounts.json"), "never read");
        fake_instance(
            &native.join("instances"),
            "EVE",
            "EVE Bridge 1.21.1",
            Some("0.19.5"),
        );
        fake_instance(
            &native.join("instances"),
            "Old",
            "Old loader",
            Some("0.16.10"),
        );
        fake_instance(&native.join("instances"), "Vanilla", "Vanilla", None);
        let flatpak = home.join(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher");
        write(
            &flatpak.join("prismlauncher.cfg"),
            "InstanceDir=/elsewhere/instances\n",
        );
        let launchers = prism(home);
        assert_eq!(
            launchers.iter().map(|l| l.kind).collect::<Vec<_>>(),
            vec![Kind::Prism, Kind::PrismFlatpak]
        );
        assert_eq!(
            launchers[1].instances,
            PathBuf::from("/elsewhere/instances")
        );
        let found = instances(&launchers[0]);
        let summary: Vec<_> = found
            .iter()
            .map(|i| (i.name.as_str(), i.compatible()))
            .collect();
        assert_eq!(
            summary,
            vec![
                ("EVE Bridge 1.21.1", true),
                ("Old loader", false),
                ("Vanilla", false)
            ]
        );
        assert_eq!(found[0].mods(), native.join("instances/EVE/minecraft/mods"));
    }

    #[test]
    fn multimc_folder_and_manual_mods_folder() {
        let root = tempfile::tempdir().unwrap();
        let picked = fs::canonicalize(root.path()).unwrap();
        assert_eq!(at(&picked, Kind::MultiMc), None);
        write(&picked.join("multimc.cfg"), "InstanceDir=inst\n");
        fake_instance(&picked.join("inst"), "a", "A", Some("0.19.6+build"));
        let launcher = at(&picked, Kind::MultiMc).unwrap();
        assert!(instances(&launcher)[0].compatible());
        let mods = picked.join("inst/a/minecraft/mods");
        assert_eq!(manual_mods(&mods).unwrap(), mods);
        assert!(manual_mods(&picked).is_err());
    }

    #[test]
    fn versions_compare_by_number() {
        assert!(at_least("0.19.5", "0.19.5"));
        assert!(at_least("0.19.10", "0.19.5"));
        assert!(at_least("1.0", "0.19.5"));
        assert!(!at_least("0.19.4", "0.19.5"));
        assert!(!at_least("0.9.50", "0.19.5"));
    }
}
