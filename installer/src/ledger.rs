//! The installer's own record, in its data folder (`~/.local/share/Colony/Stellar Bridge/`): `state.json`, the
//! previous owned set for rollback, the sets kept with files changed by hand, and the save backups. It is data, not cache: losing it hurts.

use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

use crate::payload::Payload;
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
/// run succeeds and [`commit_previous`] drops it, so a failed run that was restored still rolls back to it. A set with
/// files changed by hand cannot be installed again: it goes to `changed/<release>` (see [`set_aside`]), never over a set
/// in `previous/` that Roll back may go to.
pub fn keep_previous(data: &Path, release: &str, items: &[&Path]) -> Result<PathBuf> {
    let staging = stage(data, release, items)?;
    if Payload::from_previous(&staging).is_err() {
        return set_aside_as(data, &staging, release);
    }
    let kept = data.join("previous").join(release);
    if kept.exists() {
        discard(data, &kept)?;
    }
    fs::rename(&staging, &kept)?;
    Ok(kept)
}

/// Copies `items` into `previous/.<release>.partial`, which the next run clears when this one is killed.
fn stage(data: &Path, release: &str, items: &[&Path]) -> Result<PathBuf> {
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
    Ok(staging)
}

/// After a successful run, once `state.json` records it: `kept`, the Roll back set, becomes the only set in `previous/`.
pub fn commit_previous(data: &Path, kept: Option<&Path>) -> Result<()> {
    for entry in fs::read_dir(data.join("previous"))? {
        let path = entry?.path();
        if Some(path.as_path()) != kept {
            discard(data, &path)?;
        }
    }
    Ok(())
}

/// Deletes a set in `previous/` that is no longer needed, unless it holds files changed by hand (an interrupted or
/// unrestored run left it there, with nothing on record pointing at it): that one is [`set_aside`]. Only sets that
/// can be installed again, and the staging folders of a killed keep, are deleted.
fn discard(data: &Path, set: &Path) -> Result<()> {
    let hidden = set
        .file_name()
        .is_some_and(|n| n.to_string_lossy().starts_with('.'));
    if hidden || Payload::from_previous(set).is_ok() {
        fs::remove_dir_all(set)?;
    } else {
        set_aside(data, set)?;
    }
    Ok(())
}

/// Moves a kept set with files changed by hand, which cannot be installed again, to `changed/<release>` (`-2`, `-3`…
/// when taken). No run deletes it: they are the user's files.
pub fn set_aside(data: &Path, kept: &Path) -> Result<PathBuf> {
    set_aside_as(
        data,
        kept,
        &kept.file_name().unwrap_or_default().to_string_lossy(),
    )
}

fn set_aside_as(data: &Path, kept: &Path, name: &str) -> Result<PathBuf> {
    let changed = data.join("changed");
    fs::create_dir_all(&changed)?;
    let mut aside = changed.join(name);
    for n in 2.. {
        if fs::symlink_metadata(&aside).is_err() {
            break;
        }
        aside = changed.join(format!("{name}-{n}"));
    }
    fs::rename(kept, &aside)?;
    Ok(aside)
}

/// Where Roll back goes once `kept`, the set a run kept, is dropped (a failed run that was restored, the release
/// installed again): the set kept before it, if any, `before` when it is still there, else the one other set that can
/// be installed again. Reads only: the caller deletes `kept` once `state.json` records the result.
pub fn previous_without(
    data: &Path,
    kept: &Path,
    before: Option<&Path>,
) -> Result<Option<PathBuf>> {
    if let Some(before) = before.filter(|b| *b != kept && b.is_dir()) {
        return Ok(Some(before.to_path_buf()));
    }
    let mut rest = Vec::new();
    for entry in fs::read_dir(data.join("previous"))? {
        let entry = entry?;
        if entry.path() != kept
            && entry.path().is_dir()
            && !entry.file_name().to_string_lossy().starts_with('.')
            && Payload::from_previous(&entry.path()).is_ok()
        {
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
        let folder = root.path().join("Scripts");
        fs::create_dir_all(&folder).unwrap();
        fs::write(folder.join("main.lua"), "old").unwrap();
        let file = root.path().join("notes.txt");
        fs::write(&file, "old file").unwrap();
        let data = root.path().join("data");
        let first = keep_previous(&data, "0.1.0", &[&folder, &file]).unwrap();
        assert_eq!(
            fs::read_to_string(first.join("Scripts/main.lua")).unwrap(),
            "old"
        );
        let second = keep_previous(&data, "0.2.0", &[&file]).unwrap();
        assert!(first.exists(), "kept until the run succeeds");
        assert_eq!(
            fs::read_to_string(second.join("notes.txt")).unwrap(),
            "old file"
        );
        // A failed run that was restored drops its set and goes back to the one kept before.
        assert_eq!(
            previous_without(&data, &second, Some(&second)).unwrap(),
            Some(first.clone())
        );
        fs::remove_dir_all(&second).unwrap();
        let second = keep_previous(&data, "0.2.0", &[&file]).unwrap();
        commit_previous(&data, Some(&second)).unwrap();
        assert!(!first.exists());
        assert!(!data.join("changed").exists(), "nothing changed by hand");
        assert!(keep_previous(&data, "0.3.0", &[&root.path().join("missing")]).is_err());
        assert!(second.exists(), "a failed copy keeps the last good set");
        assert!(keep_previous(&data, "../x", &[]).is_err());
    }

    /// A set that cannot be installed again (a Lua mod with a file changed by hand) left in `previous/` with nothing
    /// pointing at it is never deleted: replacing or committing past it sets it aside.
    #[test]
    fn sets_with_changed_files_are_set_aside_not_deleted() {
        let root = tempfile::tempdir().unwrap();
        let data = root.path().join("data");
        let lua = root.path().join(crate::deploy::MOD_NAME);
        fs::create_dir_all(&lua).unwrap();
        fs::write(lua.join("main.lua"), "my edit").unwrap();
        let changed = keep_previous(&data, "0.1.0", &[&lua]).unwrap();
        assert!(Payload::from_previous(&changed).is_err());
        let dropped = keep_previous(&data, "0.2.0", &[]).unwrap();
        assert_eq!(previous_without(&data, &dropped, None).unwrap(), None);
        keep_previous(&data, "0.1.0", &[&lua]).unwrap();
        let other = keep_previous(&data, "0.2.0", &[]).unwrap();
        commit_previous(&data, Some(&other)).unwrap();
        for aside in ["0.1.0", "0.1.0-2"] {
            assert_eq!(
                fs::read_to_string(
                    data.join("changed")
                        .join(aside)
                        .join(crate::deploy::MOD_NAME)
                        .join("main.lua")
                )
                .unwrap(),
                "my edit"
            );
        }
    }

    #[test]
    fn changed_sets_are_set_aside_without_replacing_one_another() {
        let root = tempfile::tempdir().unwrap();
        let data = root.path().join("data");
        let edited = root.path().join("main.lua");
        for edit in ["first edit", "second edit"] {
            fs::write(&edited, edit).unwrap();
            let kept = keep_previous(&data, "0.2.0", &[&edited]).unwrap();
            set_aside(&data, &kept).unwrap();
            assert!(!kept.exists());
        }
        assert_eq!(
            fs::read_to_string(data.join("changed/0.2.0/main.lua")).unwrap(),
            "first edit"
        );
        assert_eq!(
            fs::read_to_string(data.join("changed/0.2.0-2/main.lua")).unwrap(),
            "second edit"
        );
    }
}
