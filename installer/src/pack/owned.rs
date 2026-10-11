//! What the player owns: the adapter's `eve-wardrobe.txt` when it is newer than Stellar Blade's save, else the newest
//! save (`StellarBladeSave[0-9][0-9].sav`, found by glob below the save folders), read once into memory and never
//! written. The save's folder is named after the player's SteamID64: no path below the save folders is ever logged.

use anyhow::{Context, Result, bail};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use super::catalog::Row;

/// A minimal read-only GVAS (UE 4.26) value: tagged properties; what it does not decode is skipped by size.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    None,
    Bool(bool),
    Int(i64),
    Float(f32),
    Str(String),
    Bytes(Vec<u8>),
    List(Vec<Value>),
    Map(Vec<(Value, Value)>),
    Struct(HashMap<String, Value>),
}

impl Value {
    /// The field `key` of a struct, or of a map keyed by strings; `None` otherwise.
    pub fn get(&self, key: &str) -> &Value {
        match self {
            Value::Struct(fields) => fields.get(key).unwrap_or(&Value::None),
            Value::Map(pairs) => pairs
                .iter()
                .find(|(k, _)| matches!(k, Value::Str(s) if s == key))
                .map_or(&Value::None, |(_, v)| v),
            _ => &Value::None,
        }
    }
}

/// Fixed-size structs, kept as their bytes.
const BINARY: &[(&str, usize)] = &[
    ("Guid", 16),
    ("Vector", 12),
    ("Rotator", 12),
    ("Vector2D", 8),
    ("Quat", 16),
    ("LinearColor", 16),
    ("Color", 4),
    ("DateTime", 8),
    ("Timespan", 8),
    ("IntPoint", 8),
];

struct Tag {
    name: String,
    kind: String,
    size: usize,
    extra: Extra,
}

enum Extra {
    None,
    Name(String),
    Pair(String, String),
    Bool(bool),
}

struct Save<'a> {
    b: &'a [u8],
    p: usize,
    /// Structs open around the current position.
    depth: usize,
}

/// Far deeper than any Stellar Blade save nests; a crafted one deeper would overflow the stack.
const MAX_DEPTH: usize = 64;

impl Save<'_> {
    fn bytes(&mut self, n: usize) -> Result<&[u8]> {
        let end = self.p.checked_add(n).filter(|&e| e <= self.b.len());
        let end = end.context("save: truncated")?;
        let out = &self.b[self.p..end];
        self.p = end;
        Ok(out)
    }

    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.bytes(4)?.try_into()?))
    }

    fn fstr(&mut self) -> Result<String> {
        let n = self.i32()?;
        let text = if n < 0 {
            let bytes = self.bytes(n.unsigned_abs() as usize * 2)?;
            let units: Vec<u16> = bytes
                .as_chunks::<2>()
                .0
                .iter()
                .map(|c| u16::from_le_bytes(*c))
                .collect();
            String::from_utf16(&units).context("save: bad UTF-16")?
        } else {
            self.bytes(n as usize)?.iter().map(|&b| b as char).collect()
        };
        Ok(text.trim_end_matches('\0').to_string())
    }

    fn byte(&self) -> Result<u8> {
        self.b.get(self.p).copied().context("save: truncated")
    }

    fn tag(&mut self) -> Result<Option<Tag>> {
        let name = self.fstr()?;
        if name == "None" {
            return Ok(None);
        }
        let kind = self.fstr()?;
        let size = usize::try_from(self.i32()?).context("save: negative size")?;
        self.i32()?;
        let extra = match kind.as_str() {
            "StructProperty" => {
                let name = self.fstr()?;
                self.bytes(16)?;
                Extra::Name(name)
            }
            "BoolProperty" => {
                let value = self.byte()? != 0;
                self.p += 1;
                Extra::Bool(value)
            }
            "ByteProperty" | "EnumProperty" | "ArrayProperty" | "SetProperty" => {
                Extra::Name(self.fstr()?)
            }
            "MapProperty" => Extra::Pair(self.fstr()?, self.fstr()?),
            _ => Extra::None,
        };
        let skip = if self.byte()? != 0 { 17 } else { 1 };
        self.bytes(skip)?;
        Ok(Some(Tag {
            name,
            kind,
            size,
            extra,
        }))
    }

    fn structure(&mut self, kind: Option<&str>) -> Result<Value> {
        if let Some(&(_, size)) = BINARY.iter().find(|(name, _)| Some(*name) == kind) {
            return Ok(Value::Bytes(self.bytes(size)?.to_vec()));
        }
        if self.depth == MAX_DEPTH {
            bail!("save: structs nested too deep");
        }
        self.depth += 1;
        let out = self.properties();
        self.depth -= 1;
        out
    }

    fn properties(&mut self) -> Result<Value> {
        let mut out = HashMap::new();
        while let Some(tag) = self.tag()? {
            let stop = self.p.checked_add(tag.size).context("save: bad size")?;
            let value = self.value(&tag);
            // A value that ran past its declared size is corrupt: without this, nested structs with sizes too small
            // would be read again and again as their parents' next fields.
            if self.p > stop {
                bail!("save: {} overruns its size", tag.name);
            }
            // What does not decode is kept as None and skipped by its size.
            out.insert(tag.name, value.unwrap_or(Value::None));
            self.p = stop;
        }
        Ok(Value::Struct(out))
    }

    /// A map or set entry: a struct entry carries no struct name, so it is a property list.
    fn scalar(&mut self, kind: &str) -> Result<Value> {
        Ok(match kind {
            "NameProperty" | "StrProperty" | "EnumProperty" => Value::Str(self.fstr()?),
            "IntProperty" => Value::Int(self.i32()?.into()),
            "UInt32Property" => Value::Int(u32::from_le_bytes(self.bytes(4)?.try_into()?).into()),
            "Int64Property" => Value::Int(i64::from_le_bytes(self.bytes(8)?.try_into()?)),
            "FloatProperty" => Value::Float(f32::from_le_bytes(self.bytes(4)?.try_into()?)),
            "BoolProperty" => Value::Bool(self.bytes(1)?[0] != 0),
            "StructProperty" => self.structure(None)?,
            _ => bail!("save: unhandled {kind}"),
        })
    }

    fn value(&mut self, tag: &Tag) -> Result<Value> {
        match (tag.kind.as_str(), &tag.extra) {
            ("BoolProperty", Extra::Bool(value)) => Ok(Value::Bool(*value)),
            ("StructProperty", Extra::Name(name)) => self.structure(Some(name)),
            ("MapProperty", Extra::Pair(key, value)) => {
                if self.i32()? != 0 {
                    bail!("save: map with removals");
                }
                let n = self.i32()?;
                let mut pairs = Vec::new();
                for _ in 0..n {
                    pairs.push((self.scalar(key)?, self.scalar(value)?));
                }
                Ok(Value::Map(pairs))
            }
            (kind @ ("ArrayProperty" | "SetProperty"), Extra::Name(inner)) => {
                if kind == "SetProperty" && self.i32()? != 0 {
                    bail!("save: set with removals");
                }
                let n = self.i32()?;
                let mut items = Vec::new();
                if inner == "StructProperty" {
                    let element = match self.tag()? {
                        Some(Tag {
                            extra: Extra::Name(name),
                            ..
                        }) => name,
                        _ => bail!("save: array without its struct tag"),
                    };
                    for _ in 0..n {
                        items.push(self.structure(Some(&element))?);
                    }
                } else {
                    for _ in 0..n {
                        items.push(self.scalar(inner)?);
                    }
                }
                Ok(Value::List(items))
            }
            (kind, _) => self.scalar(kind),
        }
    }
}

/// The save game's root struct. Refuses anything but a Stellar Blade save (`EVAS` + `GVAS` v2, `SBSaveGame`).
pub fn read_save(bytes: &[u8]) -> Result<Value> {
    if bytes.len() < 16 || &bytes[..4] != b"EVAS" || &bytes[8..12] != b"GVAS" {
        bail!("not a Stellar Blade save");
    }
    let mut r = Save {
        b: bytes,
        p: 12,
        depth: 0,
    };
    if r.i32()? != 2 {
        bail!("unexpected GVAS version");
    }
    r.bytes(14)?;
    r.fstr()?;
    r.i32()?;
    let versions = usize::try_from(r.i32()?).context("save: bad version count")?;
    r.bytes(20 * versions)?;
    if r.fstr()? != "/Script/SB.SBSaveGame" {
        bail!("not an SBSaveGame");
    }
    r.structure(None)
}

/// The catalog IDs of the items in a save: its inventory and what EVE wears. Refuses a save without an inventory
/// (another layout after a game update): it would read as owning nothing.
pub fn from_save(save: &Value, rows: &[Row]) -> Result<Vec<u64>> {
    let by_alias: HashMap<&str, u64> = rows.iter().map(|r| (r.alias.as_str(), r.id)).collect();
    let inventory = save.get("DataMap_SBItem").get("Item").get("Inventory");
    let equipment = save
        .get("DataMap_SBActor")
        .get("PlayerActor")
        .get("Equipment");
    let Value::List(entries) = inventory else {
        bail!("the save's inventory was not found");
    };
    let mut instances: Vec<&Value> = entries.iter().map(|e| e.get("ItemInstance")).collect();
    if let Value::Map(pairs) = equipment {
        instances.extend(pairs.iter().map(|(_, v)| v));
    }
    let mut ids: Vec<u64> = instances
        .into_iter()
        .filter_map(|i| match i.get("ItemAlias") {
            Value::Str(alias) => by_alias.get(alias.as_str()).copied(),
            _ => None,
        })
        .collect();
    ids.sort_unstable();
    ids.dedup();
    Ok(ids)
}

/// The item IDs of the adapter's `SBMC_WARDROBE 1` answer; `None` when it is not a complete, readable list. A refusal
/// from the menu gates still lists every owned item; one before the item bucket was found has `COUNT 0`.
pub fn from_list(text: &str) -> Option<Vec<u64>> {
    let mut lines: Vec<&str> = text.split('\n').collect();
    if lines.last() == Some(&"") {
        lines.pop();
    }
    let digits =
        |s: &str, max: usize| (1..=max).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_digit());
    if lines.len() < 7 || lines[0] != "SBMC_WARDROBE 1" {
        return None;
    }
    let count: usize = lines[5]
        .strip_prefix("COUNT ")
        .filter(|n| digits(n, 4))?
        .parse()
        .ok()?;
    let seq = lines[2].strip_prefix("SEQ ").unwrap_or(lines[2]);
    if count == 0 || lines.len() != 7 + count || lines[lines.len() - 1] != format!("END {seq}") {
        return None;
    }
    let mut ids = Vec::new();
    for line in &lines[6..6 + count] {
        let fields: Vec<&str> = line.strip_prefix("ITEM ")?.split(' ').collect();
        let numbers = [0, 1, 3, 4, 5];
        if fields.len() != 6
            || !numbers.iter().all(|&i| digits(fields[i], 20))
            || fields[2].len() != 32
            || !fields[2]
                .bytes()
                .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
        {
            return None;
        }
        ids.push(fields[0].parse().ok()?);
    }
    Some(ids)
}

/// Where the owned items came from, without the save's path (its folder is a SteamID64).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Source {
    List(SystemTime),
    Save(SystemTime),
}

/// The newest `StellarBladeSave[0-9][0-9].sav` in a save folder or one folder below it, with its time.
pub fn newest_save(save_dirs: &[PathBuf]) -> Option<(PathBuf, SystemTime)> {
    let is_save = |name: &str| {
        name.strip_prefix("StellarBladeSave")
            .and_then(|n| n.strip_suffix(".sav"))
            .is_some_and(|n| n.len() == 2 && n.bytes().all(|b| b.is_ascii_digit()))
    };
    let mut found = Vec::new();
    for dir in save_dirs {
        let mut dirs = vec![dir.clone()];
        dirs.extend(
            fs::read_dir(dir)
                .into_iter()
                .flatten()
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.is_dir()),
        );
        for dir in dirs {
            for entry in fs::read_dir(&dir).into_iter().flatten().flatten() {
                let path = entry.path();
                let name = path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or_default();
                if let (true, Ok(time)) = (
                    is_save(name) && path.is_file(),
                    entry.metadata().and_then(|m| m.modified()),
                ) {
                    found.push((path, time));
                }
            }
        }
    }
    found.into_iter().max_by_key(|(_, time)| *time)
}

/// The owned item IDs: the list when it is newer than the newest save and complete, else the save.
pub fn owned(list: &Path, save_dirs: &[PathBuf], rows: &[Row]) -> Result<(Vec<u64>, Source)> {
    let save = newest_save(save_dirs);
    let list_time = fs::metadata(list).and_then(|m| m.modified()).ok();
    if let Some(time) = list_time.filter(|t| save.as_ref().is_none_or(|(_, s)| t > s)) {
        let text = String::from_utf8_lossy(&fs::read(list)?).into_owned();
        match from_list(&text) {
            Some(ids) => return Ok((ids, Source::List(time))),
            None => crate::log::line(&format!(
                "pack: {} is not a complete wardrobe list; reading the save instead",
                list.display()
            )),
        }
    }
    let (path, time) = save.context("no Stellar Blade save and no wardrobe list")?;
    // Read once: the bytes in memory are the scratch copy; the file is never opened for writing.
    let bytes = fs::read(&path).context("the save could not be read")?;
    Ok((from_save(&read_save(&bytes)?, rows)?, Source::Save(time)))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ITEM: &str = "ITEM 2000140 4 0000000100000002000000030000000a 1 0 5";

    fn good() -> String {
        format!(
            "SBMC_WARDROBE 1\nSESSION {}\nSEQ 7\nTIME 1\nRESULT listed\nCOUNT 1\n{ITEM}\nEND 7\n",
            "0".repeat(32)
        )
    }

    #[test]
    fn list_grammar() {
        assert_eq!(from_list(&good()), Some(vec![2000140]));
        let refused = format!(
            "SBMC_WARDROBE 1\nSESSION {}\nSEQ 7\nTIME 1\nRESULT refused not-ready\nCOUNT 0\nEND 7\n",
            "0".repeat(32)
        );
        for bad in [
            good().replace("WARDROBE 1", "WARDROBE 2"),
            good().replace("END 7", "END 6"),
            good().replace("COUNT 1", "COUNT 2"),
            good().replace(ITEM, &format!("{ITEM} x")),
            good().replace("0000000a 1", "0000000A 1"),
            refused,
        ] {
            assert_eq!(from_list(&bad), None, "{bad}");
        }
        assert_eq!(
            from_list(&good().replace("RESULT listed", "RESULT refused combat")),
            from_list(&good()),
            "a refusal after the bucket lists it all"
        );
    }

    /// A GVAS writer for the shapes the reader decodes.
    pub(crate) struct Gvas(pub Vec<u8>);

    impl Gvas {
        fn fstr(&mut self, text: &str) {
            if text.is_ascii() {
                self.0.extend((text.len() as i32 + 1).to_le_bytes());
                self.0.extend(text.as_bytes());
                self.0.push(0);
            } else {
                let units: Vec<u16> = text.encode_utf16().chain([0]).collect();
                self.0.extend((-(units.len() as i32)).to_le_bytes());
                units.iter().for_each(|u| self.0.extend(u.to_le_bytes()));
            }
        }
        /// A property tag; `body` writes the value, whose size is patched in.
        fn property(
            &mut self,
            name: &str,
            kind: &str,
            header: impl FnOnce(&mut Gvas),
            body: impl FnOnce(&mut Gvas),
        ) {
            self.fstr(name);
            self.fstr(kind);
            let size_at = self.0.len();
            self.0.extend([0; 8]);
            header(self);
            self.0.push(0);
            let start = self.0.len();
            body(self);
            let size = (self.0.len() - start) as i32;
            self.0[size_at..size_at + 4].copy_from_slice(&size.to_le_bytes());
        }
        fn none(&mut self) {
            self.fstr("None");
        }
        fn name(&mut self, name: &str, value: &str) {
            self.property(name, "NameProperty", |_| {}, |g| g.fstr(value));
        }
        fn guid(&mut self, name: &str, value: u8) {
            self.property(
                name,
                "StructProperty",
                |g| {
                    g.fstr("Guid");
                    g.0.extend([0; 16])
                },
                |g| g.0.extend([value; 16]),
            );
        }
        fn int(&mut self, name: &str, value: i32) {
            self.property(
                name,
                "IntProperty",
                |_| {},
                |g| g.0.extend(value.to_le_bytes()),
            );
        }
        fn instance(&mut self, alias: &str, guid: u8) {
            self.name("ItemAlias", alias);
            self.guid("SavedGuid", guid);
            self.int("StatLevel", 1);
            self.int("MatVarIndex", 0);
            self.property("Unknown", "TextProperty", |_| {}, |g| g.0.extend([9; 5]));
            self.property("Flag", "BoolProperty", |g| g.0.push(1), |_| {});
            self.none();
        }
        fn structure(&mut self, name: &str, body: impl FnOnce(&mut Gvas)) {
            self.property(
                name,
                "StructProperty",
                |g| {
                    g.fstr("SBStruct");
                    g.0.extend([0; 16])
                },
                |g| {
                    body(g);
                    g.none()
                },
            );
        }
    }

    /// A save with two inventory items (one unknown to the catalog), one worn item and one worn in the inventory.
    pub(crate) fn save_bytes() -> Vec<u8> {
        let mut g = Gvas(b"EVAS\0\0\0\0GVAS".to_vec());
        g.0.extend(2i32.to_le_bytes());
        g.0.extend([0; 14]);
        g.fstr("++UE4+Release-4.26");
        g.0.extend(3i32.to_le_bytes());
        g.0.extend(1i32.to_le_bytes());
        g.0.extend([0; 20]);
        g.fstr("/Script/SB.SBSaveGame");
        g.property(
            "Level",
            "FloatProperty",
            |_| {},
            |g| g.0.extend(1.5f32.to_le_bytes()),
        );
        g.structure("DataMap_SBItem", |g| {
            g.structure("Item", |g| {
                g.property(
                    "Inventory",
                    "ArrayProperty",
                    |g| g.fstr("StructProperty"),
                    |g| {
                        g.0.extend(3i32.to_le_bytes());
                        g.property(
                            "Inventory",
                            "StructProperty",
                            |g| {
                                g.fstr("SBInventoryEntry");
                                g.0.extend([0; 16])
                            },
                            |_| {},
                        );
                        for (alias, guid) in [("Suit_A", 1), ("Unknown_Z", 2), ("Hair_C", 3)] {
                            g.structure("ItemInstance", |g| g.instance(alias, guid));
                            g.none();
                        }
                    },
                );
            });
        });
        g.structure("DataMap_SBActor", |g| {
            g.structure("PlayerActor", |g| {
                g.property(
                    "Equipment",
                    "MapProperty",
                    |g| {
                        g.fstr("IntProperty");
                        g.fstr("StructProperty")
                    },
                    |g| {
                        g.0.extend(0i32.to_le_bytes());
                        g.0.extend(2i32.to_le_bytes());
                        for (slot, alias, guid) in [(5i32, "Suit_A", 1), (10, "Ear_D", 4)] {
                            g.0.extend(slot.to_le_bytes());
                            g.instance(alias, guid);
                        }
                    },
                );
            });
        });
        g.property(
            "Broken",
            "MapProperty",
            |g| {
                g.fstr("ByteProperty");
                g.fstr("IntProperty")
            },
            |g| {
                g.0.extend(0i32.to_le_bytes());
                g.0.extend(1i32.to_le_bytes());
                g.0.extend([1, 0, 0, 0, 0]);
            },
        );
        g.none();
        g.0
    }

    #[test]
    fn save_reader_finds_inventory_and_worn_items() {
        let dir = tempfile::tempdir().unwrap();
        super::super::catalog::tests::write_tables(dir.path());
        let rows = super::super::catalog::rows(dir.path(), &["en"]).unwrap();
        let save = read_save(&save_bytes()).unwrap();
        assert_eq!(save.get("Level"), &Value::Float(1.5));
        assert_eq!(
            save.get("Broken"),
            &Value::None,
            "undecodable values are skipped by size"
        );
        assert_eq!(
            from_save(&save, &rows).unwrap(),
            vec![2000068, 2100001, 2300001]
        );
        assert!(from_save(&Value::Struct(HashMap::new()), &rows).is_err());
        let mut bad = save_bytes();
        bad[8] = b'X';
        assert!(read_save(&bad).is_err());
        assert!(read_save(&save_bytes()[..60]).is_err());
    }

    /// Structs nested `depth` deep whose declared sizes are 0, as a corrupt or crafted save may hold.
    fn nested_save(depth: usize) -> Vec<u8> {
        let mut g = Gvas(save_bytes());
        g.0.truncate(g.0.len() - 9);
        for _ in 0..depth {
            g.fstr("Nested");
            g.fstr("StructProperty");
            g.0.extend([0; 8]);
            g.fstr("SBStruct");
            g.0.extend([0; 17]);
        }
        for _ in 0..=depth {
            g.none();
        }
        g.0
    }

    #[test]
    fn corrupt_nesting_is_refused_quickly() {
        // Read as the window's build reads it: off the main thread, with a spawned thread's stack.
        std::thread::spawn(|| {
            for depth in [32, 2_000, 50_000] {
                let started = std::time::Instant::now();
                assert!(read_save(&nested_save(depth)).is_err(), "{depth}");
                assert!(started.elapsed().as_secs() < 5, "{depth}");
            }
        })
        .join()
        .unwrap();
    }

    #[test]
    fn owned_takes_the_newer_complete_source() {
        let dir = tempfile::tempdir().unwrap();
        super::super::catalog::tests::write_tables(dir.path());
        let rows = super::super::catalog::rows(dir.path(), &["en"]).unwrap();
        let saves = dir.path().join("SaveGames");
        let player = saves.join("76500000000000000");
        fs::create_dir_all(&player).unwrap();
        let list = dir.path().join("eve-wardrobe.txt");
        let at = |path: &Path, seconds: u64| {
            let time = SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(seconds);
            fs::File::options()
                .write(true)
                .open(path)
                .unwrap()
                .set_modified(time)
                .unwrap();
        };
        fs::write(player.join("StellarBladeSave00.sav"), save_bytes()).unwrap();
        fs::write(player.join("StellarBladeSave01.sav"), b"older, never read").unwrap();
        fs::write(player.join("StellarBladeSave.sav.bak"), b"not a save").unwrap();
        at(&player.join("StellarBladeSave00.sav"), 2_000_000_000);
        at(&player.join("StellarBladeSave01.sav"), 1_000_000_000);
        assert!(
            owned(&list, std::slice::from_ref(&saves), &rows)
                .unwrap()
                .0
                .len()
                == 3,
            "no list"
        );
        fs::write(&list, good()).unwrap();
        at(&list, 1_500_000_000);
        assert!(
            matches!(
                owned(&list, std::slice::from_ref(&saves), &rows).unwrap().1,
                Source::Save(_)
            ),
            "older list"
        );
        at(&list, 2_100_000_000);
        assert_eq!(
            owned(&list, std::slice::from_ref(&saves), &rows).unwrap(),
            (
                vec![2000140],
                Source::List(
                    SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(2_100_000_000)
                )
            )
        );
        fs::write(&list, good().replace("END 7", "END 6")).unwrap();
        at(&list, 2_100_000_000);
        assert!(
            matches!(
                owned(&list, std::slice::from_ref(&saves), &rows).unwrap().1,
                Source::Save(_)
            ),
            "incomplete list"
        );
        assert!(owned(&list, &[dir.path().join("none")], &rows).is_err());
    }

    /// The private check on a developer's own save, game closed: the newest save below `$SBMC_PRIVATE_SAVES`, read
    /// once. It prints only counts and a digest of the sorted item aliases, to compare with `wardrobe.py`'s reader.
    #[test]
    #[ignore]
    fn private_save() {
        let Some(dirs) = std::env::var_os("SBMC_PRIVATE_SAVES") else {
            return;
        };
        let (path, _) = newest_save(&[PathBuf::from(dirs)]).unwrap();
        let save = read_save(&fs::read(path).unwrap()).unwrap();
        let mut aliases: Vec<String> = Vec::new();
        if let Value::List(entries) = save.get("DataMap_SBItem").get("Item").get("Inventory") {
            aliases.extend(entries.iter().filter_map(|e| {
                match e.get("ItemInstance").get("ItemAlias") {
                    Value::Str(a) => Some(a.clone()),
                    _ => None,
                }
            }));
        }
        let inventory = aliases.len();
        if let Value::Map(pairs) = save
            .get("DataMap_SBActor")
            .get("PlayerActor")
            .get("Equipment")
        {
            aliases.extend(pairs.iter().filter_map(|(_, v)| match v.get("ItemAlias") {
                Value::Str(a) => Some(a.clone()),
                _ => None,
            }));
        }
        let worn = aliases.len() - inventory;
        aliases.sort();
        let digest = crate::safety::sha256_hex(aliases.join("\n").as_bytes());
        println!("inventory {inventory}, worn {worn}, aliases {digest}");
    }
}
