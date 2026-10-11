//! What this installer release was built and tested against.

/// Stellar Blade's Steam app id.
pub const SB_APP_ID: &str = "3489700";
/// The Steam build whose executable the adapter supports (`docs/setup.md`).
pub const SB_BUILD_ID: &str = "24463856";
/// `SB-Win64-Shipping.exe` of that build; the same pin as `tools/input_proxy.py` and the adapter itself.
pub const SB_EXE_SHA256: &str = "573aaff1c9455f85ea6036ef6f6ccb0774de4164722a296276fbbfc7fa87545c";
/// The Proton build the bridge was tested on. Others get a warning, never a refusal.
pub const PROTON_TESTED: &str = "proton-cachyos-11.0-20261005-slr-x86_64_v3";
/// `ue4ss/UE4SS.dll` builds known to work (v3.0.1 Beta #0, `08a0733`). Others get an "untested" warning.
pub const UE4SS_KNOWN_SHA256: &[&str] =
    &["e0cc51ce5b47d5be82194510b0b4b4054dd15ce0322a983655a022f92d27dabe"];

pub const MINECRAFT: &str = "1.21.1";
pub const FABRIC_LOADER_MIN: &str = "0.19.5";
pub const FABRIC_API_FILE: &str = "fabric-api-0.116.17+1.21.1.jar";
/// Modrinth and the Fabric maven agree on it.
pub const FABRIC_API_SHA512: &str = "98c478217da19181f0e4df3ea6c2da6bdbda271c7544a4aea048513a49b444f1ff5b71dc0df6303017c48e8ff69b7bb6af6179bdf0d0e09ab3c1ee08411857a8";

/// Proton loads the adapter only with this override; shown for the user to copy, never written.
pub const LAUNCH_OPTION: &str = "WINEDLLOVERRIDES=\"xinput1_3=n,b\" %command%";

/// Stellar Blade's licence agreement, linked from the consent page: modding the game is the player's own risk.
pub const SB_EULA: &str = "https://store.steampowered.com/eula/3489700_eula_0";

/// `vendor/fabric-server/fabric-server-launch.jar`: Fabric's server launcher for Minecraft 1.21.1, Fabric Loader 0.19.5
/// and Fabric Installer 1.1.2, embedded for new server folders (see its NOTICE).
pub const FABRIC_SERVER_LAUNCHER_SHA256: &str =
    "809d99b16dd4d4b57384d128724ab0d6560f859aaba989dc6750d8437a447ed0";
/// The oldest Java that runs Minecraft 1.21.1's server.
pub const JAVA_MIN: u32 = 21;
/// Mojang's licence for the Minecraft server, linked wherever the operator may accept it.
pub const MINECRAFT_EULA: &str = "https://aka.ms/MinecraftEULA";

/// Where this release's assets download from: the pack builder's EveExtract sidecar is one of them. Its sha256 is
/// pinned at build time (`SBMC_EVE_EXTRACT`, see `build.rs`).
pub const RELEASES: &str =
    "https://github.com/MotherSphere/stellarblade-minecraft/releases/download";

/// Oodle, which Stellar Blade's containers are compressed with: proprietary (Epic Games, RAD Game Tools), never shipped
/// or hosted by this project, downloaded only after the player agrees. The OodleUE release CUE4Parse itself uses
/// (Oodle 2.9.16); the zip's size and sha256 are GitHub's published digests for the asset, read with
/// `gh api repos/WorkingRobot/OodleUE/releases/tags/2026-06-04-1357`.
pub const OODLE_RELEASE: &str =
    "https://github.com/WorkingRobot/OodleUE/releases/tag/2026-06-04-1357";
/// The Unreal Engine EULA, which the OodleUE release says its use falls under.
pub const UE_EULA: &str = "https://www.unrealengine.com/eula";

/// One OS's Oodle zip and the library EveExtract loads from it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OodleZip {
    pub url: &'static str,
    pub size: u64,
    pub sha256: &'static str,
    /// The library's path inside the zip; its file name is the cached library's.
    pub entry: &'static str,
}

pub const OODLE: OodleZip = if cfg!(windows) {
    OodleZip {
        url: "https://github.com/WorkingRobot/OodleUE/releases/download/2026-06-04-1357/clang-cl-x64-release.zip",
        size: 5_286_990,
        sha256: "37f816a3e8f5a65a11f6defc8c28c0508287602767741ba4803c6a21a62a877b",
        entry: "bin/oodle-data-shared.dll",
    }
} else {
    OodleZip {
        url: "https://github.com/WorkingRobot/OodleUE/releases/download/2026-06-04-1357/gcc-x64-release.zip",
        size: 1_924_782,
        sha256: "458c66945ea6124d4f6fcf64cec5601ab230f61f6677a6ecab6cca621a9d0e11",
        entry: "lib/liboodle-data-shared.so",
    }
};
