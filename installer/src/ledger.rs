//! The installer's own record, in its data folder (`~/.local/share/Colony/Stellar Bridge/`): `state.json`, the
//! previous owned set for rollback, and the save backups. It is data, not cache: losing it hurts.

use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

use crate::safety;

/// The program name: `Colony/Stellar Bridge/` folders.
pub const PROGRAM: &str = "Stellar Bridge";

pub const STATE: &str = "state.json";

pub fn data_dir() -> Result<PathBuf> {
    Ok(colony_ui::paths::data_dir(PROGRAM)?)
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    /// Written before the first change: on the next start, offer to resume or roll back.
    #[default]
    InProgress,
    Installed,
    Removed,
}

/// `state.json`: what this installer last did, and where. Every field is optional on load, so an older or partial file
/// still loads.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct State {
    pub status: Status,
    /// The installer release that wrote it.
    pub release: String,
    pub lua_revision: Option<u32>,
    /// Where the pieces went.
    pub target: Option<crate::ops::Target>,
    /// A Prism instance this installer created. It is never deleted: it holds the user's worlds.
    pub created_instance: Option<PathBuf>,
    /// The verified copy of the Stellar Blade saves made at the first install.
    pub save_backup: Option<PathBuf>,
    /// The kept previous set (`previous/<release>`), for rollback.
    pub previous: Option<PathBuf>,
    /// Unix seconds.
    pub updated: u64,
}

/// Loads `state.json`. Absent: `None`. Unparsable: moved aside to `state.corrupt`, so nothing overwrites it, then
/// `None`.
pub fn load(path: &Path) -> Option<State> {
    let text = fs::read(path).ok()?;
    match serde_json::from_slice(&text) {
        Ok(state) => Some(state),
        Err(_) => {
            let _ = fs::rename(path, path.with_extension("corrupt"));
            None
        }
    }
}

/// Writes `state.json` through a temporary sibling and a rename, so a crash leaves the old or the new file.
pub fn save(path: &Path, state: &State) -> Result<()> {
    let mut state = state.clone();
    state.updated = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    let temporary = path.with_extension("tmp");
    fs::write(&temporary, serde_json::to_string_pretty(&state)? + "\n")?;
    fs::rename(&temporary, path)?;
    Ok(())
}

/// Copies the owned set about to be replaced (the Lua mod folder, the adapter files, the JARs) into
/// `previous/<release>/`, replacing the set kept before. About 1 MB: the avatar pack is never copied.
pub fn keep_previous(data: &Path, release: &str, items: &[&Path]) -> Result<PathBuf> {
    if release.is_empty() || release.contains(['/', '\\']) || release.starts_with('.') {
        bail!("Invalid release name: {release}");
    }
    let previous = data.join("previous");
    fs::create_dir_all(&previous)?;
    let staging = previous.join(format!(".{release}.partial"));
    if staging.exists() {
        fs::remove_dir_all(&staging)?;
    }
    fs::create_dir(&staging)?;
    let copied = items.iter().try_for_each(|item| {
        let Some(name) = item.file_name() else {
            bail!("Cannot keep {}", item.display())
        };
        safety::copy_tree(item, &staging.join(name))
    });
    if let Err(error) = copied {
        fs::remove_dir_all(&staging)?;
        return Err(error);
    }
    for entry in fs::read_dir(&previous)? {
        let path = entry?.path();
        if path != staging {
            fs::remove_dir_all(&path)?;
        }
    }
    let kept = previous.join(release);
    fs::rename(&staging, &kept)?;
    Ok(kept)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_round_trips_and_a_corrupt_file_is_kept_aside() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("state.json");
        assert_eq!(load(&path), None);
        let state = State {
            status: Status::Installed,
            release: "0.1.0".into(),
            lua_revision: Some(57),
            ..State::default()
        };
        save(&path, &state).unwrap();
        let loaded = load(&path).unwrap();
        assert_eq!(
            (loaded.status, loaded.lua_revision, loaded.release.as_str()),
            (Status::Installed, Some(57), "0.1.0")
        );
        assert!(loaded.updated > 0);
        fs::write(&path, r#"{"status": "installed", "unknown": 1}"#).unwrap();
        assert_eq!(load(&path).unwrap().status, Status::Installed);
        fs::write(&path, "{not json").unwrap();
        assert_eq!(load(&path), None);
        assert_eq!(
            fs::read_to_string(root.path().join("state.corrupt")).unwrap(),
            "{not json"
        );
    }

    #[test]
    fn previous_keeps_one_set_of_files_and_folders() {
        let root = tempfile::tempdir().unwrap();
        let lua = root.path().join("StellarCollisionProbe");
        fs::create_dir_all(lua.join("Scripts")).unwrap();
        fs::write(lua.join("Scripts/main.lua"), "old").unwrap();
        let dll = root.path().join("xinput1_3.dll");
        fs::write(&dll, "old dll").unwrap();
        let data = root.path().join("data");
        let first = keep_previous(&data, "0.1.0", &[&lua, &dll]).unwrap();
        assert_eq!(
            fs::read_to_string(first.join("StellarCollisionProbe/Scripts/main.lua")).unwrap(),
            "old"
        );
        let second = keep_previous(&data, "0.2.0", &[&dll]).unwrap();
        assert!(!first.exists());
        assert_eq!(
            fs::read_to_string(second.join("xinput1_3.dll")).unwrap(),
            "old dll"
        );
        assert!(keep_previous(&data, "0.3.0", &[&root.path().join("missing")]).is_err());
        assert!(second.exists(), "a failed copy keeps the last good set");
        assert!(keep_previous(&data, "../x", &[]).is_err());
    }
}
