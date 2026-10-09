//! The bridge pieces one installer release carries: the Lua tree, the adapter DLL, the bridge JAR and Fabric API with
//! its licence. `tools/payload.py` stages them from this repository's build outputs and `build.rs` embeds them; a
//! saved previous set loads the same way for a rollback.

use anyhow::{Context, Result, bail};
use serde::Deserialize;
use sha2::{Digest, Sha512};
use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use crate::deploy::{self, Tree};
use crate::safety::{hex, sha256_hex};
use crate::{Ownership, adapter, mods, pins};

include!(concat!(env!("OUT_DIR"), "/payload.rs"));

pub const INDEX: &str = "payload.json";
const FABRIC_LICENSE: &str = "licenses/LICENSE-fabric-api";

/// The default is an empty set: verifying against it reports ownership only.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Payload {
    /// `<installer version>+<commit>`, or the folder name of a previous set.
    pub release: String,
    pub lua_revision: Option<u32>,
    /// Without `bridge_config.lua` or a manifest; empty when the set has no Lua mod.
    pub lua: Tree,
    /// Empty when the set has no adapter.
    pub dll: Vec<u8>,
    /// File name and bytes; empty in a previous set that has none.
    pub jar: (String, Vec<u8>),
    /// Empty when the set does not carry it.
    pub fabric_api: Vec<u8>,
    /// Licence texts by path, shown by `--licenses`.
    pub licenses: BTreeMap<String, Vec<u8>>,
}

/// `payload.json`, written by `tools/payload.py`.
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Index {
    release: String,
    files: BTreeMap<String, String>,
}

/// The `N` of `Loaded revision N;` in `main.lua`.
pub fn lua_revision(main: &[u8]) -> Option<u32> {
    let text = String::from_utf8_lossy(main);
    let (_, rest) = text.split_once("Loaded revision ")?;
    let (number, _) = rest.split_once(';')?;
    number.parse().ok()
}

impl Payload {
    /// The payload built into this binary; `None` for a build without `SBMC_PAYLOAD`.
    pub fn embedded() -> Result<Option<Payload>> {
        if FILES.is_empty() {
            return Ok(None);
        }
        let files = FILES
            .iter()
            .map(|(name, bytes)| (name.to_string(), bytes.to_vec()))
            .collect();
        Self::parse(files).map(Some)
    }

    /// Checks a staged payload: exactly the files `payload.json` lists, with their sha256; Fabric API at its pin with
    /// its licence; the production adapter; a Lua tree with `main.lua`.
    pub fn parse(mut files: BTreeMap<String, Vec<u8>>) -> Result<Payload> {
        let index: Index =
            serde_json::from_slice(&files.remove(INDEX).context("payload.json is missing")?)
                .context("payload.json is not recognized")?;
        if !files.keys().eq(index.files.keys()) {
            bail!("The payload files differ from payload.json");
        }
        if let Some((name, _)) = index
            .files
            .iter()
            .find(|(name, expected)| sha256_hex(&files[*name]) != **expected)
        {
            bail!("{name} does not match payload.json");
        }
        let mut take = |name: &str| {
            files
                .remove(name)
                .with_context(|| format!("The payload has no {name}"))
        };
        let fabric_api = take(pins::FABRIC_API_FILE)?;
        if hex(&Sha512::digest(&fabric_api)) != pins::FABRIC_API_SHA512 {
            bail!("Fabric API does not match its pin");
        }
        let dll = take(adapter::DLL)?;
        adapter::check_dll(&dll)?;
        let mut licenses = BTreeMap::new();
        licenses.insert(FABRIC_LICENSE.to_string(), take(FABRIC_LICENSE)?);
        let jar = files
            .keys()
            .find(|n| mods::is_bridge_jar(n) && !n.contains('/'))
            .cloned()
            .context("The payload has no bridge JAR")?;
        let jar = (jar.clone(), files.remove(&jar).unwrap());
        let mut lua = Tree::new();
        for (name, bytes) in files {
            match name.strip_prefix("lua/") {
                Some(relative) => lua.insert(relative.to_string(), bytes),
                None if name.starts_with("licenses/") => licenses.insert(name, bytes),
                None => bail!("Unexpected payload file: {name}"),
            };
        }
        let main = lua
            .get("Scripts/main.lua")
            .context("The payload has no Lua main.lua")?;
        Ok(Payload {
            release: index.release,
            lua_revision: lua_revision(main),
            lua,
            dll,
            jar,
            fabric_api,
            licenses,
        })
    }

    /// A previous set kept by `ledger::keep_previous`, checked against the manifests saved with it.
    pub fn from_previous(dir: &Path, pin: &str) -> Result<Payload> {
        let release = dir
            .file_name()
            .and_then(|n| n.to_str())
            .context("Invalid previous set")?
            .to_string();
        let changed = |what: &str, refusal: String| -> Result<()> {
            bail!("The previous {what} changed since it was kept: {refusal}")
        };
        let mut lua = Tree::new();
        match deploy::ownership(dir)? {
            Ownership::Owned => {
                lua = deploy::read_tree(&dir.join(deploy::MOD_NAME))?;
                lua.remove(deploy::MANIFEST);
                lua.remove(deploy::CONFIG);
            }
            Ownership::Absent => {}
            Ownership::Foreign(r) | Ownership::Modified(r) => changed("Lua mod", r)?,
        }
        let mut dll = Vec::new();
        match adapter::ownership(dir, pin)? {
            Ownership::Owned => dll = fs::read(dir.join(adapter::DLL))?,
            Ownership::Absent => {}
            Ownership::Foreign(r) | Ownership::Modified(r) => changed("adapter", r)?,
        }
        // A set kept from the Python tools has no JAR: it stays empty.
        let (mut jar, mut fabric_api) = ((String::new(), Vec::new()), Vec::new());
        match mods::ownership(dir)? {
            Ownership::Owned => {
                for (name, path) in mods::present(dir)? {
                    let bytes = fs::read(path)?;
                    if mods::is_bridge_jar(&name) {
                        jar = (name, bytes);
                    } else {
                        fabric_api = bytes;
                    }
                }
            }
            Ownership::Absent => {}
            Ownership::Foreign(r) | Ownership::Modified(r) => changed("bridge JAR", r)?,
        }
        Ok(Payload {
            release,
            lua_revision: lua.get("Scripts/main.lua").and_then(|m| lua_revision(m)),
            lua,
            dll,
            jar,
            fabric_api,
            licenses: BTreeMap::new(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A payload as `tools/payload.py` stages it, with a stand-in Fabric API (the real one is checked by its pin).
    fn staged() -> BTreeMap<String, Vec<u8>> {
        let mut files: BTreeMap<String, Vec<u8>> = BTreeMap::from([
            (
                "lua/Scripts/main.lua".into(),
                b"print('Loaded revision 57; ok')".to_vec(),
            ),
            ("lua/enabled.txt".into(), Vec::new()),
            (adapter::DLL.into(), adapter::test_dll()),
            ("stellar-bridge-0.1.0.jar".into(), b"jar".to_vec()),
            (FABRIC_LICENSE.into(), b"Apache License".to_vec()),
        ]);
        let index = serde_json::json!({
            "release": "0.1.0+abc1234",
            "files": files.iter().map(|(n, b)| (n.clone(), sha256_hex(b))).collect::<BTreeMap<_, _>>(),
        });
        files.insert(INDEX.into(), serde_json::to_vec(&index).unwrap());
        files
    }

    #[test]
    fn a_staged_payload_is_checked_file_by_file() {
        assert_eq!(lua_revision(b"x Loaded revision 57; y"), Some(57));
        assert_eq!(lua_revision(b"no revision"), None);
        // Without the real Fabric API, the parse stops at its pin, after every listed hash matched.
        let error = Payload::parse(staged()).unwrap_err().to_string();
        assert!(error.contains("no fabric-api"), "{error}");
        let mut changed = staged();
        changed.insert("lua/enabled.txt".into(), b"x".to_vec());
        let error = Payload::parse(changed).unwrap_err().to_string();
        assert!(error.contains("lua/enabled.txt does not match"), "{error}");
        let mut extra = staged();
        extra.insert("lua/extra.lua".into(), Vec::new());
        assert!(
            Payload::parse(extra)
                .unwrap_err()
                .to_string()
                .contains("differ")
        );
        let mut missing = staged();
        missing.remove(INDEX);
        assert!(Payload::parse(missing).is_err());
    }

    #[test]
    fn the_embedded_payload_is_consistent_when_present() {
        if let Some(payload) = Payload::embedded().unwrap() {
            assert!(payload.lua_revision.is_some());
            assert!(payload.lua.contains_key("enabled.txt"));
            assert!(payload.licenses.contains_key(FABRIC_LICENSE));
        }
    }
}
