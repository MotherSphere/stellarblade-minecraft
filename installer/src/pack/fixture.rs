//! A synthetic Stellar Blade for the builder's tests, generated in code: item tables, locres files, SBAVMESH meshes
//! and their mesh list, material JSONs, patterned textures, the core textures, icons and the sword skeleton, laid out
//! as EveExtract writes them; and a stand-in extractor that copies from it. Nothing in it comes from the game: the
//! names are the ones the bakes key on, the pixels are arithmetic.

use anyhow::Result;
use image::{GrayImage, RgbaImage};
use serde_json::{Value, json};
use std::fs;
use std::path::{Path, PathBuf};

use super::catalog::tests::locres_bytes;
use super::manifest::{last, tests::mesh};
use super::run::{CHROME, CORE_TEXTURES};

/// A patterned image: every channel varies with x, y and `seed`, alpha included (0, 255 and partial values).
pub fn pattern(width: u32, height: u32, seed: u32) -> RgbaImage {
    RgbaImage::from_fn(width, height, |x, y| {
        image::Rgba([0, 1, 2, 3].map(|c| {
            ((x * (3 + seed)
                + y * (5 + 2 * seed)
                + c * (40 + seed)
                + (x * y * (seed + 1)) % 23
                + seed * 11)
                % 256) as u8
        }))
    })
}

/// An icon-like image: transparent but for a patterned rectangle.
fn icon(width: u32, height: u32, seed: u32, (x0, y0, x1, y1): (u32, u32, u32, u32)) -> RgbaImage {
    let mut image = pattern(width, height, seed);
    for (x, y, p) in image.enumerate_pixels_mut() {
        if !(x0..x1).contains(&x) || !(y0..y1).contains(&y) {
            p.0[3] = (x + y) as u8 % 8;
        } else {
            p.0[3] = p.0[3].max(9);
        }
    }
    image
}

fn write(path: &Path, bytes: impl AsRef<[u8]>) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, bytes).unwrap();
}

fn png(path: &Path, image: &RgbaImage) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    image.save(path).unwrap();
}

fn texture_ref(name: &str) -> Value {
    // References and exported paths differ in case, as in the game.
    json!({"ObjectName": format!("Texture2D'{name}'"), "ObjectPath": format!("/Game/Art/TEX/{name}.0")})
}

fn color(r: f64, g: f64, b: f64) -> Value {
    json!({"R": r, "G": g, "B": b, "A": 1.0})
}

/// The meshes: name, package path, bones, sections (slot names). Some core meshes are absent from this game.
pub const MESHES: &[(&str, &str, u32, &[&str])] = &[
    (
        "CH_P_EVE_Face_Jali3",
        "SB/Content/Art/Character/PC/CH_P_EVE_Head/CH_P_EVE_Face_Jali3",
        40,
        &[
            "MI_EVE_Head_V02_Jali",
            "MI_EyeRefractive1",
            "MI_EyeBrow1",
            "MI_Teeth",
            "NewMaterial",
            "MI_EVE_Eyeshadow_Occlusion",
            "EyeLight_Inst",
            "MI_EVE_Tearline",
            "MA_TeethOcculusion_Inst1",
            "M_MikeEyeBlend_Inst",
        ],
    ),
    (
        "EVE_HR_01",
        "SB/Content/Art/Character/PC/00_HR/EVE_HR_01/EVE_HR_01",
        20,
        &["MI_EVE_HR_01_TypeA", "MI_EVE_HR_01_Tail_TypeA"],
    ),
    (
        "CH_P_EVE_52_TypeB",
        "SB/Content/Art/Character/PC/CH_P_EVE_52/CH_P_EVE_52_TypeB",
        120,
        &["MI_Basebody_V02_F2", "CH_P_EVE_52_TypeB", "None"],
    ),
    (
        "ACC_EAR_19M",
        "SB/Content/Art/Character/PC/00_ACC/ACC_EAR_19/ACC_EAR_19M",
        5,
        &["MI_ACC_EAR_19M", "MI_ACC_EAR_19M_Emissive"],
    ),
    (
        "CH_W_Sword_01",
        "SB/Content/Art/Character/Weapon/Sword/CH_W_Sword_01/CH_W_Sword_01",
        3,
        &["CH_W_Sword_02_Blade_Body", "CH_W_Sword_02_Blade_Edge"],
    ),
    (
        "CH_NPC_Drone",
        "SB/Content/Art/Character/NPC/CH_NPC_Drone/CH_NPC_Drone",
        53,
        &["Drone_M_Inst", "Drone_M_2_Inst"],
    ),
    (
        "CH_NPC_Drone_Bear",
        "SB/Content/Art/Character/NPC/CH_NPC_Drone/CH_NPC_Drone_Bear",
        53,
        &[
            "MI_CH_NPC_Drone_06",
            "MI_CH_NPC_Drone_06_Decal",
            "Drone_M_2_Inst",
        ],
    ),
    (
        "CH_P_EVE_77",
        "SB/Content/Art/Character/PC/CH_P_EVE_77/CH_P_EVE_77",
        300,
        &[
            "M_Film",
            "M_HairFam",
            "M_Skin",
            "M_Fur",
            "M_Suit",
            "M_Masked",
            "M_Flat",
            "M_Gray",
            "M_Broken",
            "Hide",
        ],
    ),
    (
        "CH_P_EVE_77_NH",
        "SB/Content/Art/Character/PC/CH_P_EVE_77/CH_P_EVE_77_NH",
        290,
        &["M_Suit", "M_Skin"],
    ),
    (
        "CH_P_EVE_78",
        "SB/Content/Art/Character/PC/CH_P_EVE_78/CH_P_EVE_78",
        80,
        &["M_Suit", "M_Masked"],
    ),
];

/// The items: alias, ID, category, rarity, mesh, long-hair mesh, icon.
const ITEMS: &[(&str, u64, &str, &str, &str, &str, &str)] = &[
    (
        "Body_Suit",
        2000001,
        "NanoSuit",
        "Rare",
        "CH_P_EVE_52_TypeB",
        "None",
        "Item/NanoSuit_Icon_01",
    ),
    (
        "Suit_G",
        2000077,
        "NanoSuit",
        "Epic",
        "CH_P_EVE_77",
        "CH_P_EVE_77_NH",
        "Item/NanoSuit_Icon_77",
    ),
    (
        "Suit_H",
        2000078,
        "NanoSuit",
        "Common",
        "CH_P_EVE_78",
        "None",
        "Other/NanoSuit_Icon_77",
    ),
    (
        "Hair_Default",
        2100001,
        "Hair",
        "Uncommon",
        "EVE_HR_01",
        "None",
        "Item/Hair_Icon_01",
    ),
    (
        "Ear_19",
        2300019,
        "EarRing",
        "Legendary",
        "ACC_EAR_19M",
        "None",
        "Item/Ear_Icon_19",
    ),
    (
        "Drone_Default",
        2800001,
        "Drone",
        "Common",
        "CH_NPC_Drone",
        "None",
        "Item/Drone_Icon_01",
    ),
    (
        "Drone_Bear",
        2800006,
        "Drone",
        "Epic",
        "CH_NPC_Drone_Bear",
        "None",
        "Item/Drone_Icon_06",
    ),
    (
        "Glasses_X",
        2200404,
        "FaceAccessory",
        "Rare",
        "ACC_Glass_404",
        "None",
        "Item/Glasses_Icon_404",
    ),
    (
        "Gear_1",
        103010,
        "Gear",
        "Common",
        "None",
        "None",
        "Item/Gear_Icon_01",
    ),
    (
        "Sword_Skin",
        1,
        "Weapon",
        "Common",
        "CH_W_Sword_01",
        "None",
        "Item/Sword_Icon",
    ),
];

/// The materials' JSON, by name; a name absent here has no exported material.
fn materials() -> Vec<(&'static str, Value)> {
    let t = texture_ref;
    vec![
        (
            "MI_Basebody_V02_F2",
            json!({"Textures": {"BaseColor": t("T_Body_D"), "BaseNormal": t("T_Body_N"), "ORSS Mask": t("T_Body_ORSS")},
            "Colors": {"BaseTint": color(0.9, 0.7, 0.6)}, "Scalars": {"MinRoughness": 0.228571, "MaxRoughness": 1.107784, "BaseSSS": 1.067536}}),
        ),
        (
            "MI_EVE_Head_V02_Jali",
            json!({"Textures": {"PM_Diffuse": t("T_Head_D"), "BaseNormal": t("T_Head_N"), "ORSS Mask": t("T_Head_ORSS")},
            "Colors": {"BaseTint": color(1.0, 0.8, 0.75)}, "Scalars": {"BaseSSS": 0.5}}),
        ),
        (
            "CH_P_EVE_52_TypeB",
            json!({"Textures": {"Basecolor": t("T_Suit52_D"), "Baked NormalMap": t("T_Absent"), "PM_Normals": t("T_Suit52_N"),
            "ORM": t("T_Suit52_ORM"), "Emissive": t("T_Suit52_E")}, "Colors": {"EmissiveColor": color(0.2, 0.6, 1.0)},
            "Scalars": {"Roughness Min": 0.1, "Roughness Max": 0.8, "Metallic": 0.5}}),
        ),
        ("MI_Teeth", json!({"Textures": {"BaseColor": t("T_Teeth")}})),
        (
            "MI_EyeRefractive1",
            json!({"Textures": {"ScleraColor": t("T_Sclera"), "IrisColor": t("T_Iris")}, "Scalars": {"Iris UV Radius": 0.2}}),
        ),
        (
            "MI_EyeBrow1",
            json!({"Textures": {"Mask": t("T_Brow_Mask")}, "Colors": {"BaseColour": color(0.05, 0.03, 0.02)}}),
        ),
        (
            "MI_EVE_HR_01_TypeA",
            json!({"Textures": {"HairTexture": t("T_Hair_Strands"), "Alpha": t("T_Hair_Alpha")},
            "Colors": {"RootColorBlended": color(0.02, 0.015, 0.01), "TipColorBlended": color(0.3, 0.2, 0.12)}}),
        ),
        (
            "MI_EVE_HR_01_Tail_TypeA",
            json!({"Textures": {"HairTexture": t("T_Tail_Strands"), "Alpha": t("T_Tail_Alpha")},
            "Colors": {"RootColorBlended": color(0.05, 0.04, 0.03), "TipColorBlended": color(0.5, 0.4, 0.3)}}),
        ),
        (
            "CH_W_Sword_02_Blade_Body",
            json!({"Textures": {"BaseColor": t("T_Blade_D"), "Normal": t("T_Blade_N"), "AoRM": t("T_Blade_ORM")}}),
        ),
        (
            "CH_W_Sword_02_Blade_Edge",
            json!({"Textures": {"PM_Diffuse": t("T_Edge_D"), "PM_Normals": t("T_Blade_N"), "AoRM": t("T_Blade_ORM")},
            "Scalars": {"Roughness Min": 0.2}}),
        ),
        // A listed material whose surface map fails after its base and normal were written: nothing of it may stay.
        (
            "MI_ACC_EAR_19M",
            json!({"Textures": {"Basecolor": t("T_Ear_D"), "Baked NormalMap": t("T_Ear_N")}}),
        ),
        ("MI_ACC_EAR_19M_Emissive", json!({})),
        ("NewMaterial", json!({})),
        (
            "Drone_M_Inst",
            json!({"Textures": {"Base Color Texture": t("T_Drone_D"), "Base Normal Texture": t("T_Drone_N"), "O R M": t("T_Drone_ORM"),
            "Emissive Texture": t("T_Drone_E")}, "Colors": {"EmissiveColor": color(1.0, 0.1, 0.0)}}),
        ),
        (
            "Drone_M_2_Inst",
            json!({"Textures": {"Base Color Texture": t("T_Drone2_D"), "PM_Normals": t("T_Drone_N"), "PM_SpecularMasks": t("T_Drone_ORM")}}),
        ),
        (
            "MI_CH_NPC_Drone_06",
            json!({"Textures": {"Basecolor": t("T_Bear_D"), "Baked NormalMap": t("T_Bear_N"), "ORM": t("T_Bear_ORM"),
            "Emissive": t("T_Bear_E")}, "Colors": {"EmissiveColor": color(0.0, 0.0, 0.0)}, "Scalars": {"Metallic": 2.0}}),
        ),
        (
            "MI_CH_NPC_Drone_06_Decal",
            json!({"Textures": {"Basecolor": t("T_Decal_D"), "Baked NormalMap": t("T_Bear_N"), "ORM": t("T_Bear_ORM")}}),
        ),
        (
            "MI_EVE_Eyeshadow_Occlusion",
            json!({"Scalars": {"Shadow top": 0.7, "Shadow top exponent": 2.0, "Shadow bottom": 0.3,
            "Shadow bottom exponent": 0.5, "Shadow inner corner": 0.4, "Shadow inner corner exponent": 1.7, "Shadow outer corner": 0.2,
            "Shadow outer corner exponent": 0.0, "Shadow amount": 0.8, "Opacity": 0.9}, "Colors": {"Shadow_Color": color(0.1, 0.05, 0.04)}}),
        ),
        (
            "EyeLight_Inst",
            json!({"Textures": {"EyeLight": t("T_EyeLight")}}),
        ),
        ("MI_EVE_Tearline", json!({})),
        (
            "MA_TeethOcculusion_Inst1",
            json!({"Scalars": {"Opacity": 0.5}}),
        ),
        (
            "M_MikeEyeBlend_Inst",
            json!({"Colors": {"Outer color": color(0.8, 0.75, 0.7), "Inner color": color(0.6, 0.2, 0.25)}}),
        ),
        (
            "M_Film",
            json!({"IsTranslucent": true, "Textures": {"Base": t("T_Suit_Base")}}),
        ),
        (
            "M_HairFam",
            json!({"Textures": {"HairTexture": t("T_Fam_Strands"), "Alpha": t("T_Black")},
            "Colors": {"RootColorBlended": color(0.6, 0.6, 0.65), "TipColorBlended": color(0.9, 0.9, 0.95)}}),
        ),
        (
            "M_Skin",
            json!({"Textures": {"BaseColor": t("T_Skin_D"), "PM_Normals": t("T_Skin_N"), "ORSS Mask": t("T_Skin_ORSS")},
            "Colors": {"BaseTint": color(0.95, 0.85, 0.8)}}),
        ),
        (
            "M_Fur",
            json!({"Textures": {"FUR_Base": t("T_Fur")}, "Colors": {"FUR_FurColor": color(0.4, 0.3, 0.2)}}),
        ),
        (
            "M_Suit",
            json!({"BlendMode": 0, "Textures": {"Base": t("T_Suit_Base"), "Normal": t("T_Suit_N"), "PM_SpecularMasks": t("T_Suit_ORM"),
            "PM_Emissive": t("T_Suit_E")}, "Colors": {"EmissiveColor": color(5.0, 0.5, 0.0)}, "Scalars": {"Roughness Max": 0.6}}),
        ),
        (
            "M_Masked",
            json!({"BlendMode": 1, "Textures": {"BaseTexture": t("T_Masked"), "AoRM": t("T_Blade_ORM"), "Emissive": t("T_Suit_E")},
            "Colors": {"EmissiveColor": color(0.0, 0.0, 0.0)}}),
        ),
        (
            "M_Flat",
            json!({"Textures": {"Base": t("T_Absent")}, "Colors": {"Color": color(3.0, 1.0, 0.2)}}),
        ),
        ("M_Gray", json!({"Textures": {}, "Colors": {}})),
    ]
}

/// Texture name, size, seed; grayscale ones are written as 8-bit gray PNGs.
const TEXTURES: &[(&str, u32, u32, u32)] = &[
    ("T_Body_D", 24, 20, 1),
    ("T_Body_N", 24, 20, 2),
    ("T_Body_ORSS", 12, 10, 3),
    ("T_Head_D", 16, 16, 4),
    ("T_Head_N", 16, 16, 5),
    ("T_Head_ORSS", 16, 16, 6),
    ("T_Suit52_D", 20, 12, 7),
    ("T_Suit52_N", 20, 12, 8),
    ("T_Suit52_ORM", 10, 6, 9),
    ("T_Suit52_E", 10, 6, 10),
    ("T_Teeth", 8, 8, 11),
    ("T_Sclera", 20, 20, 12),
    ("T_Iris", 16, 12, 13),
    ("T_Hair_Strands", 24, 20, 15),
    ("T_Hair_Alpha", 12, 10, 16),
    ("T_Tail_Strands", 16, 32, 17),
    ("T_Tail_Alpha", 16, 32, 18),
    ("T_Blade_D", 12, 30, 19),
    ("T_Blade_N", 12, 30, 20),
    ("T_Blade_ORM", 12, 30, 21),
    ("T_Edge_D", 12, 30, 22),
    ("T_Ear_D", 8, 8, 23),
    ("T_Ear_N", 8, 8, 24),
    ("T_Drone_D", 18, 18, 25),
    ("T_Drone_N", 18, 18, 26),
    ("T_Drone_ORM", 9, 9, 27),
    ("T_Drone_E", 9, 9, 28),
    ("T_Drone2_D", 14, 10, 29),
    ("T_Bear_D", 16, 16, 30),
    ("T_Bear_N", 16, 16, 31),
    ("T_Bear_ORM", 16, 16, 32),
    ("T_Bear_E", 16, 16, 33),
    ("T_Decal_D", 16, 16, 34),
    ("T_EyeLight", 10, 10, 35),
    ("T_Fam_Strands", 20, 20, 36),
    ("T_Skin_D", 16, 16, 37),
    ("T_Skin_N", 16, 16, 38),
    ("T_Skin_ORSS", 16, 16, 39),
    ("T_Fur", 12, 12, 40),
    ("T_Suit_Base", 2100, 10, 41),
    ("T_Suit_N", 30, 30, 42),
    ("T_Suit_ORM", 1100, 40, 43),
    ("T_Suit_E", 30, 30, 44),
    ("T_Masked", 20, 20, 45),
];
const GRAY: &[&str] = &["T_Brow_Mask", "T_Black"];

/// Writes the synthetic game under `game`: `json/`, `raw/`, `avatar/` (with `meshes.txt`) and `export/`.
pub fn game(game: &Path) {
    let export = game.join("export");
    let content = export.join("SB/Content");
    // Item tables and names.
    let mut items = serde_json::Map::new();
    let mut equipment = serde_json::Map::new();
    for &(alias, id, category, rarity, mesh, long, icon) in ITEMS {
        let package = |name: &str| {
            MESHES
                .iter()
                .find(|m| m.0 == name)
                .map(|m| format!("SkeletalMesh'/Game/{}.{name}'", &m.1["SB/Content/".len()..]))
                .unwrap_or_else(|| format!("/Game/Art/Character/PC/{name}/{name}.{name}"))
        };
        let equip = if mesh == "None" {
            "None".to_string()
        } else {
            format!("Eq_{alias}")
        };
        items.insert(alias.into(), json!({"ID": id, "Category": format!("ESBItemCategory::Category_{category}"),
            "Rarity": format!("ESBItemRarityType::Rarity_{rarity}"), "EquipmentAlias": equip, "EquipmentAlias2": "None",
            "Asset2D": format!("/Game/Art/UI/Texture/{icon}.{}", last(icon)), "Name": format!("N_{alias}"),
            "DescContent": format!("C_{alias}"), "DescFunction": format!("F_{alias}")}));
        if mesh != "None" {
            let long = if long == "None" {
                "None".to_string()
            } else {
                package(long)
            };
            equipment.insert(equip, json!({"MountingResourcePath": package(mesh), "MountingResourcePathWhenLongHair": long}));
        }
    }
    let strings = json!([{"StringTable": {"KeysToEntries": {"N_Gear_1": "Engrenage   source"}}}]);
    write(
        &game.join("json/ItemTable.uasset.json"),
        json!([{"Rows": items}]).to_string(),
    );
    write(
        &game.join("json/EquipmentTable.uasset.json"),
        json!([{"Rows": equipment}]).to_string(),
    );
    write(
        &game.join("json/ItemStringTable.uasset.json"),
        strings.to_string(),
    );
    let fr: Vec<(String, String)> = ITEMS
        .iter()
        .filter(|i| i.0 != "Gear_1" && i.0 != "Drone_Bear")
        .flat_map(|i| {
            [
                (format!("N_{}", i.0), format!("Tenue\t{} été", i.0)),
                (format!("C_{}", i.0), format!("Contenu\n{}", i.0)),
            ]
        })
        .collect();
    let en: Vec<(String, String)> = ITEMS
        .iter()
        .flat_map(|i| {
            [
                (format!("N_{}", i.0), format!("Outfit {}", i.0)),
                (format!("F_{}", i.0), format!("Function {}", i.0)),
            ]
        })
        .filter(|(k, _)| k != "N_Gear_1")
        .collect();
    fn pairs(v: &[(String, String)]) -> Vec<(&str, &str)> {
        v.iter().map(|(a, b)| (a.as_str(), b.as_str())).collect()
    }
    write(
        &game.join("raw/fr.locres"),
        locres_bytes("ItemStringTable", &pairs(&fr)),
    );
    write(
        &game.join("raw/en.locres"),
        locres_bytes("ItemStringTable", &pairs(&en)),
    );

    // Meshes, their list and their materials.
    let mut list = String::new();
    for &(name, path, bones, sections) in MESHES {
        write(
            &game.join(format!("avatar/{name}.sbav")),
            mesh(bones, sections),
        );
        list += &format!("MESH {name}.sbav {path} pakchunk0-Windows\n");
        for section in sections {
            let folder = if name == "CH_P_EVE_78" && *section == "M_Suit" {
                "Mat2"
            } else {
                "Mat"
            };
            list +=
                &format!("MATERIAL {name}.sbav {section} /Game/Art/{folder}/{section}.{section}\n");
        }
    }
    write(&game.join("avatar/meshes.txt"), list);
    for (name, value) in materials() {
        write(
            &content.join(format!("Art/Mat/{name}.json")),
            value.to_string(),
        );
    }
    for &(name, w, h, seed) in TEXTURES {
        png(
            &content.join(format!("Art/Tex/{name}.png")),
            &pattern(w, h, seed),
        );
    }
    for (seed, name) in GRAY.iter().enumerate() {
        let gray = GrayImage::from_fn(12, 10, |x, y| {
            image::Luma([if *name == "T_Black" {
                0
            } else {
                ((x * 37 + y * 11 + seed as u32) % 256) as u8
            }])
        });
        fs::create_dir_all(content.join("Art/Tex")).unwrap();
        gray.save(content.join(format!("Art/Tex/{name}.png")))
            .unwrap();
    }
    // The core step's textures: effect sprites (one larger than its limit), the trail, HUD icons to trim.
    for (i, path) in CORE_TEXTURES.iter().enumerate() {
        let seed = 50 + i as u32;
        let image = match last(path) {
            "T_A_SparkParticle_01" => pattern(140, 130, seed),
            "T_A_AnimTrail_01" => pattern(30, 20, seed),
            "T_A_StreakNoise_05" => pattern(16, 16, seed),
            name if name.starts_with("T_") => pattern(24, 18, seed),
            "Tk_Idle_Base2" => pattern(32, 32, seed),
            _ => icon(40, 30, seed, (5 + i as u32 % 4, 3, 30, 22 + i as u32 % 5)),
        };
        png(&export.join(format!("{path}.png")), &image);
    }
    // Item and chrome icons; Ear_Icon_19 and the last chrome icon were never exported.
    for (i, item) in ITEMS
        .iter()
        .enumerate()
        .filter(|(_, i)| i.6 != "Item/Ear_Icon_19")
    {
        png(
            &content.join(format!("Art/UI/Texture/{}.png", item.6)),
            &icon(48, 48, 80 + i as u32, (4, 6, 44, 40)),
        );
    }
    for (i, path) in CHROME.iter().enumerate().take(CHROME.len() - 1) {
        png(
            &export.join(format!("{path}.png")),
            &icon(32, 32, 90 + i as u32, (2, 2, 30, 30)),
        );
    }
    let skeleton = json!([{"Type": "Skeleton", "Name": "CH_W_Sword_01_Skeleton", "Sockets": [
        {"Type": "SkeletalMeshSocket", "Properties": {"SocketName": "FX_Trail_Begin", "BoneName": "Bone_Blade",
            "RelativeLocation": {"X": 1.5, "Y": 0.0, "Z": -3.0517578125e-05}}},
        {"Type": "SkeletalMeshSocket", "Properties": {"SocketName": "FX_Trail_End", "BoneName": "Bone_Tip",
            "RelativeLocation": {"X": 95.25, "Y": 2, "Z": 0.1}}},
    ]}]);
    write(
        &game.join("json/CH_W_Sword_01_Skeleton.json"),
        skeleton.to_string(),
    );
}

/// A fake Paks folder.
pub fn paks(dir: &Path) {
    write(&dir.join("pakchunk0-Windows.utoc"), "toc");
    write(&dir.join("pakchunk0-Windows.ucas"), "cas");
    write(&dir.join("~mods/Outfit_P.pak"), "mod");
}

/// Copies the files under `from` that `keep` takes (by their path under `root`, '/'-joined).
fn copy_tree(root: &Path, from: &Path, to: &Path, keep: &dyn Fn(&str) -> bool) {
    for entry in fs::read_dir(from).unwrap().flatten() {
        let target = to.join(entry.file_name());
        if entry.file_type().unwrap().is_dir() {
            copy_tree(root, &entry.path(), &target, keep);
        } else if keep(&crate::safety::slash(entry.path().strip_prefix(root).unwrap()).unwrap()) {
            fs::create_dir_all(to).unwrap();
            fs::copy(entry.path(), target).unwrap();
        }
    }
}

/// A stand-in for EveExtract: mode, paths, each output line.
pub trait Extractor: FnMut(&str, &[String], &mut dyn FnMut(&str)) -> Result<()> {}
impl<F: FnMut(&str, &[String], &mut dyn FnMut(&str)) -> Result<()>> Extractor for F {}

/// EveExtract over the synthetic game: what each mode writes into `work`, with its output lines. `calls` records
/// the modes and paths.
pub fn extract<'a>(
    game: &'a Path,
    work: &'a Path,
    calls: &'a std::sync::Mutex<Vec<(String, Vec<String>)>>,
) -> impl Extractor + 'a {
    move |mode, paths, line| {
        calls
            .lock()
            .unwrap()
            .push((mode.to_string(), paths.to_vec()));
        match mode {
            "json" => {
                for path in paths {
                    let name = format!("{}.json", last(path));
                    let source = game.join("json").join(&name);
                    if source.exists() {
                        write(&work.join("json").join(&name), fs::read(source)?);
                        line(&format!("OK\t{path}\t{name}"));
                    } else {
                        line(&format!("FAIL\t{path}\tno game file"));
                    }
                }
            }
            "raw" => {
                for path in paths {
                    let culture = path.split('/').nth(4).unwrap_or_default();
                    match fs::read(game.join(format!("raw/{culture}.locres"))) {
                        Ok(bytes) => {
                            write(&work.join("raw/Game.locres"), bytes);
                            line(&format!("OK\t{path}\tGame.locres"));
                        }
                        Err(_) => line(&format!("FAIL\t{path}\tno game file")),
                    }
                }
            }
            "avatar" => {
                let list = fs::read_to_string(game.join("avatar/meshes.txt"))?;
                for path in paths {
                    let name = last(path);
                    let Ok(bytes) = fs::read(game.join(format!("avatar/{name}.sbav"))) else {
                        line(&format!("FAIL\t{path}\tno game file"));
                        continue;
                    };
                    write(&work.join(format!("avatar/{name}.sbav")), bytes);
                    let mut mine: String = list
                        .lines()
                        .filter(|l| l.split(' ').nth(1) == Some(&format!("{name}.sbav")))
                        .map(|l| format!("{l}\n"))
                        .collect();
                    if let Ok(before) = fs::read_to_string(work.join("avatar/meshes.txt")) {
                        mine = before + &mine;
                    }
                    write(&work.join("avatar/meshes.txt"), mine);
                    line(&format!(
                        "OK\t{path}\t{name}.sbav: 3 bones, 1 vertices, 1 triangles, 1 sections, 0 morphs (0 deltas)"
                    ));
                }
            }
            "export" => {
                // As EveExtract: a mesh brings the materials its sections name, no other; textures and icons are
                // copied whole (the bakes find them by their references).
                let list = fs::read_to_string(game.join("avatar/meshes.txt"))?;
                let materials: Vec<String> = list
                    .lines()
                    .filter_map(|l| l.strip_prefix("MATERIAL "))
                    .filter_map(|l| l.split_once(' '))
                    .filter(|(mesh, _)| paths.iter().any(|p| *mesh == format!("{}.sbav", last(p))))
                    .filter_map(|(_, rest)| rest.rsplit_once(' '))
                    .filter_map(|(_, path)| path.strip_prefix("/Game/"))
                    .filter_map(|path| path.rsplit_once('.'))
                    .map(|(path, _)| format!("SB/Content/{path}.json"))
                    .collect();
                let export = game.join("export");
                copy_tree(&export, &export, &work.join("export"), &|file| {
                    !file.ends_with(".json") || materials.iter().any(|m| m == file)
                });
                paths
                    .iter()
                    .for_each(|p| line(&format!("OK\t{p}.{}\t{}.png", last(p), last(p))));
            }
            other => anyhow::bail!("fake EveExtract: no mode {other}"),
        }
        Ok(())
    }
}

/// The folder a test keeps its game, Paks, work and home in.
pub struct Setup {
    pub root: tempfile::TempDir,
    pub game: PathBuf,
    pub paks: PathBuf,
}

impl Setup {
    pub fn new() -> Setup {
        let root = tempfile::tempdir().unwrap();
        let base = crate::platform::canonicalize(root.path()).unwrap();
        let (game_dir, paks_dir) = (base.join("game"), base.join("Paks"));
        game(&game_dir);
        paks(&paks_dir);
        Setup {
            root,
            game: game_dir,
            paks: paks_dir,
        }
    }

    pub fn build(&self, every: bool) -> super::run::Build {
        let base = crate::platform::canonicalize(self.root.path()).unwrap();
        super::run::Build {
            sidecar: super::extract::Sidecar {
                program: "EveExtract".into(),
                paks: self.paks.clone(),
                usmap: "Mappings.usmap".into(),
                oodle: "oodle".into(),
                bundle: None,
            },
            home: base.join("home"),
            work: base.join("work"),
            save_dirs: vec![base.join("SaveGames")],
            culture: "fr".into(),
            every,
            build_id: Some("24463856".into()),
        }
    }
}

/// Writes the synthetic game to `$SBMC_PACK_FIXTURE` for a reference run of the private `pack.py`, with this
/// builder's own pack beside it in `home/avatar`.
#[test]
#[ignore]
fn dump() {
    if let Some(dir) = std::env::var_os("SBMC_PACK_FIXTURE") {
        let dir = crate::platform::canonicalize(Path::new(&dir)).unwrap();
        game(&dir.join("game"));
        paks(&dir.join("Paks"));
        let build = super::run::Build {
            sidecar: super::extract::Sidecar {
                program: "EveExtract".into(),
                paks: dir.join("Paks"),
                usmap: "Mappings.usmap".into(),
                oodle: "oodle".into(),
                bundle: None,
            },
            home: dir.join("home"),
            work: dir.join("work"),
            save_dirs: vec![],
            culture: "fr".into(),
            every: true,
            build_id: None,
        };
        let calls = std::sync::Mutex::new(Vec::new());
        let game_dir = dir.join("game");
        let mut fake = extract(&game_dir, &build.work, &calls);
        let cancel = std::sync::atomic::AtomicBool::new(false);
        super::run::run_with(&build, &|| Ok(()), &cancel, &|_| {}, &mut fake).unwrap();
    }
}

/// Not a test: EveExtract over the synthetic game as a child process, for the window's build test. The arguments come
/// one per line in `SBMC_FAKE_EVE_EXTRACT`, the game folder in `SBMC_FAKE_GAME` (see [`sidecar_script`]). It checks its
/// inputs as EveExtract does (exit 3) and prints EveExtract's lines.
#[test]
#[ignore]
fn fake_eve_extract() {
    let (Ok(args), Ok(game)) = (
        std::env::var("SBMC_FAKE_EVE_EXTRACT"),
        std::env::var("SBMC_FAKE_GAME"),
    ) else {
        return;
    };
    let args: Vec<&str> = args.lines().collect();
    let value = |flag: &str| {
        let at = args.iter().position(|a| *a == flag).unwrap();
        PathBuf::from(args[at + 1])
    };
    for flag in ["--paks", "--usmap", "--oodle"] {
        if !value(flag).exists() {
            eprintln!("eve-extract: no {flag} input");
            std::process::exit(3);
        }
    }
    assert!(
        std::env::var_os("DOTNET_BUNDLE_EXTRACT_BASE_DIR").is_some_and(|d| Path::new(&d).is_dir())
    );
    let (mode, paths): (&str, Vec<String>) =
        (args[8], args[9..].iter().map(|p| p.to_string()).collect());
    let calls = std::sync::Mutex::new(Vec::new());
    let out = value("--out");
    let mut fake = extract(Path::new(&game), &out, &calls);
    println!("MOUNTED\t3\t10");
    fake(mode, &paths, &mut |line| println!("{line}")).unwrap();
    println!("DONE\t0\t0");
    std::process::exit(0);
}

/// A shell script that runs [`fake_eve_extract`] in this test binary over the synthetic game in `game`.
#[cfg(unix)]
pub fn sidecar_script(game: &Path) -> Vec<u8> {
    let exe = std::env::current_exe().unwrap();
    format!(
        "#!/bin/sh\nexport SBMC_FAKE_EVE_EXTRACT=\"$(printf '%s\\n' \"$@\")\" SBMC_FAKE_GAME='{}'\nexec '{}' --ignored --exact --nocapture --test-threads 1 pack::fixture::fake_eve_extract\n",
        game.display(),
        exe.display()
    )
    .into_bytes()
}
