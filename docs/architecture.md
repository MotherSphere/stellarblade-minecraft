# Architecture

## Responsibilities

Minecraft owns the world, block changes, inventory, menus and interaction handlers. It exports nearby collision shapes, receives EVE's feet position, and composites EVE's rendered image with Minecraft depth. Its targeting uses the displayed camera while reach remains measured from the player.

The UE4SS Lua mod imports a fixed terrain patch into an elevated lab, tracks the original return position, reads the native pawn/camera and manages bridge-owned capture components. Replacement collision is prepared before old collision is retired. Blocks intersecting EVE are deferred. Failed return keeps the supporting floor available.

The Windows `xinput1_3.dll` adapter verifies the complete executable identity and native callsites. It forwards physical controllers through the system XInput implementation and delivers bridge input only at the verified gameplay poll. It intercepts only registered bridge image jobs before encoding and publishes complete raw images.

## Rendering and ownership

Continuous capture uses three persistent Unreal capture components for display color, coverage and depth. Capture dimensions follow the Minecraft framebuffer at run start, preserving aspect within 3840 × 2160. Restart the view to change resolution after a resize. One initial synchronous depth export establishes readiness. Subsequent jobs use the engine image queue with at most one pending capture generation.

Game-thread capture scheduling is checked once per native frame; terrain work retains its separate 200 ms interval. Persistent display-color captures enable the pinned TemporalAA flag locally. Fog is restricted to rendered opaque pixels. These settings do not change normal game graphics, and separate capture passes still limit hair and translucency accuracy.

The producer owns a back slot, the consumer owns a front slot, and an atomic exchange transfers the middle slot. Each run has a new private mapping. A slow reader obtains the latest complete image without building a frame backlog. Java copies the acquired planes into detached CPU storage before releasing the mapped slot. At most three unused color/depth pairs are retained per bus; outstanding frames keep ownership until release, even after the mapping closes.

A single Java worker checks raw availability after a 1 ms delay; encoded and inactive paths use 20 ms. The render thread consumes one latest prepared frame, reuses its GL textures and swaps camera/color/depth coherently. Stopping prevents further scheduling and releases owned resources. Metadata, dimensions, identity, reserved bytes, depth and leases remain validated.

This removes repeated image encoding/decoding and avoids per-frame buffer allocation once reusable storage is available. It does **not** remove Unreal's GPU surface readback: the hook runs after that readback. GPU staging needs separate resource ownership and fence integration; lowering delays alone cannot provide it. Capture timing, accepted image cadence and Minecraft rendering FPS measure different things.

## Input and lifetimes

EVE mode forwards Minecraft's actual configured controls. Build mode retains native look but returns click/sneak interactions to Minecraft. Input has its own heartbeat and bounded ordered press/release queue, independent of image-write completion. It requires a fresh displayed frame, matching terrain session, native permit and neutral handshake. Acknowledgement means a native gameplay poll consumed the input, not that a skill dealt damage.

Stale data, lost focus, menus and unsupported native contexts neutralize controls. Expired epochs cannot be revived by newer heartbeat traffic. Windows permit publication renames the old slot to a uniquely owned retired path before publishing its replacement; cleanup can be retried without deleting a live reader's file.

## Scope and references

The implementation currently limits terrain to one 9 × 6 × 9 patch and 256 merged collision boxes. Terrain is not streamed around a moving player. Combat against Minecraft mobs, survival rules, multiplayer, Iris and arbitrary mod compatibility require additional integration. Native outfit/accessory/effect capture is not universally supported.

[SkyCraft](https://github.com/chasmlol/SkyCraft/tree/bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32) supplies the adapted triple-buffer exchange and useful examples of persistent staging/textures and newest-ready consumption. [PR 26](https://github.com/chasmlol/SkyCraft/pull/26) illustrates Linux file-backed transport. This bridge keeps fresh run identities and explicit release acknowledgements instead of resetting an in-use mapping.

[2010 Rust rewrite mashup](https://github.com/chasmlol/2010-rust-rewrite-mashup/tree/f608f85e407ff1b7689d54a9aafdd16e95711ac4) provides allocation-reuse and rendering-diagnostic examples. The Java buffer reuse follows the same principle without importing its engine or dependencies. See [wire contracts](../protocol/README.md) and [third-party notices](../THIRD-PARTY-NOTICES.md).
