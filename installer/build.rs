//! Embeds the bridge payload staged by `tools/payload.py` in the folder named by `SBMC_PAYLOAD`. Without it the
//! installer builds with an empty payload and refuses to install. `payload.json` lists every file's sha256; the
//! installer checks them when it loads the payload (`payload::Payload::embedded`).

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
            let relative = path
                .strip_prefix(root)
                .unwrap()
                .to_str()
                .expect("UTF-8 payload path");
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
    let out = Path::new(&env::var_os("OUT_DIR").unwrap()).join("payload.rs");
    fs::write(out, code).unwrap();
}
