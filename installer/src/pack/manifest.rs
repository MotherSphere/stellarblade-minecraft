//! What the pack says about itself, in the grammars Minecraft reads (`Wardrobe.java`): `wardrobe.txt` (the item
//! catalog), `pack.txt` (the game files and containers it was built from), plus the build's checks.

use anyhow::{Context, Result, bail};
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

use super::catalog::Row;

/// A section slot the game leaves empty.
pub const UNDRAWN: &[&str] = &["None", "Hide"];
/// A section is drawn when `<slot>.png`, `<slot>.overlay.png` or `<slot>.glow.png` exists.
pub const BASES: &[&str] = &["", "overlay.", "glow."];
/// The checks' `over_256_bones` lists meshes above the pose stream's former 256-bone limit, as `pack.py` does.
/// Minecraft now draws up to 512 (`AvatarMesh.MAX_BONES`); `wardrobe.txt` carries each item's count for that check.
pub const LISTED_BONES: u32 = 256;

/// An SBAVMESH v2 file's bone count and section material names.
pub fn header(bytes: &[u8]) -> Result<(u32, Vec<String>)> {
    let mut o = 12usize;
    if bytes.len() < 12 || &bytes[..8] != b"SBAVMESH" || bytes[8..12] != 2u32.to_le_bytes() {
        bail!("not an SBAVMESH v2 file");
    }
    let skip = |o: &mut usize, n: usize| -> Result<()> {
        *o = o
            .checked_add(n)
            .filter(|&e| e <= bytes.len())
            .context("SBAVMESH truncated")?;
        Ok(())
    };
    let u32_at = |o: &mut usize| -> Result<u32> {
        let at = *o;
        skip(o, 4)?;
        Ok(u32::from_le_bytes(bytes[at..at + 4].try_into()?))
    };
    let text = |o: &mut usize| -> Result<String> {
        let at = *o;
        skip(o, 2)?;
        let n = u16::from_le_bytes([bytes[at], bytes[at + 1]]) as usize;
        let start = *o;
        skip(o, n)?;
        Ok(String::from_utf8(bytes[start..start + n].to_vec())?)
    };
    let bones = u32_at(&mut o)?;
    for _ in 0..bones {
        text(&mut o)?;
        skip(&mut o, 44)?;
    }
    let vertices = u32_at(&mut o)? as usize;
    skip(&mut o, 56 * vertices)?;
    let indices = u32_at(&mut o)? as usize;
    skip(&mut o, 4 * indices)?;
    let mut names = Vec::new();
    for _ in 0..u32_at(&mut o)? {
        names.push(text(&mut o)?);
        skip(&mut o, 8)?;
    }
    Ok((bones, names))
}

/// The drawn sections of `names` without a base texture in `pack`.
pub fn untextured(pack: &Path, names: &[String]) -> Vec<String> {
    let set: BTreeSet<&String> = names
        .iter()
        .filter(|s| !UNDRAWN.contains(&s.as_str()))
        .filter(|s| {
            !BASES
                .iter()
                .any(|k| pack.join(format!("{s}.{k}png")).exists())
        })
        .collect();
    set.into_iter().cloned().collect()
}

/// SBAVWARDROBE 1: one item per line, tab-separated: ID, category (ESBItemCategory), alias, rarity
/// (ESBItemRarityType), icon (`hud.<icon>.png`, `-` when absent), the largest bone count of its meshes (0: none
/// known), its meshes (comma-separated file names without `.sbav`, `-` for none), name, description (game language,
/// English fallback). Then `END <item count>`. `icons` tells whether `hud.<name>.png` exists.
pub fn wardrobe(
    rows: &[Row],
    bones: &BTreeMap<String, u32>,
    icons: &dyn Fn(&str) -> bool,
) -> String {
    let mut sorted: Vec<&Row> = rows.iter().collect();
    sorted.sort_by_key(|r| (r.category, r.id));
    let mut out = String::from("SBAVWARDROBE 1\n");
    for row in sorted {
        let names: Vec<&str> = row.meshes.iter().map(|m| last(m)).collect();
        let icon = last(&row.icon);
        let icon = if !icon.is_empty() && icons(icon) {
            icon
        } else {
            "-"
        };
        let most = names
            .iter()
            .map(|n| bones.get(*n).copied().unwrap_or(0))
            .max()
            .unwrap_or(0);
        let meshes = if names.is_empty() {
            "-".to_string()
        } else {
            names.join(",")
        };
        out += &format!(
            "{}\t{}\t{}\t{}\t{icon}\t{most}\t{meshes}\t{}\t{}\n",
            row.id, row.category, row.alias, row.rarity, row.name, row.description
        );
    }
    out + &format!("END {}\n", rows.len())
}

/// The last segment of a package path: the asset's name.
pub fn last(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or_default()
}

/// SBAVPACK 1: the game files the pack was built from (Minecraft warns when Stellar Blade's Paks differ), then each
/// mesh's object path and the container it came from. `SOURCE <path under PAKS> <bytes> <mtime ns>`: the path may
/// hold spaces, so readers take the two numbers from the right. `sources` maps paths to "bytes mtime-ns"; `meshes`
/// are the `MESH` lines.
pub fn pack(paks: &Path, sources: &BTreeMap<String, String>, meshes: &[String]) -> Result<String> {
    let paks = paks
        .to_str()
        .context("the Paks folder's path is not UTF-8")?;
    if paks.is_empty() || paks.contains(['\n', '\r']) {
        bail!("the Paks folder's path cannot be written to pack.txt");
    }
    let mut out = format!("SBAVPACK 1\nPAKS {paks}\n");
    for (path, value) in sources {
        out += &format!("SOURCE {path} {value}\n");
    }
    for line in meshes {
        out += line;
        out.push('\n');
    }
    Ok(out + "END\n")
}

/// The build's summary, written as `<pack>.checks.json`.
#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
pub struct Checks {
    pub files: usize,
    pub bytes: u64,
    pub meshes: usize,
    pub baked: usize,
    pub over_256_bones: Vec<String>,
    pub untextured_sections: BTreeMap<String, Vec<String>>,
    pub meshes_changed_from_base: Vec<String>,
    pub wardrobe_items: usize,
    pub warnings: Vec<String>,
    /// Built with every outfit rather than the owned ones.
    pub every: bool,
}

fn last_checks(checks: &Path) -> serde_json::Value {
    fs::read(checks)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

/// The `untextured_sections` of the last build's checks, if any: their meshes are exported again.
pub fn last_untextured(checks: &Path) -> BTreeMap<String, Vec<String>> {
    serde_json::from_value(last_checks(checks)["untextured_sections"].clone()).unwrap_or_default()
}

/// Whether the last build took every outfit: an owned build cannot start from that pack, it would keep them all.
pub fn last_every(checks: &Path) -> bool {
    last_checks(checks)["every"].as_bool().unwrap_or(false)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// An SBAVMESH v2 file with `bones` bones, one vertex, three indices and the named sections.
    pub(crate) fn mesh(bones: u32, sections: &[&str]) -> Vec<u8> {
        let name = |out: &mut Vec<u8>, text: &str| {
            out.extend((text.len() as u16).to_le_bytes());
            out.extend(text.as_bytes());
        };
        let mut out = b"SBAVMESH".to_vec();
        out.extend(2u32.to_le_bytes());
        out.extend(bones.to_le_bytes());
        for i in 0..bones {
            name(&mut out, &format!("bone{i}"));
            out.extend([0; 44]);
        }
        out.extend(1u32.to_le_bytes());
        out.extend([0; 56]);
        out.extend(3u32.to_le_bytes());
        out.extend([0; 12]);
        out.extend((sections.len() as u32).to_le_bytes());
        for s in sections {
            name(&mut out, s);
            out.extend([0; 8]);
        }
        out.extend(0u32.to_le_bytes());
        out
    }

    #[test]
    fn mesh_header() {
        assert_eq!(
            header(&mesh(3, &["A", "None"])).unwrap(),
            (3, vec!["A".to_string(), "None".to_string()])
        );
        assert!(header(&mesh(3, &["A"])[..40]).is_err());
        assert!(header(b"SBAVMESH\x01\0\0\0").is_err());
    }

    #[test]
    fn wardrobe_lines() {
        let row = |id, category, icon: &str, meshes: &[&str], description: &str| Row {
            id,
            category,
            alias: format!("A{id}"),
            rarity: 3,
            icon: icon.into(),
            meshes: meshes.iter().map(|m| m.to_string()).collect(),
            missing: vec![],
            name: format!("Name {id}"),
            description: description.into(),
        };
        let rows = [
            row(
                2000068,
                4,
                "SB/Content/UI/Icon_A",
                &["SB/Content/A", "SB/Content/A_NH"],
                "Une description",
            ),
            row(103010, 2, "SB/Content/UI/Missing", &[], ""),
        ];
        let bones = BTreeMap::from([("A".to_string(), 230), ("A_NH".to_string(), 12)]);
        let text = wardrobe(&rows, &bones, &|icon| icon == "Icon_A");
        assert_eq!(
            text,
            "SBAVWARDROBE 1\n103010\t2\tA103010\t3\t-\t0\t-\tName 103010\t\n\
             2000068\t4\tA2000068\t3\tIcon_A\t230\tA,A_NH\tName 2000068\tUne description\nEND 2\n"
        );
    }

    #[test]
    fn pack_lines() {
        let sources = BTreeMap::from([("pakchunk0.pak".to_string(), "10 20".to_string())]);
        let text = pack(
            Path::new("/games/Paks"),
            &sources,
            &["MESH a.sbav SB/Content/a pakchunk0".into()],
        )
        .unwrap();
        assert_eq!(
            text,
            "SBAVPACK 1\nPAKS /games/Paks\nSOURCE pakchunk0.pak 10 20\nMESH a.sbav SB/Content/a pakchunk0\nEND\n"
        );
        assert!(pack(Path::new("/a\nb"), &sources, &[]).is_err());
    }
}
