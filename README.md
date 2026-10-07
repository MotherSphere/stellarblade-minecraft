# Stellar Blade × Minecraft

Experimental passthrough for playing EVE in Minecraft Java on Linux, with Stellar Blade running through Proton.

**Minecraft is the primary game and renderer.** Minecraft owns the world, inventory, menus and block interactions. Stellar Blade simulates EVE: her controller, animations, skills and effects. In the main **avatar mode** (`/stellaravatar play`), Minecraft draws her own meshes from the bone poses the native adapter streams every Stellar Blade frame, with Minecraft's camera; Stellar Blade's camera follows it. The older **capture mode** (`/stellarview play`) composites her captured color and depth instead, at a lower rate; it stays as a fallback and for comparison.

The prototype supports a bounded terrain/collision link, keyboard/mouse forwarding from Minecraft, EVE's combat against Minecraft's mobs, including mods' mobs ([combat](#combat)), Stellar Blade's lock-on, and its HUD. General world streaming is not implemented. Under an Iris shader pack, avatar mode is meant to draw EVE through Minecraft's entity programs so the pack shades her, with fewer material details; this is experimental and not yet validated in game ([shader packs](docs/diagnostics.md#shader-packs)). Hair and transparency still need work. In capture mode, Minecraft's displayed FPS is distinct from the rate of new EVE images.

## Start a session

First follow [setup and build instructions](docs/setup.md). Use a private creative or survival test world and a backed-up Stellar Blade save in a clear outdoor location. Avatar mode runs with Fast, Fancy or Fabulous graphics; Iris shader packs are experimental and not yet validated in game; capture mode needs Fast/Fancy without Iris.

1. In Minecraft, stand on a full block and run `/stellar start`.
2. In Stellar Blade, load EVE, press **F5** to import collision, then **F8** to enter the elevated lab. The lab must be clear of existing level geometry: about 34 m square, from 4 m to 18 m above EVE.
3. Return to Minecraft and run **`/stellaravatar play`** (or `/stellarview play` for capture mode). Close chat and release held controls. Forwarding begins once EVE's streamed pose (in capture mode: a displayed image) is fresh and the native adapter acknowledges neutral input.
4. Use **B** to switch between EVE controls and Minecraft build interactions. This binding is configurable in Minecraft's Controls menu.
5. To stop, use **`/stellaravatar off`** (or `/stellarview off`), then **F11** in Stellar Blade to return EVE, then **`/stellar stop`** in Minecraft.

Do not save, travel, load another save or reload Lua while EVE is in the lab. The bridge does not disable autosave. If returning fails, supporting collision stays in place so F11 can be retried.

The patch is fixed around the starting anchor: **33 × 12 × 33 blocks** (4 below the starting floor, 8 above), at most 1024 merged collision boxes, at 100 Unreal units per block. Water and lava are walls from the fluid up to the patch's top: EVE cannot enter them. An active session keeps the private Minecraft world running through pause menus and focus changes. Stopping or disconnecting restores normal pause behavior. This is not a multiplayer integration.

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
| `/stellaravatar play` | Avatar mode: EVE drawn by Minecraft from her streamed pose, Minecraft camera, controls forwarded |
| `/stellaravatar live`, `/stellaravatar status`, `/stellaravatar off` | Avatar without input forwarding, stream, camera, gauge, combat and evade statistics, stop |
| `/stellaravatar replay` | Loop the latest `sbmc posetest` recording (no Stellar Blade needed) |

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
| EVE Tachy mode (T) | L3 with R3 | Tachy mode, when Stellar Blade allows it |

The EVE keys default to Stellar Blade's own keyboard keys (its `DefaultInput.ini`) and are rebindable under Stellar Bridge in Minecraft's controls; Minecraft's own sprint key does nothing for EVE. Six extra controller buttons (Y, RB, LT, RT, R3, D-pad up) are initially unbound. Actions follow Stellar Blade's controller settings. In EVE mode Minecraft actions bound to the inventory key, hotbar 1–4 or an EVE key (inventory, hotbar, drop, swap hands, pick block) wait for build mode. Build mode keeps camera look, neutralizes EVE's actions and restores normal Minecraft clicks, sneak interactions, inventory and hotbar. Chat and the other hotbar slots remain Minecraft-owned.

Lost focus, menus, stale frames, native pause or an invalid context neutralize forwarded controls. Held keys must be released before control resumes.

## Combat

With `/stellaravatar play` at the bridged position, EVE fights any living entity Minecraft knows, from vanilla or a mod, through Minecraft's own damage, so each mob keeps its own reactions, sounds and loot:

- **Strikes.** While her sword swings (Stellar Blade's own blade trail plays), every living entity it sweeps through takes damage: 6 for a light slash, 9 for a strong one, 8 for a thrust, doubled after a charged attack's signal. Each strike knocks the entity back as a player's plain hit does, strong slashes and thrusts further, charged attacks further still. Her Stellar Blade attack power scales it (her base 450 deals these numbers), and her critical rate (5 % at base) makes a strike deal her critical damage (150 % at base), with Minecraft's critical sound and sparks. Players, armor stands and the player's own pets are spared.
- **Body.** EVE is solid to Minecraft's mobs. Each tick, a living entity a player could push (its own pushability and the team collision rule decide) that stands inside her body, Stellar Blade's capsule (a cylinder 0.8 blocks wide, 1.9 high), is moved out to its edge, at most half a block a tick, without passing through blocks. In Stellar Blade the eight such entities nearest her in the patch become invisible walls that only her movement meets, so she stops at a mob as at a block, while her camera and attacks should ignore them (a Stellar Blade query by object type could still find them: the game test checks the lock-on); a wall that would appear inside her waits until she has left it. Hits do not push EVE herself: Stellar Blade owns her position.
- **Dodge and guard.** Every hit on EVE, melee or projectile, is judged at its impact against the latest presses: guard pressed within 0.25 s is a perfect parry (no damage; a melee attacker is pushed back and slowed for 2 s, an arrow flies back at its shooter, other projectiles bounce off; EVE plays Stellar Blade's own just parry: her deflection, its sparks, sound, brief slow motion and screen effect); within 0.4 s a parry (no damage, EVE's parry reaction); these are Stellar Blade's 0.15 s and 0.3 s windows plus 0.1 s for Minecraft's tick, and a guard press sooner than 0.4 s after the previous press does not count, after Stellar Blade's guard cooldown, so mashing the guard parries nothing; dodge within 0.3 s is a perfect dodge (no damage; Minecraft's world runs at 6 ticks per second for 1.5 s under a cold tint, and EVE plays Stellar Blade's own perfect dodge: its back flip or spin by direction, its slow motion, effects, sounds, Burst gauge and counter window; if the game does not take it, Lua slows Stellar Blade's world to 0.3 for half a second and plays the evade effects instead); a dodge within 0.5 s still avoids the hit; a held guard lets a fifth through, as Stellar Blade's does, with EVE's guarded-hit reaction (its strong form from 8 damage). Damage Minecraft's shields cannot block (a warden's sonic boom, magic, a piercing arrow) goes through any guard; only a dodge avoids it. A parry does not fill Stellar Blade's Beta gauge: the game grants it from its own skill result, which no Show carries. The action bar names each verdict. The timing follows the impact, not a mob's animation: a dodge 0.2 s before a three-second wind-up lands is perfect.
- **Lock-on.** In avatar mode the lock-on key (middle click) targets the living entity nearest the crosshair within 40 degrees of it, 20 blocks and in sight (nothing locks when none qualifies), outlines it and turns Minecraft's camera toward it horizontally (the mouse keeps the vertical angle). Stellar Blade's own lock-on then holds EVE facing it, on a hidden stand-in Lua keeps at the target: she strafes around it with her lock-on locomotion, her sword drawn, and dodges with her lock-on side steps. The key again, the target's death or distance beyond 28 blocks, build mode or a lost link releases it. Stellar Blade's R3 is not pressed then.
- **HUD.** While EVE replaces the player, Stellar Blade's dotted health bar takes the hearts' place, fed by Minecraft's health (absorption as its shield row above it), and its Beta and Burst cubes sit above it, clear of the held item name and the action bar, streamed from Stellar Blade (200 points a cube, a perfect dodge's 130 Burst lighting quarters of one). Burst shows once Stellar Blade has unlocked it.
- **Health.** In survival, Minecraft's health is EVE's health, with armor, effects and mods. While her stats stream, her Stellar Blade maximum HP sets its maximum (her base 2000 is Minecraft's 20 health; 4000 after upgrades doubles it) through a temporary modifier that is never saved and leaves with the bridge, the health keeping its share of the maximum; and each hit from a mob or projectile is reduced about as Stellar Blade reduces it (an approximation: every hit is taken as one her shield and range reductions apply to), by her shield (17.5 % a shield block, 35 % at base, her shield taken as full), her melee or ranged damage reduction and her final HP damage reduction, before Minecraft's armor applies once. Stellar Blade's own HP is not changed. She flashes red when hurt. Mobs ignore creative players. While the session lasts but EVE cannot answer (a pause menu, since the bridge keeps the world running; EVE outside the patch; Stellar Blade paused or in a cutscene, detected when it withholds her input for 0.4 s), nothing hurts her. Minecraft's suffocation, fall and cramming damage do not apply while Stellar Blade owns her position. Her death keeps the link and Stellar Blade's lab: after respawning in the bridge's dimension, the player returns to the bridge anchor and EVE's controls resume once the held keys are released. A respawn in another dimension ends the bridge; run `/stellar start` again.

A hit makes EVE react in Stellar Blade (experimental: three ways of playing her stock light-hit reaction, or her strong-hit reaction from 8 damage before her reductions, take turns until one animates her, which is then kept for both; with the additive `tag` way the strong reaction looks like the light one; moving cancels it, and a try while she moves or already animates is not counted). Judgement uses the presses, not Stellar Blade's own state. A mod that applies damage before its attack animation shows can only be dodged before the damage.

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
