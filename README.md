# Stellar Blade × Minecraft

Experimental passthrough for playing EVE in Minecraft Java on Linux, with Stellar Blade running through Proton.

**Minecraft is the primary game and renderer.** Minecraft owns the world, inventory, menus and block interactions. Stellar Blade supplies EVE's native controller, animations and rendered appearance. The bridge composites her captured color and depth into Minecraft; it does not reimplement her combat system in Java. An experimental [avatar view](docs/diagnostics.md#avatar-view-experimental) draws her own meshes in Minecraft from recorded or live bone poses.

The prototype supports a bounded terrain/collision link, continuous framebuffer-sized EVE capture and keyboard/mouse forwarding from Minecraft. Cross-game mob damage, general world streaming and Iris/shader-pack compatibility are not implemented. Hair/transparency and capture latency still need work. Minecraft's displayed FPS is distinct from the rate of new EVE images.

## Start a session

First follow [setup and build instructions](docs/setup.md). Use a private creative test world, Fast/Fancy graphics without Iris, and a backed-up Stellar Blade save in a clear outdoor location.

1. In Minecraft, stand on a full block and run `/stellar start`.
2. In Stellar Blade, load EVE, press **F5** to import collision, then **F8** to enter the elevated lab. The lab must be clear of existing level geometry.
3. Return to Minecraft and run **`/stellarview play`**. Close chat and release held controls. Forwarding begins after a fresh displayed image and a neutral native acknowledgement.
4. Use **B** to switch between EVE controls and Minecraft build interactions. This binding is configurable in Minecraft's Controls menu.
5. To stop, use **`/stellarview off`**, then **F11** in Stellar Blade to return EVE, then **`/stellar stop`** in Minecraft.

Do not save, travel, load another save or reload Lua while EVE is in the lab. The bridge does not disable autosave. If returning fails, supporting collision stays in place so F11 can be retried.

The patch is fixed around the starting anchor: **9 × 6 × 9 blocks**, at most 256 merged collision boxes, at 100 Unreal units per block. An active session keeps the private Minecraft world running through pause menus and focus changes. Stopping or disconnecting restores normal pause behavior. This is not a multiplayer or survival integration.

## Controls

| Minecraft command or binding | Action |
|---|---|
| `/stellar start`, `/stellar status`, `/stellar stop` | Terrain and position link |
| `/stellarview play` | Continuous EVE view with controls forwarded from Minecraft |
| `/stellarview livehd` | Same continuous view without input forwarding |
| **B**, `/stellarview mode eve`, `/stellarview mode build` | Switch control ownership |
| `/stellarview gain <1..32767>` | Native camera sensitivity; default 2500 |
| `/stellarview status` | Image delivery, Minecraft FPS and stream state |
| `/stellarview off` | Stop rendering/input and restore the normal camera |
| `/stellaravatar play` | Experimental: EVE drawn by Minecraft from her streamed pose, Minecraft camera, no capture |
| `/stellaravatar live`, `/stellaravatar status`, `/stellaravatar off` | Avatar without input forwarding, stream statistics, stop |

EVE mode follows Stellar Blade's default controller layout:

| Minecraft binding (default key) | Controller | Stellar Blade |
|---|---|---|
| Movement (W, A, S, D) | Left stick | Move |
| EVE walk (C) | Left stick at 35 % | Walk |
| Jump (Space), Sneak (Shift), EVE sprint (V) | A, B, L3 | Jump, dodge (held: sprint after it), sprint |
| Sprint (Ctrl) | LT | Drone aim; attack then fires with RT |
| Attack (left click), Use (right click) | X, Y | Light attack, heavy attack |
| Inventory (E) | LB | Block, parry |
| Hotbar 1, 2, 3, 4 | LB, then X, Y, B or A 40 ms later | Beta skills |

Six extra controller buttons (Y, RB, LT, RT, R3, D-pad up) are initially unbound. Actions follow Stellar Blade's controller settings. In EVE mode Minecraft's own inventory and hotbar 1–4 wait for build mode. Build mode keeps camera look, neutralizes EVE's actions and restores normal Minecraft clicks, sneak interactions, inventory and hotbar. Chat and the other hotbar slots remain Minecraft-owned.

Lost focus, menus, stale frames, native pause or an invalid context neutralize forwarded controls. Held keys must be released before control resumes.

## Repository layout

| Path | Contents |
|---|---|
| `minecraft/` | Fabric mod, renderer, input bridge and Java tests |
| `stellar/Mods/StellarCollisionProbe/` | Deployable UE4SS Lua mod |
| `stellar/tests/` | Lua protocol, geometry and lifecycle tests |
| `native/src/`, `native/include/` | Version-pinned Windows capture/input adapter |
| `native/tests/`, `native/fixtures/` | Portable checks and isolated Windows test hosts |
| `protocol/` | Shared wire contract and fixtures |
| `tools/`, `tools/tests/` | Offline checks and owned-installation tools |
| `docs/` | Setup, architecture and diagnostic reference |

Run all offline checks from the repository root with Java 21, Lua, Python, CMake and a C++17 compiler available:

```sh
python tools/check.py
```

Use `--only lua`, `--only python`, `--only native` or `--only minecraft` for one component. The runner builds artifacts without installing them or launching games.

See [architecture](docs/architecture.md), [wire contracts](protocol/README.md) and [diagnostics](docs/diagnostics.md). The separate terrain renderer inside Stellar Blade is a diagnostic, not the main gameplay route.

## References

The transport adapts [SkyCraft](https://github.com/chasmlol/SkyCraft)'s three-buffer ownership design. [SkyCraft PR 26](https://github.com/chasmlol/SkyCraft/pull/26) provides Linux mapping context; [2010 Rust rewrite mashup](https://github.com/chasmlol/2010-rust-rewrite-mashup) provides additional allocation-reuse and render-product examples. Engine-specific hooks are implemented and checked separately for Stellar Blade. See [third-party notices](THIRD-PARTY-NOTICES.md).

No proprietary game assets are included.
