//! The installer's core, without a window: detection, the ports of `tools/deploy.py` and `tools/input_proxy.py`, the
//! Minecraft side, the embedded payload, the operations, and the safety net around every write. Nothing here launches
//! a game or a launcher.

pub mod adapter;
pub mod deploy;
pub mod detect;
pub mod launcher;
pub mod ledger;
pub mod mods;
pub mod ops;
pub mod payload;
pub mod pins;
pub mod platform;
pub mod safety;

/// Who owns what sits at an install location, as both ownership manifests define it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ownership {
    Absent,
    /// Installed by this installer or the Python tools, and unchanged since.
    Owned,
    /// Not ours: never changed or removed.
    Foreign(String),
    /// Ours, but changed or extended since: never forced.
    Modified(String),
}

/// Called before each write; an error stops the step before anything more changes.
pub type Guard<'a> = &'a dyn Fn() -> anyhow::Result<()>;
