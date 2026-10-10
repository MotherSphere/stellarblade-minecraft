# Multiplayer

How the bridge runs on a Fabric server with other players, what changes with others online, and how to test it on one PC. Install the bridge first ([setup](setup.md)); a session runs as in [playing](playing.md#start-a-session).

The same JAR, with the matching Fabric API JAR, also loads on a Fabric server (dedicated, or a world opened to LAN). Modded clients must run the same [network protocol](../protocol/README.md#minecraft-network-protocol), else the server disconnects them with both versions named; players without the mod join as usual. Each modded player runs their own Stellar Blade on their own Linux PC and links it with `/stellar start`, as in singleplayer, with EVE's save in the Great Desert, the map with the lab's remote site. The server keeps every player's session and combat, and each client writes its own bridge directory. A modded client with its own avatar pack draws every other EVE player's EVE from her network poses with that pack: an outfit piece the pack lacks is replaced by its core mesh of the same role (body, hair, ponytail, face, drone) or left out (accessories, weapons). Players without the mod or without a pack, and an EVE whose body cannot be drawn, show as vanilla player models.

What changes with others online:

- There is no PvP, whatever the server's `pvp` setting: EVE's blade, drone and grenades spare players, anyone's pets and anything a player rides, other players' damage never reaches her, and an arrow she perfectly parries back never hurts another player.
- A perfect dodge slows Minecraft's world only while the server holds one player. Otherwise Stellar Blade's own slowdown and the cold tint play for the dodger alone.
- While EVE cannot answer (Stellar Blade paused or in a cutscene, or the link lapsed), she is spared for 2 s, then hits land unjudged. Full immunity, and the pause menu that keeps the world running, stay singleplayer only.
- Over the internet, dodge and guard presses count when they were made, up to 150 ms of round trip (see the [protocol](../protocol/README.md#minecraft-network-protocol)); hits on such a player can land up to that much later.
- Each player places at most three Smart Mines.
- Each EVE player's client uploads her pose stream, about 0.4 Mbit/s, and a client with an avatar pack receives about as much per EVE player within 64 blocks (0.08 Mbit/s beyond); a client without a pack receives none. The server caps what it relays to each player at 512 KiB/s, set in KiB with `-Dstellarbridge.relayBudget` on the server (see the [network pose](../protocol/README.md#network-pose)).

## Testing on one PC

Two Stellar Blades cannot run on one PC, so a one-PC test pairs one EVE player with a second player who plays back a recording of her:

1. **Server.** In `minecraft/`, `./gradlew runServer` starts a development server with the bridge and Fabric API in `run/server/` (or use a Fabric 1.21.1 server with Fabric API 0.116.17+1.21.1 and the bridge JAR from `minecraft/build/libs/` in its `mods` folder). Accepting Mojang's EULA in its `eula.txt` is yours to do. In `server.properties`, set `online-mode=false`, `enforce-secure-profile=false`, `server-ip=127.0.0.1` and `view-distance=6`.
2. **Instance A.** The Prism instance linked to Stellar Blade, with the default bridge directory, kept in focus for Stellar Blade's input. Add `-Dstellarbridge.netTools=true` to its JVM arguments for the network tools below. Join `127.0.0.1` and play as in singleplayer.
3. **Instance B.** In `minecraft/`, `./gradlew runClient` starts an offline development client in `run/client/` whose bridge directory is `run/client/bridge/`, with the network tools on (or use a copy of A with an offline account and the JVM arguments `-Dstellarbridge.directory=<a directory of its own> -Dstellarbridge.avatar=<the pack> -Dstellarbridge.netTools=true`). Without its own directory, B would overwrite A's `terrain.txt` and session files. B never runs `/stellar start`. It reads the avatar pack from the default place unless `-Dstellarbridge.avatar` says otherwise. Set `pauseOnLostFocus:false`, render distance 6 and 30 FPS without shaders, since memory is tight with both games open. The development client needs Minecraft's assets in Gradle's cache; Loom downloads them on the first run unless they are already there.
4. **Instance C** (optional). Vanilla Minecraft 1.21.1 without Fabric, which joins as an unmodded player.

`/stellar status` on A reports the session and its combat counts, including the payloads the server refused.

The network tools, registered only with `-Dstellarbridge.netTools=true`, stand in for a second Stellar Blade:

- `/stellaravatar echo` on A toggles the echo: A's own network poses, as they would leave for the server, come back to A as another EVE 3 blocks along +X and 1 s late, through the same decoder, clock floor, interpolation and drawing as another player's. It works in singleplayer too.
- `/stellaravatar netrecord` on A records A's network poses for 60 s (again stops it early) into `eve-netrecord.bin` in A's bridge directory. Copy that file to B's (`run/client/bridge/`).
- `/stellaravatar netplay [file]` on B sends that recording through the server as B's own EVE, in a loop, placed so her first pose stands where B stands; again stops it. A then sees B as that EVE and B sees A live.

Grouped test for the remote EVEs:

1. A, linked and anchored, turns the echo on: the echo EVE follows A's moves 1 s later, 3 blocks aside, without stutter; `/stellaravatar status` shows `Remote echo` drawn in full. Turning it off removes it.
2. A records 60 s of moves, sword attacks and dodges with `netrecord`; B plays it back with `netplay`. A sees B's EVE replay them where B stood when the playback started, with B's name tag above her and B's vanilla model hidden while B stays within 8 blocks of her (the playback does not follow B; walking B further shows B's vanilla model again); B sees A's EVE live. With B at the playback spot, A walks away from B: past 20 blocks B's EVE loses her face morphs, trail and effects; past 64 blocks she moves at 2 poses a second. `/stellaravatar status` on both shows each remote's poses a second, KB/s, clock floor, display delay and stalls, and the server's relay line.
3. Compare sword arcs at the default 20 poses a second and at `-Dstellarbridge.poseRate=30` on A.
4. B without a pack (`-Dstellarbridge.avatar=<a directory that does not exist>`, restart B): A's EVE shows as a vanilla model on B. With a copy of the pack lacking A's suit, B draws A's EVE in the core suit, and `/stellaravatar status` names the stand-in.
5. Restart B with `-Dstellarbridge.clockOffsetMs=3000` (development only: B's network clock runs 3 s ahead, both for the poses it plays back and for the arrivals it measures). A's EVE stays smooth on B and B's playback stays smooth on A; in `/stellaravatar status` the clock floor of each moves by about 3000 ms and the display delay does not.
6. Death and respawn, postponed from the first multiplayer test: A dies and respawns at the anchor without a relink while B watches; B sees A's EVE again as soon as her poses flow after the respawn, and a vanilla model only while they do not (3 s without a pose). A disconnects, and Stellar Blade returns EVE within 5 s; A's EVE leaves B's view with A's player.
