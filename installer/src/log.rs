//! The log: every line goes to stderr and, once [`open`] has run, to `<cache>/Colony/Stellar Bridge/stellar-bridge.log`,
//! truncated at each start (one run is what a bug report needs). Never give it a secret, a SteamID64 or the content of
//! a Steam config file. The last lines are also kept in memory, for the pack build's log tail.

use std::collections::VecDeque;
use std::fs::File;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const NAME: &str = "stellar-bridge.log";

static FILE: Mutex<Option<File>> = Mutex::new(None);
static RECENT: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
/// How many lines [`recent`] keeps.
pub const RECENT_LINES: usize = 40;

/// Starts a fresh log file in `dir`.
pub fn open(dir: &Path) -> io::Result<PathBuf> {
    let path = dir.join(NAME);
    let file = File::create(&path)?;
    *FILE.lock().unwrap_or_else(|e| e.into_inner()) = Some(file);
    Ok(path)
}

pub fn line(text: &str) {
    eprintln!("{text}");
    {
        let mut recent = RECENT.lock().unwrap_or_else(|e| e.into_inner());
        if recent.len() == RECENT_LINES {
            recent.pop_front();
        }
        recent.push_back(text.to_string());
    }
    if let Some(file) = FILE.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
        let _ = writeln!(file, "{text}");
    }
}

/// The last [`RECENT_LINES`] lines, oldest first.
pub fn recent() -> Vec<String> {
    RECENT
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .cloned()
        .collect()
}
