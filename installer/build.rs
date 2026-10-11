//! Embeds the bridge payload staged by `tools/payload.py` in the folder named by `SBMC_PAYLOAD`. Without it the
//! installer builds with an empty payload and refuses to install. `payload.json` lists every file's sha256; the
//! installer checks them when it loads the payload (`payload::Payload::embedded`).
//!
//! It also embeds the pack builder's EveExtract sidecar for the target OS: `SBMC_EVE_EXTRACT` names the folder that
//! `tools/check.py` and CI fill with both sidecars and their `SHA256SUMS` (`sha256sum` lines). The target's sidecar goes
//! in with its line's sha256, which the installer checks when it unpacks it. Without it the installer cannot build the
//! avatar pack.

use std::fmt::Write;
use std::path::Path;
use std::{env, fs};

fn walk(root: &Path, dir: &Path, code: &mut String) {
    let mut entries: Vec<_> = fs::read_dir(dir)
        .expect("readable payload folder")
        .map(|e| e.expect("readable payload entry").path())
        .collect();
    entries.sort();
    for path in entries {
        let metadata = fs::symlink_metadata(&path).expect("readable payload entry");
        if metadata.is_dir() {
            walk(root, &path, code);
        } else if metadata.is_file() {
            // '/'-joined like payload.json's names, also when the build runs on Windows.
            let relative = path
                .strip_prefix(root)
                .unwrap()
                .components()
                .map(|c| c.as_os_str().to_str().expect("UTF-8 payload path"))
                .collect::<Vec<_>>()
                .join("/");
            let absolute = path.to_str().expect("UTF-8 payload path");
            writeln!(code, "    ({relative:?}, include_bytes!({absolute:?})),").unwrap();
        } else {
            panic!(
                "Refusing a symbolic link or special file in the payload: {}",
                path.display()
            );
        }
    }
}

fn main() {
    println!("cargo:rerun-if-env-changed=SBMC_PAYLOAD");
    let mut code = String::from("pub(crate) static FILES: &[(&str, &[u8])] = &[\n");
    if let Some(dir) = env::var_os("SBMC_PAYLOAD").filter(|d| !d.is_empty()) {
        let dir = fs::canonicalize(dir)
            .expect("SBMC_PAYLOAD names the folder staged by tools/payload.py");
        // A directory is scanned whole for changes.
        println!("cargo:rerun-if-changed={}", dir.display());
        walk(&dir, &dir, &mut code);
    }
    code.push_str("];\n");
    let out = Path::new(&env::var_os("OUT_DIR").unwrap()).to_path_buf();
    fs::write(out.join("payload.rs"), code).unwrap();
    fs::write(out.join("sidecar.rs"), sidecar()).unwrap();
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        windows_resources(&out);
    }
}

/// `SIDECAR_BUILD`: this target's EveExtract and its sha256 from `$SBMC_EVE_EXTRACT/SHA256SUMS`, or `None`.
fn sidecar() -> String {
    println!("cargo:rerun-if-env-changed=SBMC_EVE_EXTRACT");
    let Some(dir) = env::var_os("SBMC_EVE_EXTRACT").filter(|d| !d.is_empty()) else {
        return "pub(crate) static SIDECAR_BUILD: Option<(&[u8], &str)> = None;\n".into();
    };
    let name = if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        "eve-extract-win-x64.exe"
    } else {
        "eve-extract-linux-x64"
    };
    let dir = fs::canonicalize(dir)
        .expect("SBMC_EVE_EXTRACT names the folder holding EveExtract's builds");
    let sums = dir.join("SHA256SUMS");
    let program = dir.join(name);
    for path in [&sums, &program] {
        println!("cargo:rerun-if-changed={}", path.display());
    }
    let text = fs::read_to_string(&sums).expect("SBMC_EVE_EXTRACT holds EveExtract's SHA256SUMS");
    // `<sha256>  <name>`, or `<sha256> *<name>` (binary mode).
    let digest = text
        .lines()
        .filter_map(|line| line.split_once("  ").or_else(|| line.split_once(" *")))
        .find(|(_, file)| *file == name)
        .map(|(digest, _)| digest)
        .unwrap_or_else(|| panic!("{} has no line for {name}", sums.display()));
    assert!(
        digest.len() == 64
            && digest
                .bytes()
                .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')),
        "Not a lowercase sha256 for {name} in {}",
        sums.display()
    );
    let program = program.to_str().expect("UTF-8 EveExtract path");
    format!(
        "pub(crate) static SIDECAR_BUILD: Option<(&[u8], &str)> = Some((include_bytes!({program:?}), {digest:?}));\n"
    )
}

/// The Windows exe's icon, version information (this crate's version) and manifest (asInvoker: never elevated).
fn windows_resources(out: &Path) {
    let assets = Path::new(&env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("assets");
    for name in ["icon.ico", "stellar-bridge-installer.manifest"] {
        println!("cargo:rerun-if-changed={}", assets.join(name).display());
    }
    // Resource scripts read backslashes as escapes: forward slashes work for both rc.exe and llvm-rc.
    let assets = assets.display().to_string().replace('\\', "/");
    let version = env::var("CARGO_PKG_VERSION").unwrap();
    let mut numbers = version
        .split(['.', '-', '+'])
        .map(|n| n.parse::<u16>().unwrap_or(0));
    let mut next = || numbers.next().unwrap_or(0);
    let numeric = format!("{},{},{},0", next(), next(), next());
    let script = format!(
        r#"1 ICON "{assets}/icon.ico"
1 24 "{assets}/stellar-bridge-installer.manifest"
1 VERSIONINFO
FILEVERSION {numeric}
PRODUCTVERSION {numeric}
FILEFLAGSMASK 0x3F
FILEFLAGS 0x0
FILEOS 0x40004
FILETYPE 0x1
FILESUBTYPE 0x0
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904B0"
    BEGIN
      VALUE "CompanyName", "Colony"
      VALUE "FileDescription", "Stellar Bridge installer"
      VALUE "FileVersion", "{version}"
      VALUE "InternalName", "stellar-bridge-installer"
      VALUE "LegalCopyright", "Licensed under GPL-3.0-or-later"
      VALUE "OriginalFilename", "stellar-bridge-installer.exe"
      VALUE "ProductName", "Stellar Bridge"
      VALUE "ProductVersion", "{version}"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
"#
    );
    let path = out.join("stellar-bridge-installer.rc");
    fs::write(&path, script).unwrap();
    embed_resource::compile(&path, embed_resource::NONE)
        .manifest_required()
        .unwrap();
}
