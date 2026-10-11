//! The wardrobe catalog: Stellar Blade's ItemTable, EquipmentTable and ItemStringTable (EveExtract `json` dumps)
//! joined with the game's `Game.locres` (EveExtract `raw`) in the player's language, English as the fallback. One row
//! per item in the seven wardrobe categories: ID, category and rarity numbers (ESBItemCategory, ESBItemRarityType),
//! alias, icon texture, the meshes it puts on EVE (package paths, the suit's long-hair variant included), name and
//! description.

use anyhow::{Context, Result, bail};
use serde_json::Value;
use std::collections::HashMap;
use std::fs;
use std::path::Path;

/// The wardrobe categories by the last word of their ESBItemCategory name.
pub const CATEGORIES: &[(&str, u32)] = &[
    ("Gear", 2),
    ("ExoSpine", 3),
    ("NanoSuit", 4),
    ("Hair", 21),
    ("FaceAccessory", 22),
    ("EarRing", 23),
    ("Drone", 28),
];
const RARITY: &[&str] = &["None", "Common", "Uncommon", "Rare", "Epic", "Legendary"];
/// The three tables EveExtract dumps with `json`; each lands in `json/<file name>.json`.
pub const TABLES: &[&str] = &[
    "SB/Content/Local/Data/ItemTable.uasset",
    "SB/Content/Local/Data/EquipmentTable.uasset",
    "SB/Content/Local/Localize/ItemStringTable.uasset",
];

/// The game's text for `culture` (`fr`, `en`), copied with EveExtract `raw`.
pub fn locres_path(culture: &str) -> String {
    format!("SB/Content/Localization/Game/{culture}/Game.locres")
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Row {
    pub id: u64,
    pub category: u32,
    pub alias: String,
    pub rarity: u32,
    /// The icon's package path, empty when the item has none.
    pub icon: String,
    pub meshes: Vec<String>,
    /// EquipmentAlias values the EquipmentTable lacks.
    pub missing: Vec<String>,
    pub name: String,
    pub description: String,
}

const LOCRES_MAGIC: [u8; 16] = [
    0x0E, 0x14, 0x74, 0x75, 0x67, 0x4A, 0x03, 0xFC, 0x4A, 0x15, 0x90, 0x9D, 0xC3, 0x37, 0x7F, 0x1B,
];

/// A UE4 `.locres` of versions 2 to 4 (Stellar Blade writes 4: one more u32 per entry): text by key, by namespace.
pub fn locres(bytes: &[u8]) -> Result<HashMap<String, HashMap<String, String>>> {
    struct Reader<'a> {
        b: &'a [u8],
        p: usize,
    }
    impl Reader<'_> {
        fn take<const N: usize>(&mut self) -> Result<[u8; N]> {
            let end = self.p.checked_add(N).filter(|&e| e <= self.b.len());
            let end = end.context("locres: truncated")?;
            let out = self.b[self.p..end].try_into()?;
            self.p = end;
            Ok(out)
        }
        fn i32(&mut self) -> Result<i32> {
            Ok(i32::from_le_bytes(self.take()?))
        }
        fn fstr(&mut self) -> Result<String> {
            let n = self.i32()?;
            let len = (n.unsigned_abs() as usize) * if n < 0 { 2 } else { 1 };
            let end = self.p.checked_add(len).filter(|&e| e <= self.b.len());
            let bytes = &self.b[self.p..end.context("locres: truncated string")?];
            self.p += len;
            let text = if n < 0 {
                let units: Vec<u16> = bytes
                    .as_chunks::<2>()
                    .0
                    .iter()
                    .map(|c| u16::from_le_bytes(*c))
                    .collect();
                String::from_utf16_lossy(&units)
            } else {
                bytes.iter().map(|&b| b as char).collect()
            };
            Ok(text.trim_end_matches('\0').to_string())
        }
    }
    if bytes.len() < 25 || bytes[..16] != LOCRES_MAGIC {
        bail!("locres: legacy or unknown format");
    }
    let version = bytes[16];
    let mut r = Reader { b: bytes, p: 17 };
    let offset = i64::from_le_bytes(r.take()?);
    let resume = r.p;
    r.p = usize::try_from(offset).context("locres: bad offset")?;
    let mut strings = Vec::new();
    for _ in 0..r.i32()? {
        strings.push(r.fstr()?);
        if version >= 3 {
            r.i32()?;
        }
    }
    r.p = resume;
    if version >= 3 {
        r.i32()?;
    }
    let mut out: HashMap<String, HashMap<String, String>> = HashMap::new();
    for _ in 0..r.i32()? {
        if version >= 2 {
            r.i32()?;
        }
        let namespace = r.fstr()?;
        for _ in 0..r.i32()? {
            if version >= 2 {
                r.i32()?;
            }
            let key = r.fstr()?;
            r.i32()?;
            let index = usize::try_from(r.i32()?).ok();
            let text = index
                .and_then(|i| strings.get(i))
                .context("locres: bad index")?;
            out.entry(namespace.clone())
                .or_default()
                .insert(key, text.clone());
            if version >= 4 {
                r.i32()?;
            }
        }
    }
    Ok(out)
}

/// `SkeletalMesh'/Game/A/B.B'` or `/Game/A/B.B` → `SB/Content/A/B`; empty when unset (`None`).
pub fn package(reference: &str) -> String {
    let inner = match (reference.find('\''), reference.ends_with('\'')) {
        (Some(open), true)
            if open > 0
                && open < reference.len() - 1
                && reference[..open]
                    .chars()
                    .all(|c| c.is_alphanumeric() || c == '_') =>
        {
            &reference[open + 1..reference.len() - 1]
        }
        _ => reference,
    };
    let path = inner.split('.').next().unwrap_or_default();
    match path.strip_prefix("/Game/") {
        Some(rest) => format!("SB/Content/{rest}"),
        None => String::new(),
    }
}

/// A reference field as the dumps write it: a string, or a soft object path's `AssetPathName`.
fn reference(value: &Value) -> &str {
    value
        .as_str()
        .or_else(|| value.get("AssetPathName").and_then(Value::as_str))
        .unwrap_or_default()
}

/// Python's `str.split()` whitespace, which also counts the four ASCII separators.
fn space(c: char) -> bool {
    c.is_whitespace() || ('\x1c'..='\x1f').contains(&c)
}

fn table(dump: &Path, name: &str) -> Result<Value> {
    let path = dump.join("json").join(format!("{name}.uasset.json"));
    let value: Value = serde_json::from_slice(&fs::read(&path)?)
        .with_context(|| format!("{}: not JSON", path.display()))?;
    value
        .get(0)
        .cloned()
        .with_context(|| format!("{}: empty dump", path.display()))
}

/// The rows from `<dump>/json/*.uasset.json` and `<dump>/raw/<culture>.locres` for each of `cultures`, first first.
pub fn rows(dump: &Path, cultures: &[&str]) -> Result<Vec<Row>> {
    let items = table(dump, "ItemTable")?;
    let equipment = table(dump, "EquipmentTable")?;
    let strings = table(dump, "ItemStringTable")?;
    let items = items["Rows"].as_object().context("ItemTable: no rows")?;
    let equipment = equipment["Rows"]
        .as_object()
        .context("EquipmentTable: no rows")?;
    let source = &strings["StringTable"]["KeysToEntries"];
    let mut texts = Vec::new();
    for culture in cultures {
        let bytes = fs::read(dump.join("raw").join(format!("{culture}.locres")))?;
        texts.push(
            locres(&bytes)?
                .remove("ItemStringTable")
                .unwrap_or_default(),
        );
    }
    // One line: no tabs or breaks.
    let text = |key: &str| -> String {
        let found = texts
            .iter()
            .filter_map(|t| t.get(key).filter(|v| !v.is_empty()))
            .next()
            .map(String::as_str)
            .or_else(|| source.get(key).and_then(Value::as_str))
            .unwrap_or_default();
        found
            .split(space)
            .filter(|w| !w.is_empty())
            .collect::<Vec<_>>()
            .join(" ")
    };
    let field = |row: &Value, key: &str| row[key].as_str().unwrap_or_default().to_string();
    let last = |value: String| value.rsplit('_').next().unwrap_or_default().to_string();
    let mut out = Vec::new();
    for (alias, r) in items {
        let word = last(field(r, "Category"));
        let Some(&(_, category)) = CATEGORIES.iter().find(|(name, _)| *name == word) else {
            continue;
        };
        let (mut meshes, mut missing) = (Vec::new(), Vec::new());
        for key in ["EquipmentAlias", "EquipmentAlias2"] {
            let key = field(r, key);
            if key == "None" || key.is_empty() {
                continue;
            }
            let Some(e) = equipment.get(&key) else {
                missing.push(key);
                continue;
            };
            for path in ["MountingResourcePath", "MountingResourcePathWhenLongHair"] {
                let mesh = package(reference(&e[path]));
                if !mesh.is_empty() {
                    meshes.push(mesh);
                }
            }
        }
        let (content, function) = (
            text(&field(r, "DescContent")),
            text(&field(r, "DescFunction")),
        );
        let rarity = last(field(r, "Rarity"));
        let name_key = field(r, "Name");
        let name = match text(&name_key) {
            name if name.is_empty() => format!("?{name_key}"),
            name => name,
        };
        let (first, second) = if matches!(category, 2 | 3) {
            (function, content)
        } else {
            (content, function)
        };
        out.push(Row {
            id: r["ID"]
                .as_u64()
                .context("ItemTable: an ID is not a number")?,
            category,
            alias: alias.clone(),
            // ponytail: an unknown rarity reads as None (0), not a failed build.
            rarity: RARITY.iter().position(|r| *r == rarity).unwrap_or(0) as u32,
            icon: package(reference(&r["Asset2D"])),
            meshes,
            missing,
            name,
            description: if first.is_empty() { second } else { first },
        });
    }
    Ok(out)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A `.locres` v4 as Stellar Blade writes it: one namespace, the strings after the keys.
    pub(crate) fn locres_bytes(namespace: &str, entries: &[(&str, &str)]) -> Vec<u8> {
        fn fstr(out: &mut Vec<u8>, text: &str) {
            if text.is_ascii() {
                out.extend((text.len() as i32 + 1).to_le_bytes());
                out.extend(text.as_bytes());
                out.push(0);
            } else {
                let units: Vec<u16> = text.encode_utf16().chain([0]).collect();
                out.extend((-(units.len() as i32)).to_le_bytes());
                units.iter().for_each(|u| out.extend(u.to_le_bytes()));
            }
        }
        let mut out = LOCRES_MAGIC.to_vec();
        out.push(4);
        let offset_at = out.len();
        out.extend(0i64.to_le_bytes());
        out.extend((entries.len() as i32).to_le_bytes());
        out.extend(1i32.to_le_bytes());
        out.extend(0i32.to_le_bytes());
        fstr(&mut out, namespace);
        out.extend((entries.len() as i32).to_le_bytes());
        for (index, (key, _)) in entries.iter().enumerate() {
            out.extend(7i32.to_le_bytes());
            fstr(&mut out, key);
            out.extend(9i32.to_le_bytes());
            out.extend((index as i32).to_le_bytes());
            out.extend(0i32.to_le_bytes());
        }
        let offset = out.len() as i64;
        out[offset_at..offset_at + 8].copy_from_slice(&offset.to_le_bytes());
        out.extend((entries.len() as i32).to_le_bytes());
        for (_, text) in entries {
            fstr(&mut out, text);
            out.extend(1i32.to_le_bytes());
        }
        out
    }

    #[test]
    fn locres_reads_ascii_and_utf16_and_refuses_legacy_files() {
        let bytes = locres_bytes(
            "ItemStringTable",
            &[("A", "Plain"), ("B", "Combinaison d'été")],
        );
        let found = locres(&bytes).unwrap();
        assert_eq!(found["ItemStringTable"]["A"], "Plain");
        assert_eq!(found["ItemStringTable"]["B"], "Combinaison d'été");
        assert!(locres(&bytes[..30]).is_err());
        assert!(locres(b"not a locres file at all, no").is_err());
    }

    #[test]
    fn package_paths() {
        assert_eq!(package("SkeletalMesh'/Game/A/B.B'"), "SB/Content/A/B");
        assert_eq!(package("/Game/A/B.B"), "SB/Content/A/B");
        assert_eq!(package("None"), "");
        assert_eq!(package("/Engine/A.A"), "");
    }

    #[test]
    fn rows_join_tables_and_text_with_the_fallbacks() {
        let dir = tempfile::tempdir().unwrap();
        write_tables(dir.path());
        let rows = rows(dir.path(), &["fr", "en"]).unwrap();
        let by = |alias: &str| rows.iter().find(|r| r.alias == alias).unwrap();
        assert_eq!(rows.len(), 4, "the weapon row is not a wardrobe item");
        let suit = by("Suit_A");
        assert_eq!((suit.id, suit.category, suit.rarity), (2000068, 4, 3));
        assert_eq!(
            suit.meshes,
            ["SB/Content/Art/Suit_A", "SB/Content/Art/Suit_A_NH"]
        );
        assert_eq!(suit.icon, "SB/Content/UI/Icon_Suit_A");
        assert_eq!(suit.name, "Combinaison d'été");
        assert_eq!(
            suit.description, "Une description sur une ligne",
            "content first"
        );
        let gear = by("Gear_B");
        assert_eq!(gear.name, "Gear in English", "English fallback");
        assert_eq!(gear.description, "Function text", "function first for gear");
        assert_eq!(gear.missing, ["Nowhere"]);
        assert_eq!(by("Hair_C").name, "Source text", "string table fallback");
        assert_eq!(by("Ear_D").name, "?NoText");
        assert_eq!(super::rows(dir.path(), &["en"]).unwrap().len(), 4);
    }

    /// Synthetic table dumps and locres files, as EveExtract writes them.
    pub(crate) fn write_tables(dump: &Path) {
        let json = dump.join("json");
        fs::create_dir_all(&json).unwrap();
        let item = |id: u64,
                    category: &str,
                    rarity: &str,
                    eq: &str,
                    eq2: &str,
                    icon: &str,
                    name: &str,
                    content: &str,
                    function: &str| {
            serde_json::json!({"ID": id, "Category": format!("ESBItemCategory::Category_{category}"), "Rarity": format!("ESBItemRarityType::Rarity_{rarity}"),
                "EquipmentAlias": eq, "EquipmentAlias2": eq2, "Asset2D": icon, "Name": name, "DescContent": content, "DescFunction": function})
        };
        let items = serde_json::json!([{"Rows": {
            "Suit_A": item(2000068, "NanoSuit", "Rare", "Suit_A", "None", "Texture2D'/Game/UI/Icon_Suit_A.Icon_Suit_A'", "N_A", "C_A", "F_A"),
            "Gear_B": item(103010, "Gear", "Common", "Nowhere", "None", "/Game/UI/Icon_Gear.Icon_Gear", "N_B", "C_B", "F_B"),
            "Hair_C": item(2100001, "Hair", "Epic", "Hair_C", "None", "/Game/UI/Icon_Hair.Icon_Hair", "N_C", "", ""),
            "Ear_D": item(2300001, "EarRing", "Legendary", "Ear_D", "Ear_D2", "/Game/UI/Icon_Ear.Icon_Ear", "NoText", "", ""),
            "Sword_E": item(1, "Weapon", "Common", "None", "None", "None", "N_E", "", ""),
        }}]);
        let mount = |path: &str, long: &str| serde_json::json!({"MountingResourcePath": path, "MountingResourcePathWhenLongHair": long});
        let equipment = serde_json::json!([{"Rows": {
            "Suit_A": mount("SkeletalMesh'/Game/Art/Suit_A.Suit_A'", "SkeletalMesh'/Game/Art/Suit_A_NH.Suit_A_NH'"),
            "Hair_C": mount("/Game/Art/Hair_C.Hair_C", "None"),
            "Ear_D": mount("/Game/Art/Ear_D.Ear_D", ""),
            "Ear_D2": mount("/Game/Art/Ear_D2.Ear_D2", "None"),
        }}]);
        let strings =
            serde_json::json!([{"StringTable": {"KeysToEntries": {"N_C": "Source   text"}}}]);
        for (name, value) in [
            ("ItemTable", items),
            ("EquipmentTable", equipment),
            ("ItemStringTable", strings),
        ] {
            fs::write(json.join(format!("{name}.uasset.json")), value.to_string()).unwrap();
        }
        let raw = dump.join("raw");
        fs::create_dir_all(&raw).unwrap();
        let fr = locres_bytes(
            "ItemStringTable",
            &[
                ("N_A", "Combinaison\td'été"),
                ("C_A", "Une description\nsur une ligne"),
                ("N_B", ""),
            ],
        );
        let en = locres_bytes(
            "ItemStringTable",
            &[
                ("N_A", "Summer suit"),
                ("N_B", "Gear in English"),
                ("F_B", "Function text"),
                ("C_B", "Content"),
            ],
        );
        fs::write(raw.join("fr.locres"), fr).unwrap();
        fs::write(raw.join("en.locres"), en).unwrap();
    }
}
