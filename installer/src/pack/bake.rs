//! The texture bakes: one RGBA base texture per material slot, `<slot>.png`, and optional maps beside it:
//! `<slot>.normal.png` (Unreal tangent space, +Y along +V), `<slot>.surface.png` (occlusion, roughness, metallic,
//! specular), `<slot>.emissive.png` (RGB), `<slot>.scatter.png` (subsurface color times mask) and `<slot>.hair.png`
//! (root to tip, strand id, depth). The shader cuts out alpha below 0.5; hair blends its soft edges below that.
//! Translucent slots ship `<slot>.overlay.png` instead of `<slot>.png`: premultiplied RGB (alpha 0 adds light) drawn
//! after the opaque pass. `<slot>.glow.png` is such an overlay that Minecraft draws only while the game raises the
//! slot's streamed Opacity, tinting its emissive map by the streamed Emissive_Color and Emissive_Intensity. A slot with
//! none is not drawn.
//!
//! Effect sprites ship as `fx.<texture>.png`: the game's additive effect textures reduced to their brightness in R
//! (with G, B equal). HUD textures and item icons ship as `hud.<texture>.png` (64²). The sword carries
//! `<mesh>.trail.txt` (sockets from its skeleton) and `<mesh>.trail.png` (anim-trail gradient in R, streak noise in G).
//!
//! A port of the private `pack.py` (numpy and Pillow): the arithmetic is the same in f64, values are truncated as
//! numpy's `astype(uint8)` does, and resizing is Pillow's (premultiplied for RGBA, fixed-point separable filters), so
//! the pixels match `pack.py`'s on the same inputs.

use anyhow::{Context, Result, anyhow, bail};
use image::RgbaImage;
use serde_json::Value;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

// ---------------------------------------------------------------------------------------------------------------
// Exported files.

/// EveExtract's `export/` tree: files by lower-case path under it (references and package paths differ in case).
#[derive(Default)]
pub struct Export {
    files: HashMap<String, PathBuf>,
}

impl Export {
    pub fn scan(root: &Path) -> Result<Export> {
        let mut files = HashMap::new();
        if root.is_dir() {
            crate::safety::walk(root, &mut |relative, metadata| {
                if metadata.is_file() {
                    files.insert(
                        crate::safety::slash(relative)?.to_lowercase(),
                        root.join(relative),
                    );
                }
                Ok(())
            })?;
        }
        Ok(Export { files })
    }

    /// An object path (`/Game/A/B.B`, `/Engine/...`) or package path → its exported file with `suffix`.
    pub fn find(&self, path: &str, suffix: &str) -> Option<&Path> {
        let path = match path.starts_with('/') {
            true => path.rsplit_once('.').map_or(path, |(p, _)| p),
            false => path,
        };
        let path = if let Some(rest) = path.strip_prefix("/Game/") {
            format!("SB/Content/{rest}")
        } else if let Some(rest) = path.strip_prefix("/Engine/") {
            format!("Engine/Content/{rest}")
        } else {
            path.to_string()
        };
        self.files
            .get(&(path + suffix).to_lowercase())
            .map(PathBuf::as_path)
    }

    /// Any exported file named exactly `name`.
    fn named(&self, name: &str) -> Option<&Path> {
        self.files
            .values()
            .find(|p| p.file_name().is_some_and(|n| n == name))
            .map(PathBuf::as_path)
    }

    /// An exported PNG by its file stem: the effect, HUD and trail textures have unique names.
    pub fn texture(&self, stem: &str) -> Result<&Path> {
        self.named(&format!("{stem}.png"))
            .with_context(|| format!("texture {stem}: not exported"))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// numpy and Pillow arithmetic.

/// numpy's `astype(uint8)` on the values the bakes produce: truncation toward zero.
fn u8t(value: f64) -> u8 {
    value as u8
}

/// numpy's `array ** e` for a scalar `e`: square and square root are special-cased, the rest is libm's `pow`.
fn npow(x: f64, e: f64) -> f64 {
    if e == 2.0 {
        x * x
    } else if e == 0.5 {
        x.sqrt()
    } else if e == 1.0 {
        x
    } else {
        x.powf(e)
    }
}

/// Linear 0..1 → sRGB bytes.
fn srgb(lin: f64) -> f64 {
    let lin = lin.clamp(0.0, 1.0);
    (if lin <= 0.0031308 {
        lin * 12.92
    } else {
        1.055 * npow(lin, 1.0 / 2.4) - 0.055
    }) * 255.0
}

/// sRGB bytes → linear 0..1.
fn linear(byte: f64) -> f64 {
    let c = byte / 255.0;
    if c <= 0.04045 {
        c / 12.92
    } else {
        npow((c + 0.055) / 1.055, 2.4)
    }
}

/// An image of `width` × `height` from a pixel function.
fn image(width: u32, height: u32, pixel: impl Fn(u32, u32) -> [u8; 4]) -> RgbaImage {
    RgbaImage::from_fn(width, height, |x, y| image::Rgba(pixel(x, y)))
}

/// Pillow's resampling filters.
#[derive(Debug, Clone, Copy)]
pub enum Filter {
    Bicubic,
    Lanczos,
}

const PRECISION_BITS: u32 = 22;

impl Filter {
    fn support(self) -> f64 {
        match self {
            Filter::Bicubic => 2.0,
            Filter::Lanczos => 3.0,
        }
    }

    fn weight(self, x: f64) -> f64 {
        fn sinc(x: f64) -> f64 {
            if x == 0.0 {
                return 1.0;
            }
            let x = x * std::f64::consts::PI;
            x.sin() / x
        }
        match self {
            Filter::Bicubic => {
                let a = -0.5;
                let x = x.abs();
                if x < 1.0 {
                    ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
                } else if x < 2.0 {
                    (((x - 5.0) * x + 8.0) * x - 4.0) * a
                } else {
                    0.0
                }
            }
            Filter::Lanczos if (-3.0..3.0).contains(&x) => sinc(x) * sinc(x / 3.0),
            Filter::Lanczos => 0.0,
        }
    }

    /// Pillow's `precompute_coeffs` and `normalize_coeffs_8bpc`: per output pixel, its first input pixel and its
    /// fixed-point weights.
    fn coefficients(self, input: u32, output: u32) -> Vec<(usize, Vec<i32>)> {
        let scale = input as f64 / output as f64;
        let filterscale = scale.max(1.0);
        let support = self.support() * filterscale;
        let ss = 1.0 / filterscale;
        (0..output)
            .map(|xx| {
                let center = (xx as f64 + 0.5) * scale;
                let xmin = ((center - support + 0.5) as i64).max(0);
                let xmax = ((center + support + 0.5) as i64).min(input as i64) - xmin;
                let mut k: Vec<f64> = (0..xmax.max(0))
                    .map(|x| self.weight(((x + xmin) as f64 - center + 0.5) * ss))
                    .collect();
                let ww: f64 = k.iter().sum();
                if ww != 0.0 {
                    k.iter_mut().for_each(|w| *w /= ww);
                }
                let fixed = k
                    .iter()
                    .map(|&w| {
                        let w = w * (1u32 << PRECISION_BITS) as f64;
                        (if w < 0.0 { w - 0.5 } else { w + 0.5 }) as i32
                    })
                    .collect();
                (xmin as usize, fixed)
            })
            .collect()
    }
}

fn clip8(sum: i32) -> u8 {
    (sum >> PRECISION_BITS).clamp(0, 255) as u8
}

/// One separable pass over 4-byte pixels: along x when `horizontal`, else along y.
fn pass(
    pixels: &[u8],
    (width, height): (u32, u32),
    out_size: u32,
    horizontal: bool,
    filter: Filter,
) -> Vec<u8> {
    let (w, h) = (width as usize, height as usize);
    let coefficients = filter.coefficients(if horizontal { width } else { height }, out_size);
    let (ow, oh) = if horizontal {
        (out_size as usize, h)
    } else {
        (w, out_size as usize)
    };
    let mut out = vec![0u8; ow * oh * 4];
    for y in 0..oh {
        for x in 0..ow {
            let (start, k) = &coefficients[if horizontal { x } else { y }];
            let mut sums = [1i32 << (PRECISION_BITS - 1); 4];
            for (i, weight) in k.iter().enumerate() {
                let at = if horizontal {
                    (y * w + start + i) * 4
                } else {
                    ((start + i) * w + x) * 4
                };
                for c in 0..4 {
                    sums[c] += pixels[at + c] as i32 * weight;
                }
            }
            let o = (y * ow + x) * 4;
            for c in 0..4 {
                out[o + c] = clip8(sums[c]);
            }
        }
    }
    out
}

/// Pillow's `Image.resize` of an RGBA image (premultiplied while filtered) or, with `rgba` false, of the RGB channels
/// alone (the result's alpha is then meaningless).
pub fn resize(
    source: &RgbaImage,
    width: u32,
    height: u32,
    filter: Filter,
    rgba: bool,
) -> RgbaImage {
    if source.dimensions() == (width, height) {
        return source.clone();
    }
    let mut pixels = source.as_raw().clone();
    if rgba {
        // rgbA2rgba: MULDIV255.
        for p in pixels.as_chunks_mut::<4>().0 {
            let alpha = p[3] as u32;
            for c in &mut p[..3] {
                let t = *c as u32 * alpha + 128;
                *c = (((t >> 8) + t) >> 8) as u8;
            }
        }
    }
    let mut size = source.dimensions();
    if width != size.0 {
        pixels = pass(&pixels, size, width, true, filter);
        size.0 = width;
    }
    if height != size.1 {
        pixels = pass(&pixels, size, height, false, filter);
    }
    if rgba {
        // rgba2rgbA.
        for p in pixels.as_chunks_mut::<4>().0 {
            let alpha = p[3] as u32;
            if alpha != 0 && alpha != 255 {
                for c in &mut p[..3] {
                    *c = ((255 * *c as u32) / alpha).min(255) as u8;
                }
            }
        }
    }
    RgbaImage::from_raw(width, height, pixels).expect("sized buffer")
}

/// A PNG as Pillow's `convert('RGBA')` reads it.
pub fn open(path: &Path) -> Result<RgbaImage> {
    Ok(image::open(path)
        .with_context(|| format!("{}: unreadable image", path.display()))?
        .to_rgba8())
}

/// `pack.py`'s `save`: wider than `limit` (on either side) → `limit` wide, Lanczos; then a PNG in `folder`.
pub fn save(image: &RgbaImage, folder: &Path, name: &str, limit: u32) -> Result<()> {
    let (w, h) = image.dimensions();
    let resized;
    let image = if w.max(h) > limit {
        resized = resize(
            image,
            limit,
            (limit as u64 * h as u64 / w as u64) as u32,
            Filter::Lanczos,
            true,
        );
        &resized
    } else {
        image
    };
    write_png(image, &folder.join(name))
}

pub fn write_png(image: &RgbaImage, path: &Path) -> Result<()> {
    use image::ImageEncoder;
    use image::codecs::png::{CompressionType, FilterType, PngEncoder};
    let file = std::io::BufWriter::new(fs::File::create(path)?);
    PngEncoder::new_with_quality(file, CompressionType::Default, FilterType::Adaptive)
        .write_image(
            image.as_raw(),
            image.width(),
            image.height(),
            image::ExtendedColorType::Rgba8,
        )
        .with_context(|| format!("{}: not written", path.display()))
}

// ---------------------------------------------------------------------------------------------------------------
// Materials.

/// A material's parameter JSON (EveExtract `export`) and where its textures are.
pub struct Material<'a> {
    pub name: &'a str,
    data: Value,
    export: &'a Export,
}

impl<'a> Material<'a> {
    /// The material's JSON: by its object path from the mesh list, else any exported `<name>.json`.
    pub fn load(
        name: &'a str,
        object_path: Option<&str>,
        export: &'a Export,
    ) -> Result<Material<'a>> {
        let file = match object_path {
            Some(path) => export.find(path, ".json"),
            None => export.named(&format!("{name}.json")),
        };
        let file = file.ok_or_else(|| anyhow!("{name}: no exported material"))?;
        let data = serde_json::from_slice(&fs::read(file)?)
            .with_context(|| format!("{name}: material JSON unreadable"))?;
        Ok(Material { name, data, export })
    }

    fn texture_file(&self, key: &str) -> Option<&Path> {
        let path = self
            .data
            .get("Textures")?
            .get(key)?
            .get("ObjectPath")?
            .as_str()?;
        (!path.is_empty())
            .then(|| self.export.find(path, ".png"))
            .flatten()
    }

    fn has(&self, keys: &[&str]) -> bool {
        keys.iter().any(|k| self.texture_file(k).is_some())
    }

    /// The first of `keys` with an exported texture.
    fn texture(&self, keys: &[&str]) -> Result<RgbaImage> {
        match keys.iter().find_map(|k| self.texture_file(k)) {
            Some(file) => open(file),
            None => bail!("{}: none of {keys:?}", self.name),
        }
    }

    fn colors(&self) -> Option<&serde_json::Map<String, Value>> {
        self.data.get("Colors").and_then(Value::as_object)
    }

    fn raw_color(&self, key: &str) -> Result<[f64; 3]> {
        let c = self.colors().and_then(|c| c.get(key));
        let c = c.with_context(|| format!("{}: no color {key}", self.name))?;
        let channel = |k: &str| {
            c.get(k)
                .and_then(Value::as_f64)
                .with_context(|| format!("{}: color {key}", self.name))
        };
        Ok([channel("R")?, channel("G")?, channel("B")?])
    }

    /// A linear material color as sRGB bytes.
    fn color(&self, key: &str) -> Result<[f64; 3]> {
        Ok(self.raw_color(key)?.map(srgb))
    }

    /// A linear HDR color (emissive tints reach 128) as sRGB bytes, scaled under 1 first so the hue survives.
    fn hdr(&self, key: &str) -> Result<[f64; 3]> {
        let c = self.raw_color(key)?.map(|v| v.max(0.0));
        let top = c.iter().copied().fold(f64::NEG_INFINITY, f64::max).max(1.0);
        Ok(c.map(|v| srgb(v / top)))
    }

    fn scalar(&self, key: &str, default: f64) -> f64 {
        self.data
            .get("Scalars")
            .and_then(|s| s.get(key))
            .and_then(Value::as_f64)
            .unwrap_or(default)
    }
}

/// Which color function an emissive mask is painted with.
#[derive(Clone, Copy)]
enum Paint {
    Color,
    Hdr,
}

/// Skin: BaseColor times the linear BaseTint, at half strength (square root): without Unreal's subsurface scattering
/// the full tint reads too dark. Normals keep 30 % of their slope for the same reason.
const SKIN_TINT: f64 = 0.5;
const SKIN_NORMAL: f64 = 0.3;
pub const MAX: u32 = 2048;

fn tinted(m: &Material) -> Result<RgbaImage> {
    let a = m.texture(&["BaseColor", "PM_Diffuse"])?;
    let tint = m.raw_color("BaseTint")?.map(|v| npow(v, SKIN_TINT));
    Ok(image(a.width(), a.height(), |x, y| {
        let p = a.get_pixel(x, y).0;
        let c = |i: usize| u8t(srgb(linear(p[i] as f64) * tint[i]));
        [c(0), c(1), c(2), 255]
    }))
}

fn opaque(mut image: RgbaImage) -> RgbaImage {
    image.pixels_mut().for_each(|p| p.0[3] = 255);
    image
}

fn base(m: &Material, keys: &[&str]) -> Result<RgbaImage> {
    Ok(opaque(m.texture(keys)?))
}

/// CH_HR_Ponytail: R strand opacity, G strand id, B depth, A root (1) to tip (0) along V. PonyTail_Alpha repeats R.
fn hair(m: &Material) -> Result<RgbaImage> {
    let strands = m.texture(&["HairTexture"])?;
    let (w, h) = strands.dimensions();
    let mut mask = resize(&m.texture(&["Alpha"])?, w, h, Filter::Bicubic, true);
    if mask.pixels().all(|p| p.0[0] == 0) {
        // A black placeholder Alpha (NieR, NIKKE hairs): the strand opacity in R.
        mask = strands.clone();
    }
    let root = m.color("RootColorBlended")?.map(linear);
    let tip = m.color("TipColorBlended")?.map(linear);
    Ok(image(w, h, |x, y| {
        let s = strands.get_pixel(x, y).0.map(|v| v as f64 / 255.0);
        let (gradient, variation) = (s[3], 0.8 + 0.4 * s[1]);
        // Unreal's hair shading scatters light many times inside the volume: the strands read darker and more
        // saturated than their albedo. A power on the linear color approximates that for Minecraft's simple lighting.
        let c = |i: usize| {
            u8t(srgb(npow(
                (tip[i] + (root[i] - tip[i]) * gradient) * variation,
                1.4,
            )))
        };
        [
            c(0),
            c(1),
            c(2),
            u8t(mask.get_pixel(x, y).0[0] as f64 / 255.0 * 255.0),
        ]
    }))
}

fn hair_map(m: &Material) -> Result<RgbaImage> {
    let s = m.texture(&["HairTexture"])?;
    Ok(image(s.width(), s.height(), |x, y| {
        let p = s.get_pixel(x, y).0;
        [p[3], p[1], p[2], 255]
    }))
}

fn normal(m: &Material, keys: &[&str], strength: f64) -> Result<RgbaImage> {
    let t = m.texture(keys)?;
    Ok(image(t.width(), t.height(), |x, y| {
        let p = t.get_pixel(x, y).0;
        let mut n = [0, 1, 2].map(|i| p[i] as f64 / 127.5 - 1.0);
        n[0] *= strength;
        n[1] *= strength;
        let length = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt().max(1e-6);
        let c = |i: usize| u8t(((n[i] / length + 1.0) * 127.5).clamp(0.0, 255.0));
        [c(0), c(1), c(2), 255]
    }))
}

/// Surface: R occlusion, G roughness, B metallic, A specular (0.5 is Unreal's default 4 % reflectance). Unreal ORM.
fn surface_orm(m: &Material, keys: &[&str]) -> Result<RgbaImage> {
    let t = m.texture(keys)?;
    let (low, high) = (
        m.scalar("Roughness Min", 0.0),
        m.scalar("Roughness Max", 1.0),
    );
    let metallic = m.scalar("Metallic", 1.0);
    Ok(image(t.width(), t.height(), |x, y| {
        let p = t.get_pixel(x, y).0.map(f64::from);
        let rough = (low + (high - low) * p[1] / 255.0) * 255.0;
        [
            p[0] as u8,
            u8t(rough.clamp(0.0, 255.0)),
            u8t((p[2] * metallic).clamp(0.0, 255.0)),
            128,
        ]
    }))
}

/// Stellar Blade skin ORSS: occlusion, roughness, specular, scattering.
fn surface_skin(m: &Material, key: &str) -> Result<RgbaImage> {
    let t = m.texture(&[key])?;
    let (low, high) = (m.scalar("MinRoughness", 0.0), m.scalar("MaxRoughness", 1.0));
    Ok(image(t.width(), t.height(), |x, y| {
        let p = t.get_pixel(x, y).0;
        let rough = ((low + (high - low) * p[1] as f64 / 255.0) * 255.0).clamp(0.0, 255.0);
        [p[0], u8t(rough), 0, p[2]]
    }))
}

fn emissive_mask(m: &Material, key: &str, color_key: &str, paint: Paint) -> Result<RgbaImage> {
    let t = m.texture(&[key])?;
    let c = match paint {
        Paint::Color => m.color(color_key)?,
        Paint::Hdr => m.hdr(color_key)?,
    };
    Ok(image(t.width(), t.height(), |x, y| {
        let mask = t.get_pixel(x, y).0[0] as f64 / 255.0;
        [u8t(mask * c[0]), u8t(mask * c[1]), u8t(mask * c[2]), 255]
    }))
}

fn solid4(rgba: [u8; 4]) -> RgbaImage {
    image(4, 4, |_, _| rgba)
}

fn solid([r, g, b]: [u8; 3]) -> RgbaImage {
    solid4([r, g, b, 255])
}

/// Subsurface color (reddish, as Unreal's skin profile) times the ORSS scattering mask.
fn scatter_skin(m: &Material, key: &str) -> Result<RgbaImage> {
    let t = m.texture(&[key])?;
    let k = m.scalar("BaseSSS", 1.0).min(1.0);
    Ok(image(t.width(), t.height(), |x, y| {
        let mask = t.get_pixel(x, y).0[3] as f64 / 255.0;
        let c = |v: f64| u8t(mask * v * k);
        [c(200.0), c(70.0), c(50.0), 255]
    }))
}

/// Unreal's eye occlusion shell, evaluated from its UVs: u from the outer (0) to the inner (1) corner, v from the
/// upper (0) to the lower (1) lid. Premultiplied shadow color, faded at the shell's outline.
fn eye_shadow(m: &Material) -> Result<RgbaImage> {
    let k = |key: &str| m.scalar(key, 0.0);
    let color = m.color("Shadow_Color")?;
    Ok(image(256, 256, |x, y| {
        let (u, v) = (x as f64 / 255.0, y as f64 / 255.0);
        let shadow = k("Shadow top") * npow(1.0 - v, k("Shadow top exponent"))
            + k("Shadow bottom") * npow(v, k("Shadow bottom exponent"))
            + k("Shadow inner corner") * npow(u, k("Shadow inner corner exponent"))
            + k("Shadow outer corner") * npow(1.0 - u, k("Shadow outer corner exponent"));
        let edge = (u.min(1.0 - u).min(v.min(1.0 - v)) * 10.0).clamp(0.0, 1.0);
        let alpha = shadow.clamp(0.0, 1.0) * k("Shadow amount") * k("Opacity") * edge;
        [
            u8t(alpha * color[0]),
            u8t(alpha * color[1]),
            u8t(alpha * color[2]),
            u8t(alpha * 255.0),
        ]
    }))
}

/// Unreal's eye blend shell over the inner corner (caruncle), evaluated from its UVs: u from the eyeball's edge (0.1)
/// to the tear duct (0.7), v from the upper (0) to the lower (1) lid. Sclera-colored where it meets the eye, pink
/// toward the duct; opacity rises from the eyeball side so the shell melts into the sclera, and fades at the lids.
fn eye_blend(m: &Material) -> Result<RgbaImage> {
    let (outer, inner) = (m.color("Outer color")?, m.color("Inner color")?);
    Ok(image(256, 256, |x, y| {
        let (u, v) = (x as f64 / 255.0, y as f64 / 255.0);
        let t = ((u - 0.1) / 0.6).clamp(0.0, 1.0);
        let alpha = ((u - 0.1) / 0.3).clamp(0.0, 1.0) * (v.min(1.0 - v) * 8.0).clamp(0.0, 1.0);
        let c = |i: usize| u8t((outer[i] * (1.0 - t) + inner[i] * t) * alpha);
        [c(0), c(1), c(2), u8t(alpha * 255.0)]
    }))
}

/// Additive catchlight: alpha 0.
fn eye_light(m: &Material) -> Result<RgbaImage> {
    let light = m.texture(&["EyeLightTexture", "EyeLight"])?;
    Ok(image(light.width(), light.height(), |x, y| {
        let p = light.get_pixel(x, y).0;
        [p[0], p[1], p[2], 0]
    }))
}

fn eye(m: &Material) -> Result<RgbaImage> {
    let sclera = resize(
        &m.texture(&["ScleraColor"])?,
        1024,
        1024,
        Filter::Bicubic,
        true,
    );
    let iris = m.texture(&["IrisColor"])?;
    let radius = m
        .data
        .get("Scalars")
        .and_then(|s| s.get("Iris UV Radius"))
        .and_then(Value::as_f64)
        .with_context(|| format!("{}: no Iris UV Radius", m.name))?;
    let (iw, ih) = (iris.width() as i64, iris.height() as i64);
    Ok(image(1024, 1024, |x, y| {
        let (du, dv) = (x as f64 / 1023.0 - 0.5, y as f64 / 1023.0 - 0.5);
        let distance = du.hypot(dv);
        let iu = (((du / (2.0 * radius) + 0.5) * (iw - 1) as f64) as i64).clamp(0, iw - 1);
        let iv = (((dv / (2.0 * radius) + 0.5) * (ih - 1) as f64) as i64).clamp(0, ih - 1);
        let blend = ((radius - distance) / 0.02).clamp(0.0, 1.0);
        let (s, i) = (
            sclera.get_pixel(x, y).0,
            iris.get_pixel(iu as u32, iv as u32).0,
        );
        let c = |k: usize| {
            u8t(s[k] as f64 * (1.0 - blend) + (i[k] as f64 * 1.5).clamp(0.0, 255.0) * blend)
        };
        [c(0), c(1), c(2), 255]
    }))
}

fn mask_color(m: &Material, mask_key: &str, color_key: &str) -> Result<RgbaImage> {
    let mask = m.texture(&[mask_key])?;
    let c = m.color(color_key)?.map(u8t);
    Ok(image(mask.width(), mask.height(), |x, y| {
        [c[0], c[1], c[2], mask.get_pixel(x, y).0[0]]
    }))
}

type Bake = fn(&Material) -> Result<RgbaImage>;

/// The materials baked by name: the base file's kind (`""`, `"overlay."` or `"glow."`) and its bake.
fn listed(name: &str) -> Option<(&'static str, Bake)> {
    let bake: (&str, Bake) = match name {
        "MI_Basebody_V02_F2" | "MI_EVE_Head_V02_Jali" => ("", tinted),
        "CH_P_EVE_52_TypeB" | "MI_ACC_EAR_19M" | "MI_CH_NPC_Drone_06" => {
            ("", |m| base(m, &["Basecolor", "PM_Diffuse"]))
        }
        "MI_Teeth" | "CH_W_Sword_02_Blade_Body" => ("", |m| base(m, &["BaseColor", "PM_Diffuse"])),
        "MI_EyeRefractive1" => ("", eye),
        "MI_EyeBrow1" => ("", |m| mask_color(m, "Mask", "BaseColour")),
        "MI_EVE_HR_01_TypeA" | "MI_EVE_HR_01_Tail_TypeA" => ("", hair),
        "MI_ACC_EAR_19M_Emissive" => ("", |_| Ok(solid([255, 72, 0]))),
        // Eyelash cards: untextured in the game data, drawn near black.
        "NewMaterial" => ("", |_| Ok(solid([24, 18, 16]))),
        // EVE's drone (CH_NPC_Drone, the default Drone_Body_Default skin): shell and gun parts.
        "Drone_M_Inst" | "Drone_M_2_Inst" => {
            ("", |m| base(m, &["Base Color Texture", "PM_Diffuse"]))
        }
        // The Bear skin's masked decal sheet (warning labels), in EVE's suit material family.
        "MI_CH_NPC_Drone_06_Decal" => ("", |m| m.texture(&["Basecolor", "PM_Diffuse"])),
        // The sword's edge: hidden until a special attack raises its Opacity; white emissive takes the streamed color.
        "CH_W_Sword_02_Blade_Edge" => ("glow.", |m| base(m, &["BaseColor", "PM_Diffuse"])),
        "MI_EVE_Eyeshadow_Occlusion" => ("overlay.", eye_shadow),
        "EyeLight_Inst" => ("overlay.", eye_light),
        // Wet line: only its sharp highlight shows.
        "MI_EVE_Tearline" => ("overlay.", |_| Ok(solid4([0, 0, 0, 0]))),
        "MA_TeethOcculusion_Inst1" => ("overlay.", |m| {
            Ok(solid4([
                0,
                0,
                0,
                (255.0 * m.scalar("Opacity", 0.7) * 0.7).round_ties_even() as u8,
            ]))
        }),
        "M_MikeEyeBlend_Inst" => ("overlay.", eye_blend),
        _ => return None,
    };
    Some(bake)
}

/// The optional maps of the listed materials: suffix, bake, size limit.
fn maps(name: &str) -> Vec<(&'static str, Bake)> {
    let suit_normal: Bake = |m| normal(m, &["Baked NormalMap", "PM_Normals"], 1.0);
    let suit_surface: Bake = |m| surface_orm(m, &["ORM", "PM_SpecularMasks"]);
    let suit_emissive: Bake = |m| emissive_mask(m, "Emissive", "EmissiveColor", Paint::Color);
    let skin: [(&str, Bake); 3] = [
        ("normal", |m| normal(m, &["BaseNormal"], SKIN_NORMAL)),
        ("surface", |m| surface_skin(m, "ORSS Mask")),
        ("scatter", |m| scatter_skin(m, "ORSS Mask")),
    ];
    let blade: [(&str, Bake); 2] = [
        ("normal", |m| normal(m, &["Normal", "PM_Normals"], 1.0)),
        ("surface", |m| surface_orm(m, &["AoRM", "PM_SpecularMasks"])),
    ];
    let drone: [(&str, Bake); 2] = [
        ("normal", |m| {
            normal(m, &["Base Normal Texture", "PM_Normals"], 1.0)
        }),
        ("surface", |m| {
            surface_orm(m, &["O R M", "PM_SpecularMasks"])
        }),
    ];
    match name {
        "CH_P_EVE_52_TypeB" | "MI_CH_NPC_Drone_06" => {
            vec![
                ("normal", suit_normal),
                ("surface", suit_surface),
                ("emissive", suit_emissive),
            ]
        }
        "MI_Basebody_V02_F2" | "MI_EVE_Head_V02_Jali" => skin.to_vec(),
        "MI_EVE_Tearline" => vec![("surface", |_| Ok(solid4([255, 26, 0, 255])))],
        "CH_W_Sword_02_Blade_Edge" => {
            let mut maps = blade.to_vec();
            maps.push(("emissive", |_| Ok(solid([255, 255, 255]))));
            maps
        }
        "CH_W_Sword_02_Blade_Body" => blade.to_vec(),
        "MI_ACC_EAR_19M" | "MI_CH_NPC_Drone_06_Decal" => {
            vec![("normal", suit_normal), ("surface", suit_surface)]
        }
        "MI_ACC_EAR_19M_Emissive" => vec![("emissive", |_| Ok(solid([255, 72, 0])))],
        // Wet cornea: sharp light glints.
        "MI_EyeRefractive1" => vec![("surface", |_| Ok(solid4([255, 20, 0, 255])))],
        "Drone_M_Inst" => {
            let mut maps = drone.to_vec();
            maps.push(("emissive", |m| {
                emissive_mask(m, "Emissive Texture", "EmissiveColor", Paint::Color)
            }));
            maps
        }
        // Drone_M_2_Inst: EmissiveBrightness 0.
        "Drone_M_2_Inst" => drone.to_vec(),
        "MI_EVE_HR_01_TypeA" | "MI_EVE_HR_01_Tail_TypeA" => vec![("hair", hair_map)],
        _ => Vec::new(),
    }
}

fn map_size(suffix: &str) -> u32 {
    match suffix {
        "normal" | "hair" => 2048,
        _ => 1024,
    }
}

/// Any other material, by its parameters (named keys first: a PM_* key may hold a detail pattern, not the base): a
/// translucent film, the hair family, skin (ORSS mask and tint), fur, else a suit base color with the maps it has.
fn generic(m: &Material, pack: &Path) -> Result<&'static str> {
    let d = &m.data;
    let colors = m.colors();
    let has_color = |key: &str| colors.is_some_and(|c| c.contains_key(key));
    if d.get("IsTranslucent")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        // Premultiplied glossy film; the game's translucent shaders have no single base map.
        save(
            &solid4([6, 6, 8, 70]),
            pack,
            &format!("{}.overlay.png", m.name),
            MAX,
        )?;
        save(
            &solid4([255, 40, 0, 160]),
            pack,
            &format!("{}.surface.png", m.name),
            1024,
        )?;
        return Ok("overlay");
    }
    if m.has(&["HairTexture"]) && has_color("RootColorBlended") {
        save(&hair(m)?, pack, &format!("{}.png", m.name), MAX)?;
        save(&hair_map(m)?, pack, &format!("{}.hair.png", m.name), 2048)?;
        return Ok("hair");
    }
    if m.has(&["ORSS Mask"]) && has_color("BaseTint") {
        save(&tinted(m)?, pack, &format!("{}.png", m.name), MAX)?;
        let normal_map = normal(m, &["BaseNormal", "PM_Normals"], SKIN_NORMAL)?;
        save(&normal_map, pack, &format!("{}.normal.png", m.name), 2048)?;
        save(
            &surface_skin(m, "ORSS Mask")?,
            pack,
            &format!("{}.surface.png", m.name),
            1024,
        )?;
        save(
            &scatter_skin(m, "ORSS Mask")?,
            pack,
            &format!("{}.scatter.png", m.name),
            1024,
        )?;
        return Ok("skin");
    }
    const BASES: &[&str] = &[
        "Basecolor",
        "BaseColor",
        "Base Color Texture",
        "Base",
        "BaseTexture",
        "PM_Diffuse",
    ];
    let base = if m.has(&["FUR_Base"]) && has_color("FUR_FurColor") {
        // Fur: the fur color shaded by FUR_Base's R (a packed mask: B is near 0).
        let fur = m.texture(&["FUR_Base"])?;
        let c = m.color("FUR_FurColor")?;
        image(fur.width(), fur.height(), |x, y| {
            let p = fur.get_pixel(x, y).0;
            let shade = 0.6 + 0.4 * p[0] as f64 / 255.0;
            [
                u8t(shade * c[0]),
                u8t(shade * c[1]),
                u8t(shade * c[2]),
                p[3],
            ]
        })
    } else if m.has(BASES) {
        m.texture(BASES)?
    } else {
        // ponytail: no base map (glow trims, lace, pins): one flat color from the material, else gray; per-family
        // bakes if one looks wrong.
        let keys = [
            "BaseColor",
            "Base Color",
            "ColorTint",
            "Color",
            "EmissiveColor",
            "Eye Core HDR Tint",
        ];
        let flat = match keys.iter().find(|k| has_color(k)) {
            Some(key) => m.hdr(key)?.map(u8t),
            None => [128, 128, 128],
        };
        save(&solid(flat), pack, &format!("{}.png", m.name), MAX)?;
        return Ok("flat");
    };
    let blend = d.get("BlendMode").and_then(Value::as_i64);
    let base = if blend == Some(1) { base } else { opaque(base) };
    save(&base, pack, &format!("{}.png", m.name), MAX)?;
    const NORMALS: &[&str] = &[
        "Baked NormalMap",
        "Normal",
        "BaseNormal",
        "Base Normal Texture",
        "PM_Normals",
    ];
    if m.has(NORMALS) {
        save(
            &normal(m, NORMALS, 1.0)?,
            pack,
            &format!("{}.normal.png", m.name),
            2048,
        )?;
    }
    const ORMS: &[&str] = &["ORM", "AoRM", "O R M", "PM_SpecularMasks"];
    if m.has(ORMS) {
        save(
            &surface_orm(m, ORMS)?,
            pack,
            &format!("{}.surface.png", m.name),
            1024,
        )?;
    }
    let glows = ["R", "G", "B"].iter().any(|c| {
        colors
            .and_then(|cs| cs.get("EmissiveColor"))
            .and_then(|g| g.get(*c))
            .and_then(Value::as_f64)
            .is_some_and(|v| v > 0.0)
    });
    if m.has(&["Emissive", "PM_Emissive"]) && glows {
        let key = if m.has(&["Emissive"]) {
            "Emissive"
        } else {
            "PM_Emissive"
        };
        let map = emissive_mask(m, key, "EmissiveColor", Paint::Hdr)?;
        save(&map, pack, &format!("{}.emissive.png", m.name), 1024)?;
    }
    Ok("suit")
}

/// Bakes one material into `pack`: the kind baked (`listed`, `overlay`, `hair`, `skin`, `flat`, `suit`), or the
/// reason it was skipped. A skipped material leaves no file behind: its sections stay undrawn. `object_path` is the
/// material's path from the mesh list, when known.
pub fn material(
    name: &str,
    object_path: Option<&str>,
    export: &Export,
    pack: &Path,
) -> Result<&'static str, String> {
    let result = Material::load(name, object_path, export).and_then(|m| {
        if let Some((kind, bake)) = listed(name) {
            save(&bake(&m)?, pack, &format!("{name}.{kind}png"), MAX)?;
            for (suffix, bake) in maps(name) {
                save(
                    &bake(&m)?,
                    pack,
                    &format!("{name}.{suffix}.png"),
                    map_size(suffix),
                )?;
            }
            Ok("listed")
        } else {
            generic(&m, pack)
        }
    });
    result.map_err(|error| {
        let prefix = format!("{name}.");
        for entry in fs::read_dir(pack).into_iter().flatten().flatten() {
            let file = entry.file_name().to_string_lossy().into_owned();
            if file.starts_with(&prefix) && file.ends_with("png") {
                let _ = fs::remove_file(entry.path());
            }
        }
        format!("{error:#}")
    })
}

// ---------------------------------------------------------------------------------------------------------------
// Effect sprites, HUD textures, the sword trail, item icons.

pub const FX_TEXTURES: &[(&str, u32)] = &[
    ("T_A_SparkParticle_01", 128),
    ("T_A_HorizonFlare_03", 512),
    ("T_A_LensFlare_01", 256),
    ("T_A_ShineFlare_01", 256),
    ("T_Semicircle_Arcs_2x2", 512),
    ("T_B_Electric_01", 512),
    ("T_A_BreakCrystal_01", 512),
    ("T_A_StreakSwirl_01", 256),
];

/// Name → trim. A trimmed icon is cropped to its opaque bounds (alpha above 8) and padded to a centred square, so it
/// fills its item cell; the Tachy slot's base and icon and the dash frame keep their whole texture.
pub const HUD_TEXTURES: &[(&str, bool)] = &[
    ("Tk_Idle_Base2", false),
    ("Tk_Idle_Icon", false),
    ("skill_hud_dashframe", false),
    ("potion", true),
    ("Skill_Icon_Dash", true),
    ("bullet_icon_01", true),
    ("bullet_icon_02", true),
    ("bullet_icon_03", true),
    ("bullet_icon_04", true),
    ("bullet_icon_05", true),
    ("Icon_Camera", true),
    ("Icon_PotionR", true),
    ("Icon_Potion", true),
    ("Icon_PotionSmall", true),
    ("HealGrenade", true),
    ("Grenade", true),
    ("PluseGrenade", true),
    ("TrapGrenade", true),
    ("FlashGrenade", true),
    ("WbPump", true),
];

/// The game's additive effect textures as brightness times coverage (some store their shape in alpha, some on
/// black), and a soft ring for shock waves (the game's are camera-aligned meshes with a red edge).
pub fn fx_textures(export: &Export, pack: &Path) -> Result<()> {
    for &(name, size) in FX_TEXTURES {
        let t = open(export.texture(name)?)?;
        let sprite = image(t.width(), t.height(), |x, y| {
            let p = t.get_pixel(x, y).0;
            let bright = u8t(p[0].max(p[1]).max(p[2]) as f64 * p[3] as f64 / 255.0);
            [bright, bright, bright, 255]
        });
        save(&sprite, pack, &format!("fx.{name}.png"), size)?;
    }
    let ring = image(256, 256, |x, y| {
        let (u, v) = (x as f64 / 127.5 - 1.0, y as f64 / 127.5 - 1.0);
        let r = u.hypot(v);
        let ring = u8t(npow((1.0 - (r - 0.8).abs() / 0.15).clamp(0.0, 1.0), 2.0) * 255.0);
        [ring, ring, ring, 255]
    });
    write_png(&ring, &pack.join("fx.Ring.png"))
}

/// An icon cropped to its opaque bounds (alpha above 8) and centred in a transparent square.
fn trim(image: &RgbaImage) -> RgbaImage {
    let opaque: Vec<(u32, u32)> = image
        .enumerate_pixels()
        .filter(|(_, _, p)| p.0[3] > 8)
        .map(|(x, y, _)| (x, y))
        .collect();
    let (Some(x0), Some(y0)) = (
        opaque.iter().map(|p| p.0).min(),
        opaque.iter().map(|p| p.1).min(),
    ) else {
        return image.clone();
    };
    let x1 = opaque.iter().map(|p| p.0).max().unwrap_or(x0) + 1;
    let y1 = opaque.iter().map(|p| p.1).max().unwrap_or(y0) + 1;
    let side = (x1 - x0).max(y1 - y0);
    let (dx, dy) = ((side - (x1 - x0)) / 2, (side - (y1 - y0)) / 2);
    let mut square = RgbaImage::new(side, side);
    for y in y0..y1 {
        for x in x0..x1 {
            square.put_pixel(x - x0 + dx, y - y0 + dy, *image.get_pixel(x, y));
        }
    }
    square
}

pub fn hud_textures(export: &Export, pack: &Path) -> Result<()> {
    for &(name, trimmed) in HUD_TEXTURES {
        let mut t = open(export.texture(name)?)?;
        if trimmed {
            t = trim(&t);
        }
        save(
            &resize(&t, 64, 64, Filter::Lanczos, true),
            pack,
            &format!("hud.{name}.png"),
            64,
        )?;
    }
    Ok(())
}

/// An item icon: its whole texture at 64².
pub fn icon(source: &Path, pack: &Path, name: &str) -> Result<()> {
    save(
        &resize(&open(source)?, 64, 64, Filter::Lanczos, true),
        pack,
        &format!("hud.{name}.png"),
        64,
    )
}

/// Python's `repr` of a float: the shortest round-trip digits, scientific below 1e-4 and from 1e16.
fn py_repr(value: f64) -> String {
    if value == 0.0 || !value.is_finite() {
        return if value.is_nan() {
            "nan".into()
        } else if value.is_infinite() {
            (if value > 0.0 { "inf" } else { "-inf" }).into()
        } else if value.is_sign_negative() {
            "-0.0".into()
        } else {
            "0.0".into()
        };
    }
    let scientific = format!("{value:e}");
    let (mantissa, exponent) = scientific.split_once('e').expect("LowerExp has an e");
    let exponent: i32 = exponent.parse().expect("LowerExp exponent");
    let negative = mantissa.starts_with('-');
    let digits: String = mantissa.chars().filter(char::is_ascii_digit).collect();
    let sign = if negative { "-" } else { "" };
    if (-4..16).contains(&exponent) {
        let point = exponent + 1;
        let text = if point <= 0 {
            format!("0.{}{digits}", "0".repeat((-point) as usize))
        } else if point as usize >= digits.len() {
            format!("{digits}{}.0", "0".repeat(point as usize - digits.len()))
        } else {
            format!(
                "{}.{}",
                &digits[..point as usize],
                &digits[point as usize..]
            )
        };
        format!("{sign}{text}")
    } else {
        let mantissa = if digits.len() > 1 {
            format!("{}.{}", &digits[..1], &digits[1..])
        } else {
            digits
        };
        format!(
            "{sign}{mantissa}e{}{:02}",
            if exponent < 0 { '-' } else { '+' },
            exponent.abs()
        )
    }
}

/// A JSON number as Python prints what `json.loads` made of it.
fn py_number(value: Option<&Value>) -> String {
    match value {
        Some(Value::Number(n)) if n.is_i64() || n.is_u64() => n.to_string(),
        Some(Value::Number(n)) => py_repr(n.as_f64().unwrap_or(0.0)),
        _ => "0.0".into(),
    }
}

/// Stellar Blade's light-attack trail: P_A_AnimTrail_SwordLightAtk_01 between FX_Trail_Begin and FX_Trail_End,
/// material MI_A_AnimTrailDefault_01_3 (T_A_AnimTrail_01, T_A_StreakNoise_05). Only while the sword is in EVE's right
/// hand. `skeleton` is the sword skeleton's JSON dump.
pub fn trail(export: &Export, skeleton: &Value, pack: &Path) -> Result<()> {
    fn walk<'a>(value: &'a Value, found: &mut HashMap<String, (&'a str, &'a Value)>) {
        match value {
            Value::Object(map) => {
                if let (Some(socket), Some(bone)) = (
                    map.get("SocketName").and_then(Value::as_str),
                    map.get("BoneName").and_then(Value::as_str),
                ) {
                    found.insert(
                        socket.to_string(),
                        (bone, map.get("RelativeLocation").unwrap_or(&Value::Null)),
                    );
                }
                map.values().for_each(|v| walk(v, found));
            }
            Value::Array(items) => items.iter().for_each(|v| walk(v, found)),
            _ => {}
        }
    }
    let mut found = HashMap::new();
    walk(skeleton, &mut found);
    let mut lines = vec!["SBAVTRAIL 1".to_string()];
    for (key, socket) in [("BEGIN", "FX_Trail_Begin"), ("END", "FX_Trail_End")] {
        let (bone, location) = found
            .get(socket)
            .with_context(|| format!("sword skeleton: no {socket}"))?;
        let xyz = ["X", "Y", "Z"].map(|k| py_number(location.get(k)));
        lines.push(format!("{key} {bone} {} {} {}", xyz[0], xyz[1], xyz[2]));
    }
    lines.push("HELD CH_P_EVE_52_TypeB Bip001-R-Hand 30 Bip001-Spine2".into());
    fs::write(
        pack.join("CH_W_Sword_01.trail.txt"),
        lines.join("\n") + "\n",
    )?;
    let rgb = |name: &str| -> Result<RgbaImage> {
        Ok(resize(
            &opaque(open(export.texture(name)?)?),
            512,
            512,
            Filter::Lanczos,
            false,
        ))
    };
    let (gradient, streaks) = (rgb("T_A_AnimTrail_01")?, rgb("T_A_StreakNoise_05")?);
    let texture = image(512, 512, |x, y| {
        [
            gradient.get_pixel(x, y).0[0],
            streaks.get_pixel(x, y).0[1],
            0,
            255,
        ]
    });
    write_png(&texture, &pack.join("CH_W_Sword_01.trail.png"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn python_float_repr() {
        for (value, text) in [
            (0.0, "0.0"),
            (1.0, "1.0"),
            (12.5, "12.5"),
            (-3.25, "-3.25"),
            (0.0001, "0.0001"),
            (0.00001, "1e-05"),
            (-3.0517578125e-05, "-3.0517578125e-05"),
            (1e16, "1e+16"),
            (123456789012345.6, "123456789012345.6"),
            (0.1 + 0.2, "0.30000000000000004"),
        ] {
            assert_eq!(py_repr(value), text);
        }
    }

    #[test]
    fn resize_keeps_flat_colors_and_premultiplies() {
        let flat = image(5, 3, |_, _| [200, 100, 50, 255]);
        let big = resize(&flat, 13, 7, Filter::Lanczos, true);
        assert!(big.pixels().all(|p| p.0 == [200, 100, 50, 255]));
        let small = resize(&flat, 2, 2, Filter::Bicubic, true);
        assert!(small.pixels().all(|p| p.0 == [200, 100, 50, 255]));
        // A transparent pixel's color does not bleed into its opaque neighbour.
        let edge = image(2, 1, |x, _| {
            if x == 0 {
                [255, 0, 0, 0]
            } else {
                [0, 0, 255, 255]
            }
        });
        let wide = resize(&edge, 4, 1, Filter::Lanczos, true);
        assert!(wide.pixels().all(|p| p.0[0] == 0), "{wide:?}");
    }

    #[test]
    fn trim_centres_the_opaque_bounds() {
        let icon = image(10, 6, |x, y| {
            if (2..6).contains(&x) && (1..3).contains(&y) {
                [9, 9, 9, 200]
            } else {
                [1, 1, 1, 8]
            }
        });
        let square = trim(&icon);
        assert_eq!(square.dimensions(), (4, 4));
        assert_eq!(square.get_pixel(0, 1).0, [9, 9, 9, 200]);
        assert_eq!(square.get_pixel(0, 0).0, [0, 0, 0, 0]);
        let clear = image(3, 3, |_, _| [0; 4]);
        assert_eq!(trim(&clear), clear);
    }

    /// The private golden check, on a developer's own game exports (nothing of them is kept): bakes every section of
    /// the meshes in `$SBMC_GOLDEN_MESHES`, the effect sprites, HUD textures and the sword trail from the EveExtract
    /// `export/` tree `$SBMC_GOLDEN_EXPORT` and the skeleton dump `$SBMC_GOLDEN_SKELETON` into a temporary folder,
    /// and compares each image with the same file of a pack `pack.py` built, `$SBMC_GOLDEN_PACK`.
    #[test]
    #[ignore]
    fn private_golden() {
        let var = |name: &str| std::env::var_os(name).map(PathBuf::from);
        let (Some(export), Some(meshes), Some(skeleton), Some(reference)) = (
            var("SBMC_GOLDEN_EXPORT"),
            var("SBMC_GOLDEN_MESHES"),
            var("SBMC_GOLDEN_SKELETON"),
            var("SBMC_GOLDEN_PACK"),
        ) else {
            return;
        };
        use rayon::prelude::*;
        let out = tempfile::tempdir().unwrap();
        let export = Export::scan(&export.join("export")).unwrap();
        let mut todo = std::collections::BTreeSet::new();
        for entry in fs::read_dir(&meshes).unwrap().flatten() {
            if entry.path().extension().is_some_and(|e| e == "sbav") {
                let (_, sections) =
                    super::super::manifest::header(&fs::read(entry.path()).unwrap()).unwrap();
                todo.extend(
                    sections
                        .into_iter()
                        .filter(|s| !super::super::manifest::UNDRAWN.contains(&s.as_str())),
                );
            }
        }
        let results: Vec<_> = todo
            .par_iter()
            .map(|m| (m, material(m, None, &export, out.path())))
            .collect();
        for (m, result) in results {
            println!("{m}: {result:?}");
        }
        fx_textures(&export, out.path()).unwrap();
        hud_textures(&export, out.path()).unwrap();
        trail(
            &export,
            &serde_json::from_slice(&fs::read(skeleton).unwrap()).unwrap(),
            out.path(),
        )
        .unwrap();
        let (mut same, mut close, mut other) = (0, 0, Vec::new());
        for entry in fs::read_dir(out.path()).unwrap().flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let Ok(expected) = fs::read(reference.join(&name)) else {
                other.push(format!("{name}: not in the reference pack"));
                continue;
            };
            if !name.ends_with(".png") {
                if fs::read(entry.path()).unwrap() == expected {
                    same += 1
                } else {
                    other.push(format!("{name}: differs"))
                }
                continue;
            }
            let (a, b) = (
                open(&entry.path()).unwrap(),
                image::load_from_memory(&expected).unwrap().to_rgba8(),
            );
            if a.dimensions() != b.dimensions() {
                other.push(format!(
                    "{name}: {:?} against {:?}",
                    a.dimensions(),
                    b.dimensions()
                ));
                continue;
            }
            let off: Vec<u8> = a
                .pixels()
                .zip(b.pixels())
                .map(|(p, q)| (0..4).map(|c| p.0[c].abs_diff(q.0[c])).max().unwrap())
                .collect();
            let (count, most) = (
                off.iter().filter(|&&d| d > 0).count(),
                off.iter().copied().max().unwrap_or(0),
            );
            match (count, most) {
                (0, _) => same += 1,
                (n, 1) if n * 1000 < off.len() => close += 1,
                (n, m) => other.push(format!(
                    "{name}: {n} of {} px differ, by up to {m}",
                    off.len()
                )),
            }
        }
        println!(
            "pixel-identical {same}, within 1 on under 0.1 % of pixels {close}, other {}",
            other.len()
        );
        for line in &other {
            println!("  {line}");
        }
        assert!(
            other.iter().all(|l| l.contains("not in the reference")),
            "{other:#?}"
        );
    }
}
