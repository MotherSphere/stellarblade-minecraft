//! The installer's own record, in its data folder (`~/.local/share/Colony/Stellar Bridge/`): `state.json`, the
//! previous owned set for rollback, and the save backups. It is data, not cache: losing it hurts.

use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

use crate::{Kind, Refusal, safety};

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

/// What a run kept in `previous/`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Kept {
    /// Nothing was installed (a first install, a reinstall after Uninstall).
    Nothing,
    Set(PathBuf),
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
    /// The kept previous set (`previous/<release>`), for rollback: only a set that can be installed again.
    pub previous: Option<PathBuf>,
    /// While a run is in progress, once it got past keeping: what it kept. A run finishing it keeps nothing again,
    /// since the set on disk is then partly its own.
    pub kept: Option<Kept>,
    /// Unix seconds.
    pub updated: u64,
}

/// Loads `state.json`. Absent or unparsable: `None`. Reads only: a dry run and the status page call it too.
pub fn load(path: &Path) -> Option<State> {
    serde_json::from_slice(&fs::read(path).ok()?).ok()
}

/// Writes `state.json` through a temporary sibling and a rename, so a crash leaves the old or the new file. An
/// unparsable `state.json` is first moved aside to `state.corrupt`, so nothing overwrites it.
pub fn save(path: &Path, state: &State) -> Result<()> {
    if path.exists() && load(path).is_none() {
        fs::rename(path, path.with_extension("corrupt"))?;
    }
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
/// `previous/<release>/`. About 1 MB: the avatar pack is never copied. The set kept before stays beside it until the
/// run succeeds and [`commit_previous`] drops it, so a failed run that was restored still rolls back to it.
pub fn keep_previous(data: &Path, release: &str, items: &[&Path]) -> Result<PathBuf> {
    if release.is_empty() || release.contains(['/', '\\']) || release.starts_with('.') {
        bail!(Refusal::new(Kind::InvalidRelease, release));
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
            bail!(Refusal::new(Kind::CannotKeep, item.display()))
        };
        safety::copy_tree(item, &staging.join(name))
    });
    if let Err(error) = copied {
        fs::remove_dir_all(&staging)?;
        return Err(error);
    }
    let kept = previous.join(release);
    if kept.exists() {
        fs::remove_dir_all(&kept)?;
    }
    fs::rename(&staging, &kept)?;
    Ok(kept)
}

/// After a successful run: `kept` becomes the only set in `previous/`.
pub fn commit_previous(data: &Path, kept: &Path) -> Result<()> {
    for entry in fs::read_dir(data.join("previous"))? {
        let path = entry?.path();
        if path != kept {
            fs::remove_dir_all(&path)?;
        }
    }
    Ok(())
}

/// After a failed run that was restored: drops `kept`, the set it kept (now identical to what is installed again),
/// and returns the set kept before it, if any: `before` when it is still there, else the one other complete set.
pub fn drop_previous(data: &Path, kept: &Path, before: Option<&Path>) -> Result<Option<PathBuf>> {
    fs::remove_dir_all(kept)?;
    if let Some(before) = before.filter(|b| b.is_dir()) {
        return Ok(Some(before.to_path_buf()));
    }
    let mut rest = Vec::new();
    for entry in fs::read_dir(data.join("previous"))? {
        let entry = entry?;
        if entry.path().is_dir() && !entry.file_name().to_string_lossy().starts_with('.') {
            rest.push(entry.path());
        }
    }
    Ok(if rest.len() == 1 { rest.pop() } else { None })
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
            fs::read_to_string(&path).unwrap(),
            "{not json",
            "loading writes nothing"
        );
        save(&path, &state).unwrap();
        assert_eq!(load(&path).unwrap().release, "0.1.0");
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
        assert!(first.exists(), "kept until the run succeeds");
        assert_eq!(
            fs::read_to_string(second.join("xinput1_3.dll")).unwrap(),
            "old dll"
        );
        // A failed run that was restored drops its set and goes back to the one kept before.
        assert_eq!(
            drop_previous(&data, &second, None).unwrap(),
            Some(first.clone())
        );
        assert!(!second.exists());
        let second = keep_previous(&data, "0.2.0", &[&dll]).unwrap();
        commit_previous(&data, &second).unwrap();
        assert!(!first.exists());
        assert!(keep_previous(&data, "0.3.0", &[&root.path().join("missing")]).is_err());
        assert!(second.exists(), "a failed copy keeps the last good set");
        assert!(keep_previous(&data, "../x", &[]).is_err());
    }
}
