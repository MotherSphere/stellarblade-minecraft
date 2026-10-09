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
