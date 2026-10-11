//! The avatar pack builder: Minecraft draws EVE from a private pack (SBAVMESH meshes, baked PNGs, `wardrobe.txt`,
//! `pack.txt`) that each player builds from their own copy of Stellar Blade, read-only. EveExtract (a .NET sidecar on
//! CUE4Parse) reads the game's containers; this module reads the item tables and the owned items, bakes the
//! textures, writes the manifests and swaps the new pack in. Nothing it reads or writes leaves the player's PC.

pub mod catalog;
pub mod owned;
