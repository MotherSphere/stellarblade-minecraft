# Stellar Blade × Minecraft

Experimental passthrough for playing EVE in Minecraft Java on Linux, with Stellar Blade running through Proton.

**Minecraft is the primary game and renderer.** Minecraft owns the world, inventory, menus and block interactions. Stellar Blade supplies EVE's native controller, animations and rendered appearance. The bridge composites her captured color and depth into Minecraft; it does not reimplement her combat system in Java. An experimental [avatar view](docs/diagnostics.md#avatar-view-experimental) draws her own meshes in Minecraft from recorded or live bone poses.

The prototype supports a bounded terrain/collision link, continuous framebuffer-sized EVE capture and keyboard/mouse forwarding from Minecraft. With the avatar view, EVE fights Minecraft's mobs, including mods' mobs ([combat](#combat)). General world streaming and Iris/shader-pack compatibility are not implemented. Hair/transparency and capture latency still need work. Minecraft's displayed FPS is distinct from the rate of new EVE images.

## Start a session

First follow [setup and build instructions](docs/setup.md). Use a private creative or survival test world, Fast/Fancy graphics without Iris, and a backed-up Stellar Blade save in a clear outdoor location.

1. In Minecraft, stand on a full block and run `/stellar start`.
2. In Stellar Blade, load EVE, press **F5** to import collision, then **F8** to enter the elevated lab. The lab must be clear of existing level geometry.
3. Return to Minecraft and run **`/stellarview play`**. Close chat and release held controls. Forwarding begins after a fresh displayed image and a neutral native acknowledgement.
4. Use **B** to switch between EVE controls and Minecraft build interactions. This binding is configurable in Minecraft's Controls menu.
5. To stop, use **`/stellarview off`**, then **F11** in Stellar Blade to return EVE, then **`/stellar stop`** in Minecraft.

Do not save, travel, load another save or reload Lua while EVE is in the lab. The bridge does not disable autosave. If returning fails, supporting collision stays in place so F11 can be retried.

The patch is fixed around the starting anchor: **9 × 6 × 9 blocks**, at most 256 merged collision boxes, at 100 Unreal units per block. An active session keeps the private Minecraft world running through pause menus and focus changes. Stopping or disconnecting restores normal pause behavior. This is not a multiplayer integration.

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
| EVE drone aim (Ctrl) | LT | Drone gun; attack then fires with RT |
| Attack (left click), Use (right click) | X, Y | Light attack, heavy attack |
| Inventory (E) | LB | Guard, parry |
| Hotbar 1, 2, 3, 4 | LB, then X, Y, A or B 40 ms later | Beta skills |
| EVE burst (Alt) with hotbar 1–4 | RB, then X, Y, A or B | Burst skills |
| EVE heal (Q position: A on AZERTY), EVE item (R) | D-pad up, D-pad down | Heal, item |
| EVE interact (F), EVE scan (Tab), EVE lock-on (middle click) | RT, Back, R3 (avatar: Minecraft's lock-on) | Interact, scan, lock-on |

The EVE keys default to Stellar Blade's own keyboard keys (its `DefaultInput.ini`) and are rebindable under Stellar Bridge in Minecraft's controls; Minecraft's own sprint key does nothing for EVE. Six extra controller buttons (Y, RB, LT, RT, R3, D-pad up) are initially unbound. Actions follow Stellar Blade's controller settings. In EVE mode Minecraft actions bound to the inventory key, hotbar 1–4 or an EVE key (inventory, hotbar, drop, swap hands, pick block) wait for build mode. Build mode keeps camera look, neutralizes EVE's actions and restores normal Minecraft clicks, sneak interactions, inventory and hotbar. Chat and the other hotbar slots remain Minecraft-owned.

Lost focus, menus, stale frames, native pause or an invalid context neutralize forwarded controls. Held keys must be released before control resumes.

## Combat

With `/stellaravatar play` at the bridged position, EVE fights any living entity Minecraft knows, from vanilla or a mod, through Minecraft's own damage, so each mob keeps its own reactions, sounds and loot:

- **Strikes.** While her sword swings (Stellar Blade's own blade trail plays), every living entity it sweeps through takes damage: 6 for a light slash, 9 for a strong one, 8 for a thrust, doubled after a charged attack's signal, with more knockback for strong attacks. Players, armor stands and the player's own pets are spared.
- **Dodge and guard.** Every hit on EVE, melee or projectile, is judged at its impact against the latest presses: guard pressed within 0.25 s is a perfect parry (no damage; a melee attacker is pushed back and slowed; EVE plays her guarded-hit reaction and the parry effect in Stellar Blade); dodge within 0.3 s is a perfect dodge (no damage; Minecraft's world runs at 6 ticks per second for 1.5 s under a cold tint, and EVE plays Stellar Blade's own perfect dodge: its back flip or spin by direction, its slow motion, effects, sounds, Burst gauge and counter window; if the game does not take it, Lua slows Stellar Blade's world to 0.3 for half a second and plays the evade effects instead); a dodge within 0.5 s still avoids the hit; a held guard lets a quarter through, with EVE's guarded-hit reaction. The action bar names each verdict. The timing follows the impact, not a mob's animation: a dodge 0.2 s before a three-second wind-up lands is perfect.
- **Lock-on.** In avatar mode the lock-on key (middle click) targets the living entity nearest the crosshair within 40 degrees of it, 20 blocks and in sight (nothing locks when none qualifies), outlines it and turns Minecraft's camera toward it. Stellar Blade's own lock-on then holds EVE facing it, on a hidden stand-in Lua keeps at the target: she strafes around it with her lock-on locomotion. The key again, the target's death or distance beyond 28 blocks, build mode or a lost link releases it. Stellar Blade's R3 is not pressed then.
- **HUD.** While EVE replaces the player, Stellar Blade's dotted health bar takes the hearts' place, fed by Minecraft's health (absorption as its shield row above it), and its Beta and Burst cubes sit above it, clear of the held item name and the action bar, streamed from Stellar Blade (200 points a cube, a perfect dodge's 130 Burst lighting quarters of one). Burst shows once Stellar Blade has unlocked it.
- **Health.** In survival, Minecraft's health is EVE's health, with armor, effects and mods. She flashes red when hurt. Mobs ignore creative players. While the session lasts but EVE cannot answer (a pause menu, since the bridge keeps the world running; EVE outside the patch), nothing hurts her. Stellar Blade's own pause is not detected: with Minecraft's pause on lost focus disabled, hits land in full while Stellar Blade is paused. Minecraft's suffocation, fall and cramming damage do not apply while Stellar Blade owns her position. Her death keeps the link and Stellar Blade's lab: after respawning in the bridge's dimension, the player returns to the bridge anchor and EVE's controls resume once the held keys are released. A respawn in another dimension ends the bridge; run `/stellar start` again.

A hit makes EVE react in Stellar Blade (experimental: three ways of playing her stock light-hit reaction take turns until one animates her, which is then kept; moving cancels it, and a try while she moves or already animates is not counted). Judgement uses the presses, not Stellar Blade's own state. A mod that applies damage before its attack animation shows can only be dodged before the damage.

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
