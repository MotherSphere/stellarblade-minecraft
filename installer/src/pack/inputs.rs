//! The pack builder's inputs besides the game's own files, kept in the builder cache (`<data>/builder`):
//!
//! - **EveExtract**, the sidecar: carried by the installer and unpacked into the cache the first time, and again
//!   whenever the cached copy is not the one this build pins (sha256).
//! - **Oodle**: downloaded from the pinned OodleUE release only after the player agrees, or taken from that release's
//!   zip the player already has; the zip's sha256 must be the pinned one. Never shipped. The consent (date, source and
//!   digests) is recorded beside the library.
//! - **The usmap** UE4SS writes for the running game build (the Lua mod asks for it, or Ctrl+Numpad6): read from
//!   UE4SS's folders, checked by its header and copied into the cache under the game's Steam build id. Nothing is ever
//!   written to the game folder.

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime};

use super::extract::Cancelled;
use crate::safety::{hex, sha256_file, sha256_hex};
use crate::{Kind, Refusal, log, pins, platform};

include!(concat!(env!("OUT_DIR"), "/sidecar.rs"));

/// This OS's EveExtract, by its release asset name, which is also its name in the cache.
pub const SIDECAR: &str = if cfg!(windows) {
    "eve-extract-win-x64.exe"
} else {
    "eve-extract-linux-x64"
};
/// The usmap header: magic `C4 30`, then the version byte. CUE4Parse at the pinned commit reads versions 0 to 5.
const USMAP_MAGIC: [u8; 2] = [0xC4, 0x30];
const USMAP_VERSIONS: u8 = 5;
/// The consent record beside the cached Oodle library.
const OODLE_RECORD: &str = "oodle.json";
/// A file to download, checked against its size and sha256 before it is used.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Remote {
    pub url: String,
    pub size: u64,
    pub sha256: String,
}

/// A program the installer carries, with its pinned sha256.
#[derive(Clone)]
pub struct Embedded {
    pub bytes: &'static [u8],
    pub sha256: String,
}

/// Where the inputs come from. [`Sources::release`] outside tests.
#[derive(Clone)]
pub struct Sources {
    /// This OS's EveExtract; `None` in a build without `SBMC_EVE_EXTRACT`.
    pub sidecar: Option<Embedded>,
    pub oodle: Remote,
    /// The library's path inside the Oodle zip.
    pub oodle_entry: String,
}

impl Sources {
    /// This release's: the EveExtract it carries, and the pinned OodleUE zip for this OS.
    pub fn release() -> Sources {
        Sources {
            sidecar: SIDECAR_BUILD.map(|(bytes, sha256)| Embedded {
                bytes,
                sha256: sha256.into(),
            }),
            oodle: Remote {
                url: pins::OODLE.url.into(),
                size: pins::OODLE.size,
                sha256: pins::OODLE.sha256.into(),
            },
            oodle_entry: pins::OODLE.entry.into(),
        }
    }

    /// The cached library's file name: the entry's.
    pub fn oodle_file(&self) -> &str {
        self.oodle_entry
            .rsplit('/')
            .next()
            .unwrap_or(&self.oodle_entry)
    }
}

/// `<data>/builder`.
pub fn cache(data: &Path) -> PathBuf {
    data.join("builder")
}

fn matches(path: &Path, sha256: &str) -> bool {
    path.is_file() && sha256_file(path, &mut |_| {}).is_ok_and(|d| d == sha256)
}

/// This OS's EveExtract in `cache`: unpacked from the installer when the cached copy is missing or not the pinned one.
pub fn unpack_sidecar(sources: &Sources, cache: &Path) -> Result<PathBuf> {
    let Some(embedded) = &sources.sidecar else {
        bail!(Refusal::new(Kind::NoSidecar, ""));
    };
    // Before every build: also makes a cache from an older installer private.
    platform::create_private_dir_all(cache)?;
    let path = cache.join(SIDECAR);
    if matches(&path, &embedded.sha256) {
        return Ok(path);
    }
    if sha256_hex(embedded.bytes) != embedded.sha256 {
        bail!(Refusal::new(Kind::DigestMismatch, SIDECAR));
    }
    let part = cache.join(format!("{SIDECAR}.part"));
    fs::write(&part, embedded.bytes)?;
    platform::make_executable(&part)?;
    fs::rename(&part, &path)?;
    log::line(&format!(
        "pack: EveExtract {} unpacked to {}",
        embedded.sha256,
        path.display()
    ));
    Ok(path)
}

/// Downloads `remote` to `to` through `<to>.part`: the file appears only once its size and sha256 match.
pub fn download(
    remote: &Remote,
    to: &Path,
    cancel: &AtomicBool,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<()> {
    let failed = || Refusal::new(Kind::DownloadFailed, &remote.url);
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_connect(Some(Duration::from_secs(30)))
        .timeout_recv_response(Some(Duration::from_secs(60)))
        .build()
        .into();
    let mut response = agent
        .get(&remote.url)
        .call()
        .map_err(|e| anyhow::Error::new(e).context(failed()))?;
    // One byte over the pinned size is enough to refuse it.
    let mut reader = response
        .body_mut()
        .with_config()
        .limit(remote.size + 1)
        .reader();
    let part = to.with_file_name(format!(
        "{}.part",
        to.file_name().unwrap_or_default().to_string_lossy()
    ));
    let result = (|| -> Result<()> {
        let mut file = File::create(&part)?;
        let mut hasher = Sha256::new();
        let (mut buffer, mut done) = (vec![0; 64 << 10], 0u64);
        loop {
            if cancel.load(Ordering::Relaxed) {
                bail!(Cancelled);
            }
            // ponytail: a stalled connection is noticed only by the next read; cancel waits for it.
            let n = reader.read(&mut buffer).context(failed())?;
            if n == 0 {
                break;
            }
            done += n as u64;
            if done > remote.size {
                bail!(Refusal::new(Kind::DigestMismatch, &remote.url));
            }
            hasher.update(&buffer[..n]);
            file.write_all(&buffer[..n])?;
            progress(done, remote.size);
        }
        file.sync_all()?;
        if hex(&hasher.finalize()) != remote.sha256 || remote.size != done {
            bail!(Refusal::new(Kind::DigestMismatch, &remote.url));
        }
        Ok(())
    })();
    match result {
        Ok(()) => Ok(fs::rename(&part, to)?),
        Err(error) => {
            let _ = fs::remove_file(&part);
            Err(error)
        }
    }
}

/// The consent the player gave for Oodle, recorded beside the library it brought.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OodleRecord {
    /// Seconds since the Unix epoch.
    pub consented: u64,
    /// The download URL, or `file` for a zip the player had.
    pub source: String,
    pub zip_sha256: String,
    pub sha256: String,
}

/// The cached Oodle library and its record, when the record is for this release's zip and the library is unchanged.
pub fn oodle(sources: &Sources, cache: &Path) -> Option<(PathBuf, OodleRecord)> {
    let record: OodleRecord =
        serde_json::from_slice(&fs::read(cache.join(OODLE_RECORD)).ok()?).ok()?;
    let path = cache.join("oodle").join(sources.oodle_file());
    (record.zip_sha256 == sources.oodle.sha256 && matches(&path, &record.sha256))
        .then_some((path, record))
}

/// Downloads the pinned Oodle zip (the player agreed) and keeps its library.
pub fn fetch_oodle(
    sources: &Sources,
    cache: &Path,
    cancel: &AtomicBool,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<PathBuf> {
    platform::create_private_dir_all(cache)?;
    let zip = cache.join("oodle.zip");
    download(&sources.oodle, &zip, cancel, progress)?;
    let result = keep_oodle(sources, cache, &zip, &sources.oodle.url);
    let _ = fs::remove_file(&zip);
    result
}

/// Keeps the library of an Oodle zip the player already has, when it is the pinned zip.
pub fn oodle_from_file(sources: &Sources, cache: &Path, zip: &Path) -> Result<PathBuf> {
    if !matches(zip, &sources.oodle.sha256) {
        bail!(Refusal::new(Kind::DigestMismatch, zip.display()));
    }
    platform::create_private_dir_all(cache)?;
    keep_oodle(sources, cache, zip, "file")
}

/// Extracts the library from a checked zip into `<cache>/oodle/` and records the consent.
fn keep_oodle(sources: &Sources, cache: &Path, zip: &Path, source: &str) -> Result<PathBuf> {
    let mut archive = zip::ZipArchive::new(File::open(zip)?)
        .with_context(|| Refusal::new(Kind::OodleEntry, zip.display()))?;
    let mut entry = archive
        .by_name(&sources.oodle_entry)
        .with_context(|| Refusal::new(Kind::OodleEntry, zip.display()))?;
    let dir = cache.join("oodle");
    fs::create_dir_all(&dir)?;
    let path = dir.join(sources.oodle_file());
    let part = dir.join(format!("{}.part", sources.oodle_file()));
    std::io::copy(&mut entry, &mut File::create(&part)?)?;
    fs::rename(&part, &path)?;
    let record = OodleRecord {
        consented: SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)?
            .as_secs(),
        source: source.to_string(),
        zip_sha256: sources.oodle.sha256.clone(),
        sha256: sha256_file(&path, &mut |_| {})?,
    };
    fs::write(
        cache.join(OODLE_RECORD),
        serde_json::to_string_pretty(&record)? + "\n",
    )?;
    log::line(&format!(
        "pack: Oodle kept from {source} (zip sha256 {}, library sha256 {})",
        record.zip_sha256, record.sha256
    ));
    Ok(path)
}

/// Whether `path` starts like a usmap CUE4Parse reads.
pub fn check_usmap(path: &Path) -> Result<()> {
    let mut header = [0u8; 3];
    File::open(path)?
        .read_exact(&mut header)
        .ok()
        .filter(|_| header[..2] == USMAP_MAGIC && header[2] <= USMAP_VERSIONS)
        .with_context(|| Refusal::new(Kind::UsmapInvalid, path.display()))
}

/// [`check_usmap`] for UE4SS's own usmap, which must also be no older than the game's program: mappings come from its
/// compiled code, and the Lua mod never replaces a usmap, so an older one was written for an earlier game build.
pub fn check_game_usmap(win64: &Path, usmap: &Path) -> Result<()> {
    check_usmap(usmap)?;
    let modified = |path: &Path| fs::metadata(path).and_then(|m| m.modified()).ok();
    if let (Some(exe), Some(written)) =
        (modified(&win64.join(crate::adapter::EXE)), modified(usmap))
        && written < exe
    {
        bail!(Refusal::new(Kind::UsmapStale, usmap.display()));
    }
    Ok(())
}

/// The newest `*.usmap` in UE4SS's folders (`Win64/ue4ss`, or `Win64` in the old layout), where UE4SS writes it.
pub fn usmap_in_game(win64: &Path) -> Option<PathBuf> {
    [win64.join("ue4ss"), win64.to_path_buf()]
        .iter()
        .flat_map(|dir| fs::read_dir(dir).into_iter().flatten().flatten())
        .map(|entry| entry.path())
        .filter(|path| {
            path.extension()
                .is_some_and(|e| e.eq_ignore_ascii_case("usmap"))
                && path.is_file()
        })
        .max_by_key(|path| fs::metadata(path).and_then(|m| m.modified()).ok())
}

/// The cached usmap of a game build (unknown builds share one name).
pub fn cached_usmap(cache: &Path, build_id: Option<&str>) -> PathBuf {
    let build = build_id
        .filter(|b| !b.is_empty() && b.bytes().all(|c| c.is_ascii_digit()))
        .unwrap_or("unknown");
    cache.join("usmap").join(format!("{build}.usmap"))
}

/// The usmap for this build: UE4SS's newest one copied into the cache when its header is right, else the cached copy.
/// The game folder is only read.
pub fn take_usmap(win64: &Path, build_id: Option<&str>, cache: &Path) -> Result<PathBuf> {
    let cached = cached_usmap(cache, build_id);
    if let Some(found) = usmap_in_game(win64) {
        check_game_usmap(win64, &found)?;
        platform::create_private_dir_all(cache)?;
        fs::create_dir_all(cached.parent().context("no usmap folder")?)?;
        let part = cached.with_extension("usmap.part");
        fs::copy(&found, &part)?;
        fs::rename(&part, &cached)?;
        log::line(&format!(
            "pack: usmap {} copied for build {}",
            found.file_name().unwrap_or_default().to_string_lossy(),
            build_id.unwrap_or("unknown")
        ));
    }
    check_usmap(&cached).context(Refusal::new(Kind::NoUsmap, ""))?;
    Ok(cached)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::net::TcpListener;

    /// A local HTTP server that answers every request for `path` with `body`, and 404 for anything else. Its URL.
    pub(crate) fn serve(path: &'static str, body: Vec<u8>) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}{path}", listener.local_addr().unwrap());
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let mut reader = BufReader::new(&stream);
                let mut request = String::new();
                reader.read_line(&mut request).unwrap_or_default();
                let mut header = String::new();
                while reader.read_line(&mut header).is_ok_and(|n| n > 2) {
                    header.clear();
                }
                let mut stream = &stream;
                let found = request.split(' ').nth(1) == Some(path);
                let (status, body) = if found {
                    ("200 OK", body.as_slice())
                } else {
                    ("404 Not Found", &b"not here"[..])
                };
                let _ = write!(
                    stream,
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(body);
            }
        });
        url
    }

    /// A zip holding `entry` (deflated) and a decoy.
    pub(crate) fn zip_with(entry: &str, bytes: &[u8]) -> Vec<u8> {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        writer.start_file("include/oodle2.h", options).unwrap();
        writer.write_all(b"header").unwrap();
        writer.start_file(entry, options).unwrap();
        writer.write_all(bytes).unwrap();
        writer.finish().unwrap().into_inner()
    }

    pub(crate) fn sources(oodle_url: String, zip: &[u8], sidecar: Option<Embedded>) -> Sources {
        Sources {
            sidecar,
            oodle: Remote {
                url: oodle_url,
                size: zip.len() as u64,
                sha256: crate::safety::sha256_hex(zip),
            },
            oodle_entry: "lib/liboodle-data-shared.so".into(),
        }
    }

    #[test]
    fn oodle_downloads_only_its_pinned_zip_and_records_the_consent() {
        let zip = zip_with("lib/liboodle-data-shared.so", b"not really oodle");
        let url = serve("/gcc-x64-release.zip", zip.clone());
        let dir = tempfile::tempdir().unwrap();
        let cache = dir.path().join("builder");
        let sources = sources(url.clone(), &zip, None);
        assert_eq!(oodle(&sources, &cache), None);
        let mut seen = Vec::new();
        let path = fetch_oodle(&sources, &cache, &AtomicBool::new(false), &mut |d, t| {
            seen.push((d, t))
        })
        .unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"not really oodle");
        assert_eq!(seen.last(), Some(&(zip.len() as u64, zip.len() as u64)));
        let (found, record) = oodle(&sources, &cache).unwrap();
        assert_eq!(found, path);
        assert_eq!(record.source, url);
        assert_eq!(record.zip_sha256, sources.oodle.sha256);
        assert!(!cache.join("oodle.zip").exists(), "the zip is not kept");

        // A changed library, or a record for another zip, asks again.
        fs::write(&path, "changed").unwrap();
        assert_eq!(oodle(&sources, &cache), None);

        // Another zip at the URL: refused, nothing kept.
        let other = sources.clone();
        let mut other_pin = other.clone();
        other_pin.oodle.sha256 = "0".repeat(64);
        let error =
            fetch_oodle(&other_pin, &cache, &AtomicBool::new(false), &mut |_, _| {}).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().map(|r| r.kind),
            Some(Kind::DigestMismatch)
        );
        assert!(!cache.join("oodle.zip").exists() && !cache.join("oodle.zip.part").exists());

        // A wrong URL: a download failure, by URL.
        let mut missing = sources.clone();
        missing.oodle.url = url.replace("gcc-x64", "nothing");
        let error =
            fetch_oodle(&missing, &cache, &AtomicBool::new(false), &mut |_, _| {}).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().map(|r| r.kind),
            Some(Kind::DownloadFailed)
        );

        // A cancel stops it.
        let cancel = AtomicBool::new(true);
        let error = fetch_oodle(&sources, &cache, &cancel, &mut |_, _| {}).unwrap_err();
        assert!(error.is::<Cancelled>());
    }

    #[test]
    fn oodle_from_a_file_takes_the_pinned_zip_only() {
        let zip = zip_with("lib/liboodle-data-shared.so", b"lib");
        let dir = tempfile::tempdir().unwrap();
        let cache = dir.path().join("builder");
        let sources = sources("http://127.0.0.1:9/none".into(), &zip, None);
        let file = dir.path().join("gcc-x64-release.zip");
        fs::write(&file, &zip).unwrap();
        let path = oodle_from_file(&sources, &cache, &file).unwrap();
        assert_eq!(fs::read(path).unwrap(), b"lib");
        assert_eq!(oodle(&sources, &cache).unwrap().1.source, "file");

        let wrong = dir.path().join("liboodle-data-shared.so");
        fs::write(&wrong, b"lib").unwrap();
        let error = oodle_from_file(&sources, &cache, &wrong).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().map(|r| r.kind),
            Some(Kind::DigestMismatch)
        );

        // The pinned digest of a zip without the library: refused by its entry.
        let empty = zip_with("lib/other.so", b"x");
        fs::write(&file, &empty).unwrap();
        let sources = super::tests::sources("http://127.0.0.1:9/none".into(), &empty, None);
        let error = oodle_from_file(&sources, &cache, &file).unwrap_err();
        assert_eq!(
            crate::i18n::error(&error),
            crate::i18n::refusal(&Refusal::new(Kind::OodleEntry, file.display()))
        );
    }

    #[test]
    fn the_sidecar_is_unpacked_only_at_its_pin() {
        let program: &'static [u8] = b"#!/bin/sh\necho eve-extract\n";
        let dir = tempfile::tempdir().unwrap();
        let cache = dir.path().join("builder");
        let embedded = Embedded {
            bytes: program,
            sha256: sha256_hex(program),
        };
        let mut sources = sources(String::new(), b"", Some(embedded.clone()));
        let path = unpack_sidecar(&sources, &cache).unwrap();
        assert_eq!(path, cache.join(SIDECAR));
        assert_eq!(fs::read(&path).unwrap(), program);
        assert!(!cache.join(format!("{SIDECAR}.part")).exists());
        // The cache keeps Oodle and the usmap: private, also when an older installer made it.
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = || fs::metadata(&cache).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode(), 0o700);
            fs::set_permissions(&cache, fs::Permissions::from_mode(0o755)).unwrap();
            unpack_sidecar(&sources, &cache).unwrap();
            assert_eq!(mode(), 0o700);
        }

        // A changed cached copy is unpacked again.
        fs::write(&path, b"changed").unwrap();
        assert_eq!(unpack_sidecar(&sources, &cache).unwrap(), path);
        assert_eq!(fs::read(&path).unwrap(), program);

        // Bytes that are not the pinned ones are refused and the cached copy is not replaced.
        fs::write(&path, b"changed").unwrap();
        sources.sidecar.as_mut().unwrap().sha256 = "1".repeat(64);
        let error = unpack_sidecar(&sources, &cache).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().map(|r| r.kind),
            Some(Kind::DigestMismatch)
        );
        assert_eq!(fs::read(&path).unwrap(), b"changed");
        sources.sidecar = None;
        let error = unpack_sidecar(&sources, &cache).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().map(|r| r.kind),
            Some(Kind::NoSidecar)
        );
    }

    #[test]
    fn the_release_carries_its_pinned_sidecar() {
        let sources = Sources::release();
        assert!(sources.oodle.url.ends_with(".zip") && sources.oodle.sha256.len() == 64);
        assert!(sources.oodle_entry.ends_with(sources.oodle_file()));
        if let Some(sidecar) = &sources.sidecar {
            assert_eq!(sha256_hex(sidecar.bytes), sidecar.sha256);
        }
    }

    #[test]
    fn the_usmap_is_read_from_ue4ss_and_kept_by_build() {
        let dir = tempfile::tempdir().unwrap();
        let (win64, cache) = (dir.path().join("Win64"), dir.path().join("builder"));
        fs::create_dir_all(win64.join("ue4ss")).unwrap();
        let error = take_usmap(&win64, Some("24463856"), &cache).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().map(|r| r.kind),
            Some(Kind::NoUsmap)
        );
        let usmap = win64.join("ue4ss/SB-4.26.2-0+++UE4+Release-4.26.USMAP");
        fs::write(&usmap, [0xC4, 0x30, 3, 0, 0]).unwrap();
        let before = fs::read(&usmap).unwrap();
        let cached = take_usmap(&win64, Some("24463856"), &cache).unwrap();
        assert_eq!(cached, cache.join("usmap/24463856.usmap"));
        assert_eq!(fs::read(&cached).unwrap(), before);
        assert_eq!(
            fs::read(&usmap).unwrap(),
            before,
            "the game folder is only read"
        );
        // Gone from the game folder (a cleanup): the cached copy serves.
        fs::remove_file(&usmap).unwrap();
        assert_eq!(
            take_usmap(&win64, Some("24463856"), &cache).unwrap(),
            cached
        );
        // Another build has none.
        assert!(take_usmap(&win64, Some("1"), &cache).is_err());
        // A file that is not a usmap, or of a version CUE4Parse does not read, is refused by name.
        for bad in [&b"PK\x03"[..], &[0xC4, 0x30, 6], &[0xC4]] {
            fs::write(win64.join("Mappings.usmap"), bad).unwrap();
            let error = take_usmap(&win64, Some("24463856"), &cache).unwrap_err();
            assert_eq!(
                error.downcast_ref::<Refusal>().map(|r| r.kind),
                Some(Kind::UsmapInvalid),
                "{bad:?}"
            );
        }
        // One older than the game's program was written for an earlier build: refused by name, not copied.
        fs::remove_file(win64.join("Mappings.usmap")).unwrap();
        fs::write(&usmap, [0xC4, 0x30, 3, 0, 1]).unwrap();
        let exe = win64.join(crate::adapter::EXE);
        fs::write(&exe, b"").unwrap();
        let later = fs::metadata(&usmap).unwrap().modified().unwrap() + Duration::from_secs(60);
        File::options()
            .write(true)
            .open(&exe)
            .unwrap()
            .set_modified(later)
            .unwrap();
        let error = take_usmap(&win64, Some("24463856"), &cache).unwrap_err();
        assert_eq!(
            error.downcast_ref::<Refusal>().map(|r| r.kind),
            Some(Kind::UsmapStale)
        );
        assert_eq!(fs::read(&cached).unwrap(), before);
        assert_eq!(
            cached_usmap(&cache, Some("../x")),
            cache.join("usmap/unknown.usmap")
        );
    }
}
