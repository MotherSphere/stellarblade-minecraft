//! The installer: detection, the ports of `tools/deploy.py` and `tools/input_proxy.py`, the Minecraft side, the
//! embedded payload, the operations and the safety net around every write, and the window over them (`app`, `ui`,
//! `i18n`). Nothing here launches a game or a launcher.

pub mod adapter;
pub mod app;
pub mod deploy;
pub mod detect;
pub mod i18n;
pub mod launcher;
pub mod ledger;
pub mod log;
pub mod mods;
pub mod ops;
pub mod pack;
pub mod payload;
pub mod pins;
pub mod platform;
pub mod safety;
pub mod server;
pub mod ui;

/// Who owns what sits at an install location, as both ownership manifests define it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ownership {
    Absent,
    /// Installed by this installer or the Python tools, and unchanged since.
    Owned,
    /// Not ours: never changed or removed.
    Foreign(Refusal),
    /// Ours, but changed or extended since: never forced.
    Modified(Refusal),
}

macro_rules! kinds {
    ($($kind:ident => $key:literal,)*) => {
        /// What the core refuses or fails on. Each kind is a string of the window's tables, under its key.
        #[derive(Debug, Clone, Copy, PartialEq, Eq)]
        pub enum Kind {
            $($kind,)*
        }

        impl Kind {
            pub const ALL: &[Kind] = &[$(Kind::$kind,)*];

            pub fn key(self) -> &'static str {
                match self {
                    $(Kind::$kind => $key,)*
                }
            }
        }
    };
}

kinds! {
    ForeignJar => "err_foreign_jar",
    ModifiedJar => "err_modified_jar",
    ForeignLua => "err_foreign_lua",
    ModifiedLua => "err_modified_lua",
    ForeignAdapter => "err_foreign_adapter",
    ModifiedAdapter => "err_modified_adapter",
    UnknownManifest => "err_unknown_manifest",
    NotDirectory => "err_not_directory",
    RealFile => "err_real_file",
    RealDir => "err_real_dir",
    Symlink => "err_symlink",
    NotRegular => "err_not_regular",
    NonUtf8 => "err_non_utf8",
    ChangedLuaFolders => "err_changed_lua_folders",
    UnlistedLuaFile => "err_unlisted_lua_file",
    Replace => "err_replace",
    NothingToRemove => "err_nothing_to_remove",
    RemoveFirst => "err_remove_first",
    FabricMissing => "err_fabric_missing",
    ExpectedExe => "err_expected_exe",
    UnsupportedExe => "err_unsupported_exe",
    BridgeControl => "err_bridge_control",
    BridgeNotAbsolute => "err_bridge_not_absolute",
    BridgeMode => "err_bridge_mode",
    BridgeVolume => "err_bridge_volume",
    BridgeAscii => "err_bridge_ascii",
    NoAncestor => "err_no_ancestor",
    ProcessList => "err_process_list",
    CloseFirst => "err_close_first",
    SaveBackupDiffers => "err_save_backup_differs",
    ProtectedChanged => "err_protected_changed",
    SavesChanged => "err_saves_changed",
    ModsMissing => "err_mods_missing",
    LeftBehind => "err_left_behind",
    NoJar => "err_no_jar",
    NoGameSide => "err_no_game_side",
    MismatchAfterWrite => "err_mismatch_after_write",
    InstallRestored => "err_install_restored",
    InstallNotRestored => "err_install_not_restored",
    InstallNotRemoved => "err_install_not_removed",
    NoPrevious => "err_no_previous",
    PreviousChanged => "err_previous_changed",
    StillModified => "err_still_modified",
    RunUpdate => "err_run_update",
    InvalidRelease => "err_invalid_release",
    CannotKeep => "err_cannot_keep",
    JavaArgument => "err_java_argument",
    InstanceUnreadable => "err_instance_unreadable",
    PickMods => "err_pick_mods",
    ServerRunning => "err_server_running",
    ServerFolder => "err_server_folder",
    ServerLoader => "err_server_loader",
    JavaVersion => "err_java_version",
    NoPayload => "no_payload",
}

/// A refusal or failure of the core and the path (or name) it is about. The window shows it in its language through
/// [`i18n::refusal`]; its `Display` is the English text, for the log and the tests.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Refusal {
    pub kind: Kind,
    /// Replaces `{path}` in the string; empty when the string has none.
    pub subject: String,
}

impl Refusal {
    pub fn new(kind: Kind, subject: impl ToString) -> Refusal {
        Refusal {
            kind,
            subject: subject.to_string(),
        }
    }
}

impl std::fmt::Display for Refusal {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&i18n::english(self.kind.key(), &self.subject))
    }
}

impl std::error::Error for Refusal {}

/// `error` as the refusal it carries, else as `kind` about `path` (an unreadable manifest, an I/O error).
pub(crate) fn refusal(error: anyhow::Error, kind: Kind, path: &std::path::Path) -> Refusal {
    error
        .downcast::<Refusal>()
        .unwrap_or_else(|_| Refusal::new(kind, path.display()))
}

/// Called before each write; an error stops the step before anything more changes.
pub type Guard<'a> = &'a dyn Fn() -> anyhow::Result<()>;
