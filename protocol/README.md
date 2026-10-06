# Shared protocol

The shared directory is private to the local user (0700). Text snapshots are bounded and versioned. Readers validate complete-message footers, identity, timestamps, revisions, dimensions and finite coordinates before using a snapshot. Implementations and shared [fixtures](fixtures/) are authoritative for exact grammars and bounds.

| Product | Producer | Reader | Contract |
|---|---|---|---|
| Terrain | Minecraft | Lua | `SBMC 1 TERRAIN`, fixed anchor, revision and merged boxes |
| EVE position | Lua | Minecraft | `SBMC 1 EVE`, session, state, acknowledged terrain revision and feet position |
| Native terrain visuals | Minecraft | Lua | Independent visual revision and supported face/texture data |
| Capture request | Minecraft | Lua/native | `SBMC_CAPTURE_SESSION 2`, RUN, SESSION, TIME, SIZE, STATE, END |
| Raw frame | Native | Minecraft | Mapping v1 containing frame-v6 metadata, RGBA8 and R32F depth |
| Control permit | Lua | Native | `SBMC_CONTROL 1`, run/session, generation, sequence and native context |
| Input / acknowledgement | Minecraft / native | Native / Minecraft | `SBMC_INPUT 1`, ordered edges, epochs, sequence and leases |
| Pose request | Minecraft | Lua/native | `SBMC_POSE_REQUEST 1`, RUN, TIME, STATE, END |
| Pose components | Lua | Native/Minecraft | `SBMC_POSE_COMPONENTS 1`, generation, placement and EVE's skeletal mesh components |
| Pose | Native | Minecraft | Pose mapping v1 with world, component-space bone and morph-weight floats |

Positions use Unreal `(X,Y,Z)` to Minecraft `(X,Z,Y)` with 100 Unreal units per block and the session's anchor transform. Native camera/depth and feet position must match the terrain link; mixing older coordinate bases is unsupported.

## Raw mapping v1

Minecraft exclusively creates `eve-frames-<RUN>.bin` with mode 0600. RUN and SESSION are separate 32-character lowercase hexadecimal identities. Each run fixes width 1–3840 and height 1–2160. Let `P = width * height`, `stride = align64(4096 + P * 8)`, and `total = 4096 + 3 * stride`. Integers and float planes use little-endian layout.

| Header offset | Field |
|---|---|
| 0 | Eight-byte magic `SBMCFRM1` |
| 8, 12 | Version 1; header size 4096 (u32) |
| 16, 20, 24, 28 | Width, height, slot stride, total bytes (u32) |
| 32, 64 | RUN and SESSION (32 ASCII bytes each) |
| 96 | Consumer PID (u32) |
| 128 | Atomic middle-slot index with dirty bit 4 (u32) |
| 192, 196, 200 | Producer PID, state, error (u32) |
| 208, 216 | Producer Unix milliseconds and completed counter (u64) |
| 256, 264, 272 | Consumer state (u32), Unix milliseconds and accepted counter (u64) |

Reserved bytes are zero. Producer states are 0 unclaimed, 1 ready, 2 released, 3 failed. Consumer states are 1 active, 2 stopped. Producer PID `UINT_MAX` marks a cancelled unclaimed run. Claims and cancellation use the same atomic word.

Initially the producer owns slot 1, consumer slot 2 and middle slot 0. Atomic exchange transfers middle ownership; a published middle sets dirty bit 4. The reader copies its acquired slot before exchanging again. A slot contains counter (u64) at 0, publication Unix milliseconds (u64) at 8, metadata length (u32) at 16, format 1 (u32) at 20, ASCII frame-v6 metadata at 64, RGBA8 at 4096, then P R32F depth values. Metadata is limited to 2048 bytes.

A frame-v6 generation is exactly two registered image jobs, color and depth, in either order; a `MASK depth 0` line records that no coverage job exists. Alpha is 255 where depth is positive and below 65504 (the binary16 capture clear value) and 0 elsewhere. Non-finite or non-positive depth becomes 0. A retired mask job fails the run. No mapped slice escapes the Java reader. Native release acknowledgement is required before deleting an active producer's mapping; an unresolved mapping is retained and never reset for another run.

## Pose stream v1

Minecraft refreshes `eve-pose-request.txt` (`SBMC_POSE_REQUEST 1`, RUN, TIME in Unix milliseconds, STATE `active` or `stopped`, END) at least every second; a request older than three seconds is inactive. While it is active, Lua publishes [`eve-pose-components.0/1`](fixtures/pose-components.txt) a few times per second when the composition changes: RUN, a GENERATION that increases within the run, the entered link's SESSION and `ANCHOR x y z unitsPerBlock` (Unreal) or `none`, COUNT (1–16), then per component its object, class and skeletal mesh addresses (16 lowercase hex digits), bone count (0 for leader-pose followers, at most 256), morph count (at most 1024), name, mesh name and leader or `-`, followed by `BONES` (component-space order) and `MORPHS` (`MorphTargets` order) name lines. Readers take the newer complete slot of their run.

The adapter reads the listed components on the game thread at the verified gameplay XInput poll, after the previous frame's animation and physics. A component is read only while it is the live, non-pending-kill `GUObjectArray` object at its address with the published class and mesh; otherwise its status is 0. Faults are contained.

Minecraft exclusively creates `eve-pose-<RUN>.bin` (0600, 200704 bytes): the raw mapping v1 header and ownership protocol with magic `SBMCPOS1`, slot stride 65536 at offset 16, payload capacity 16352 floats at 20, total bytes at 28, RUN at 32 and no SESSION. Offsets 224, 228 and 232 hold the producer's last and maximum read time (u32 microseconds) and total read time (u64). A slot holds counter (u64) at 0, publication Unix milliseconds (u64) at 8, generation (u64) at 16, format 1 at 24, component count at 28, payload floats at 32, one status word per component at 64 (bit 0 world, 1 bones, 2 morphs) and the payload at 128: per component its world transform, its bone transforms in component space, then its morph weights. A transform is 10 floats: quaternion xyzw, translation xyz, scale xyz, Unreal space. Minecraft decodes a slot only with the table of the same generation.

## Text publication and legacy captures

Java publishes text with atomic replacement. Lua uses alternating slots where Windows cannot rename over an existing destination. Control permits retire the old slot under a unique owned name before publishing; cleanup failure retains ownership for retry. A missing/stale permit neutralizes input.

Frame-v1 uses PNG color; v2 uses BMP color with PNG mask and HDR depth. Frame-v3 describes a pending asynchronous still and v4 the encoded continuous compatibility path. Frame-v5 described three-job raw payloads and is no longer accepted; v6 replaces it. Pending encoded output requires observed file closure and content validation; metadata alone is insufficient. The active `/stellarview play` and `/stellarview livehd` path uses v6.

See [RawFrameBus.java](../minecraft/src/main/java/org/stellarbridge/RawFrameBus.java), [AvatarStream.java](../minecraft/src/main/java/org/stellarbridge/AvatarStream.java), [frame_bus.hpp](../native/include/frame_bus.hpp), [pose_stream.hpp](../native/include/pose_stream.hpp), [input.hpp](../native/include/input.hpp) and the [Lua modules](../stellar/Mods/StellarCollisionProbe/Scripts/) for executable contracts.
