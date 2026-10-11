//! English, the canonical locale. Keep the keys in step with `fr.rs`: a test fails otherwise.

pub(super) const STRINGS: &[(&str, &str)] = &[
    // Chrome
    ("nav_steps", "Steps"),
    ("screen_welcome", "Welcome"),
    ("screen_detect", "Detect"),
    ("screen_verify", "Verify the game"),
    ("screen_target", "Minecraft"),
    ("screen_consent", "Review"),
    ("screen_working", "Installing"),
    ("screen_done", "Done"),
    ("dry_run_badge", "Dry run"),
    ("dry_run_finish", "Show the result (dry run)"),
    ("dry_run_nothing_written", "Dry run: nothing was written."),
    ("language", "Language"),
    (
        "language_desc",
        "The installer's language. Applies at once.",
    ),
    (
        "prefs_not_saved",
        "The preferences could not be saved: {error}",
    ),
    ("copied", "Copied to the clipboard"),
    (
        "still_writing",
        "Files are being written: the window closes once this step is done.",
    ),
    (
        "no_payload",
        "This build carries no bridge pieces: it can detect and uninstall, not install.",
    ),
    ("no_payload_short", "No bridge pieces"),
    // Buttons
    ("start", "Start"),
    ("back", "Back"),
    ("continue", "Continue"),
    ("cancel", "Cancel"),
    ("rescan", "Detect again"),
    ("copy", "Copy"),
    ("back_to_status", "Back to the status page"),
    ("action_install", "Install"),
    ("action_update", "Update"),
    ("action_up_to_date", "Up to date"),
    ("action_repair", "Repair"),
    ("action_rollback", "Roll back"),
    ("action_uninstall", "Uninstall"),
    // Welcome
    ("welcome_title", "Stellar Bridge"),
    (
        "welcome_body",
        "This installer sets up the Stellar Blade x Minecraft bridge: the UE4SS Lua mod and the input adapter on the Stellar Blade side, the bridge mod and Fabric API on the Minecraft side. It checks everything first and asks before it writes anything.",
    ),
    ("role_title", "On this PC"),
    ("role_eve", "EVE player"),
    (
        "role_eve_hint",
        "Stellar Blade and Minecraft both run on this PC.",
    ),
    ("role_viewer", "Viewer"),
    (
        "role_viewer_hint",
        "Minecraft only: join a friend's server and see the EVE players. Installs the bridge mod and Fabric API.",
    ),
    ("welcome_changes_title", "What changes"),
    (
        "welcome_changes",
        "A folder in UE4SS's Mods, xinput1_3.dll beside the game executable, the bridge mod and Fabric API in one Minecraft instance, and a private bridge folder. Every file is recorded so it can be updated, repaired or removed later.",
    ),
    ("never_touched_title", "Never touched"),
    (
        "never_touched",
        "Your saves (a copy is made once, before the first install), UE4SS, dwmapi.dll, dinput8.dll, other mods, Steam settings and launch options, and the registry (the Proton prefix's on Linux, Windows' own, which it only reads to find Steam). The installer never starts a game or a launcher.",
    ),
    // Detect
    ("detect_title", "What is on this PC"),
    (
        "detecting",
        "Looking for Steam, Stellar Blade, UE4SS and Minecraft…",
    ),
    ("row_steam", "Steam"),
    ("row_steam_missing", "Not found"),
    (
        "row_steam_found",
        "{count} installation(s), {libraries} library folder(s)",
    ),
    ("row_game", "Stellar Blade"),
    ("row_game_build", "Steam build {build}"),
    ("row_proton", "Proton"),
    ("row_proton_default", "Steam's default tool"),
    (
        "row_proton_untested",
        "{name}: untested, the bridge was tested on {tested}",
    ),
    ("row_prefix", "Proton prefix"),
    (
        "row_prefix_missing",
        "Not found: start Stellar Blade once through Steam first",
    ),
    ("row_ue4ss", "UE4SS"),
    ("row_ue4ss_known", "A build known to work"),
    ("row_ue4ss_untested", "An untested build: it may work"),
    ("row_override", "xinput1_3 override"),
    ("row_override_registry", "Set in the Proton prefix"),
    ("row_override_launch", "Set in the launch options"),
    (
        "row_override_missing",
        "Not found: the launch option to copy is shown at the end",
    ),
    ("row_minecraft", "Minecraft"),
    (
        "row_minecraft_none",
        "No Prism Launcher found: create an instance, load a MultiMC folder or pick a mods folder later",
    ),
    (
        "row_minecraft_found",
        "{launchers}: {count} instance(s), {compatible} ready for the bridge",
    ),
    ("row_install", "Bridge install"),
    ("row_install_none", "Nothing installed"),
    ("row_pack", "Avatar pack"),
    ("row_record", "Installer record"),
    ("row_payload", "Bridge pieces"),
    ("row_exe", "Game executable"),
    ("piece_jar", "Bridge mod"),
    ("piece_lua", "Lua mod"),
    ("piece_adapter", "Input adapter"),
    ("piece_absent", "Not installed"),
    ("piece_current", "Installed, this release"),
    (
        "piece_other_release",
        "Installed, another release: Update replaces it",
    ),
    (
        "pack_missing",
        "Missing: the avatar view needs it, the capture views work without it",
    ),
    (
        "pack_untracked",
        "Present, built before game files were tracked",
    ),
    ("pack_unreadable", "Present, its pack.txt cannot be read"),
    (
        "pack_stale",
        "Present, but {count} game file(s) changed since it was built: rebuild it",
    ),
    (
        "pack_stale_short",
        "Avatar pack: built from other game files, rebuild it",
    ),
    ("pack_fresh", "Present and up to date"),
    (
        "state_interrupted",
        "A run of {release} was interrupted: Install again to finish it, or Roll back",
    ),
    ("state_installed", "Installed {release}"),
    ("state_removed", "Uninstalled (last release {release})"),
    (
        "blocker_no_game",
        "Stellar Blade was not found in any Steam library.",
    ),
    (
        "blocker_updating",
        "Steam is updating Stellar Blade: wait until it finishes.",
    ),
    (
        "blocker_build",
        "Stellar Blade was updated: wait for a bridge release that supports this game version.",
    ),
    (
        "blocker_no_ue4ss",
        "UE4SS was not found: install it first (the installer never installs it).",
    ),
    (
        "blocker_ue4ss_mods",
        "UE4SS's Mods folder was moved (ModsFolderPath): this release supports only the default folder.",
    ),
    (
        "blocker_game_files",
        "A file where the bridge goes in the game folder is not the bridge's, or was changed (see the rows above). UE4SS 2.x, for example, also uses xinput1_3.dll. Remove or move that file; if it is the bridge's own, use Repair.",
    ),
    ("viewer_still_possible", "The viewer role still works."),
    ("maintenance_title", "Installed pieces"),
    // Verify
    ("verify_title", "Verify the game"),
    (
        "verify_body",
        "The adapter works with one exact Stellar Blade executable. Its fingerprint is checked now: this reads about 360 MB.",
    ),
    ("exe_ok", "The supported build"),
    (
        "exe_mismatch",
        "Stellar Blade was updated: wait for a bridge release. The Stellar Blade side stays off; the viewer role is still available.",
    ),
    (
        "exe_not_verified",
        "The game executable has not been verified.",
    ),
    ("continue_as_viewer", "Continue as a viewer"),
    // Target
    ("target_title", "Where Minecraft gets the bridge"),
    (
        "target_body",
        "A dedicated instance keeps your other worlds and mods apart. Prism downloads Minecraft and Fabric Loader at its first start.",
    ),
    ("pick_target", "Choose where to install the Minecraft side."),
    ("choice_current", "The instance used last time"),
    ("choice_new", "New instance \"{name}\" in {launcher}"),
    (
        "choice_flatpak_hint",
        "The bridge folders go inside the Flatpak sandbox and are passed to the instance.",
    ),
    ("choice_manual", "A mods folder"),
    (
        "choice_manual_hint",
        "Any other launcher. Its Minecraft and Fabric Loader versions cannot be checked: use Minecraft 1.21.1 with Fabric Loader 0.19.5 or newer.",
    ),
    ("choice_manual_placeholder", "/path/to/the/instance/mods"),
    (
        "target_others",
        "{count} other instance(s) are not Minecraft 1.21.1 with Fabric Loader 0.19.5 or newer, or belong to a Flatpak Prism (use its dedicated instance).",
    ),
    (
        "mods_missing",
        "This instance has no mods folder yet: start it once in its launcher, or pick another. ({path})",
    ),
    (
        "manual_flatpak",
        "This mods folder belongs to a Flatpak launcher, which cannot see the bridge folder. Choose the new dedicated instance instead: it carries the Java arguments that point Minecraft to the right folder.",
    ),
    ("multimc_title", "MultiMC"),
    ("multimc_placeholder", "/path/to/MultiMC"),
    ("multimc_load", "Load its instances"),
    ("multimc_loaded", "{count} MultiMC instance(s) loaded"),
    (
        "multimc_not_found",
        "No MultiMC data folder there (it holds multimc.cfg or instances/).",
    ),
    // Consent
    ("consent_title", "Review before installing"),
    (
        "consent_nothing",
        "This release is already installed here: nothing will change.",
    ),
    ("consent_writes", "Will be written"),
    ("consent_replaced", "Replaced (kept first, for Roll back)"),
    ("consent_backups", "Backups made first"),
    (
        "consent_eula",
        "Modding Stellar Blade is at your own risk under its licence agreement:",
    ),
    (
        "consent_check",
        "I have closed Stellar Blade and Minecraft, and I agree to these changes.",
    ),
    // Games
    ("games_title", "Close the games"),
    (
        "games_body",
        "Nothing is written while they run. This list updates every second.",
    ),
    ("games_running", "Running"),
    ("games_all_closed", "Everything is closed"),
    ("running_sb", "Stellar Blade"),
    ("running_minecraft", "Minecraft"),
    ("running_launcher", "The Minecraft launcher"),
    // Confirm
    ("confirm_title", "{action}?"),
    (
        "confirm_repair",
        "These installed files changed or went missing. They are put back exactly as recorded:",
    ),
    (
        "confirm_rollback",
        "The current pieces are replaced by the kept ones, through the same checks as an install:",
    ),
    ("confirm_uninstall", "These installed files are removed:"),
    (
        "uninstall_keeps",
        "Kept: the Minecraft instance and its worlds, the avatar pack, the save backups and the installer record.",
    ),
    (
        "remove_fabric",
        "Also remove Fabric API (only if no other mod needs it)",
    ),
    ("left_in_place", "Left in place: {what}"),
    ("no_current_target", "No installed target was found."),
    (
        "no_previous",
        "There is no previous release to roll back to.",
    ),
    ("rollback_to", "Roll back to {release}"),
    // Working
    (
        "working_body",
        "Each step is checked before the next one. If one fails, what was written is removed and the previous state is put back.",
    ),
    (
        "step_checks",
        "Checks: games closed, game version, ownership",
    ),
    ("step_save_backup", "Copy the Stellar Blade saves"),
    (
        "step_keep_previous",
        "Keep the current pieces for Roll back",
    ),
    ("step_remove", "Remove the replaced pieces"),
    ("step_minecraft", "Bridge mod and Fabric API"),
    ("step_lua", "Bridge folder and Lua mod"),
    ("step_adapter", "Input adapter"),
    ("step_repair", "Put back the changed files"),
    ("step_verify", "Verify"),
    ("step_restore", "Put the previous state back"),
    ("step_record", "Record the install"),
    ("log_instance_created", "Created the instance {path}"),
    // Done
    ("done_ok", "{action}: done"),
    ("done_failed", "{action}: failed"),
    ("done_dry_run", "{action}: dry run"),
    ("done_release", "Bridge release {release}"),
    (
        "done_nothing_to_repair",
        "Nothing to repair: every installed file is as recorded.",
    ),
    ("done_removed_all", "Every installed piece was removed."),
    (
        "done_left_in_place",
        "Removed what this installer owns. Left in place (not installed by it, or changed since):",
    ),
    ("launch_option_title", "One step left in Steam"),
    (
        "launch_option_body",
        "Proton must load the adapter. In Steam, open Stellar Blade's Properties and set these launch options (your current options are kept):",
    ),
    ("next_title", "Next"),
    (
        "next_steps",
        "Start Minecraft from the instance, open your test world, stand on a full block and run /stellar start. With EVE loaded in Stellar Blade, press F5 then F8 there (or run /stellar import then /stellar enter), then /stellaravatar play in Minecraft.",
    ),
    ("log_at", "Log: {path}"),
    // Refusals and failures of the core, by `Kind` key; `{path}` is what they are about
    ("err_cause", "{error}: {cause}"),
    ("piece_refusal", "{piece}: {refusal}"),
    (
        "err_unexpected",
        "Unexpected error: the log has the details.",
    ),
    (
        "err_foreign_jar",
        "Refusing a bridge JAR this installer did not install: {path}",
    ),
    ("err_modified_jar", "Refusing modified bridge files: {path}"),
    ("err_foreign_lua", "Refusing an unowned directory: {path}"),
    (
        "err_modified_lua",
        "Refusing modified or extra content: {path}",
    ),
    (
        "err_foreign_adapter",
        "Refusing existing input adapter content: {path}",
    ),
    (
        "err_modified_adapter",
        "Refusing modified input adapter content: {path}",
    ),
    (
        "err_unknown_manifest",
        "The ownership manifest is not recognized: {path}",
    ),
    ("err_not_directory", "Expected a real directory: {path}"),
    (
        "err_real_file",
        "Expected a real file without symlinks: {path}",
    ),
    (
        "err_real_dir",
        "Expected a real directory without symlinks: {path}",
    ),
    ("err_symlink", "Refusing a symbolic link: {path}"),
    ("err_not_regular", "Refusing a non-regular file: {path}"),
    ("err_non_utf8", "Refusing a non-UTF-8 path: {path}"),
    (
        "err_changed_lua_folders",
        "Refusing changed Lua mod folders: {path}",
    ),
    (
        "err_unlisted_lua_file",
        "Refusing an unlisted Lua mod file: {path}",
    ),
    ("err_replace", "Refusing to replace {path}"),
    (
        "err_nothing_to_remove",
        "Nothing of the bridge to remove in {path}",
    ),
    (
        "err_remove_first",
        "Remove the installed bridge JAR first: {path}",
    ),
    (
        "err_fabric_missing",
        "Fabric API is missing from {path} and not carried",
    ),
    ("err_expected_exe", "Expected SB-Win64-Shipping.exe: {path}"),
    (
        "err_unsupported_exe",
        "Unsupported Stellar Blade executable: wait for a bridge release that supports this game version ({path})",
    ),
    (
        "err_bridge_control",
        "The bridge path cannot contain line breaks: {path}",
    ),
    (
        "err_bridge_not_absolute",
        "The bridge directory must be absolute: {path}",
    ),
    (
        "err_bridge_mode",
        "The bridge directory must belong to this user with mode 0700: {path}",
    ),
    (
        "err_bridge_volume",
        "The bridge directory must be on a local NTFS disk, never a network share: {path}",
    ),
    (
        "err_bridge_ascii",
        "Stellar Blade's Lua opens only ASCII paths, and this folder has other characters and no short (8.3) name: {path}",
    ),
    ("err_no_ancestor", "No existing folder above {path}"),
    (
        "err_process_list",
        "Cannot list processes to check that the games are closed",
    ),
    (
        "err_server_running",
        "Stop the Minecraft server running in {path} first",
    ),
    (
        "err_server_folder",
        "Neither an empty folder nor a Fabric server for Minecraft 1.21.1: {path}",
    ),
    (
        "err_server_loader",
        "This server's Fabric Loader is older than 0.19.5, which the bridge needs: {path}",
    ),
    (
        "err_java_version",
        "The server needs Java 21 or newer as `java` on the PATH (found: {path}). Install it, then try again.",
    ),
    (
        "err_download",
        "The download from {path} failed. Check the connection, then try again.",
    ),
    (
        "err_digest",
        "{path} is not the file this release pins (its size or SHA-256 differs): it was not used.",
    ),
    (
        "err_oodle_entry",
        "{path} does not hold the Oodle library where the OodleUE release keeps it.",
    ),
    (
        "err_usmap_invalid",
        "{path} is not a usmap UE4SS writes (wrong header). Delete it, then press Ctrl+Numpad6 in Stellar Blade with a save loaded.",
    ),
    (
        "err_no_usmap",
        "No usmap for this Stellar Blade build yet. Start Stellar Blade with the bridge installed, load a save until EVE is in the world, then quit; or press Ctrl+Numpad6 in game.",
    ),
    (
        "err_usmap_rejected",
        "EveExtract could not read the game's item table with the usmap of build {path}: it does not describe this game build. Delete the .usmap in UE4SS's folder, start Stellar Blade, load a save, press Ctrl+Numpad6, quit, then build again.",
    ),
    (
        "err_no_sidecar",
        "This installer was built without EveExtract's pins: use a release build to make the avatar pack.",
    ),
    ("err_close_first", "Close {path} first"),
    (
        "err_save_backup_differs",
        "The save backup differs from the saves: {path}",
    ),
    ("err_protected_changed", "Protected files changed: {path}"),
    (
        "err_saves_changed",
        "The Stellar Blade saves changed: {path}",
    ),
    (
        "err_mods_missing",
        "The Minecraft mods folder does not exist: {path}",
    ),
    (
        "err_left_behind",
        "The bridge is still installed in {path}: uninstall it there first, then install here",
    ),
    ("err_no_jar", "{path} has no bridge JAR to install"),
    (
        "err_no_game_side",
        "{path} has no Stellar Blade side to install",
    ),
    (
        "err_mismatch_after_write",
        "{path} does not match the release after writing",
    ),
    (
        "err_install_restored",
        "Installation failed; the previous state was restored",
    ),
    (
        "err_install_not_restored",
        "Installation failed and the previous state could not be restored; the replaced files are kept in {path}",
    ),
    (
        "err_install_not_removed",
        "Installation failed and the files it wrote could not all be removed; the log has the details",
    ),
    (
        "err_no_previous",
        "There is no previous set to roll back to",
    ),
    (
        "err_previous_changed",
        "The kept previous set changed since it was kept: {path}",
    ),
    ("err_still_modified", "{path} is still not as installed"),
    (
        "err_run_update",
        "{path} differs from what this installer carries: run the update instead",
    ),
    ("err_invalid_release", "Invalid release name: {path}"),
    ("err_cannot_keep", "Cannot keep {path}"),
    (
        "err_java_argument",
        "Unsupported Java argument for the instance (no spaces or quotes): {path}",
    ),
    (
        "err_instance_unreadable",
        "The new instance cannot be read back: {path}",
    ),
    ("err_pick_mods", "Pick the instance's mods folder: {path}"),
    // Server folder
    ("screen_server", "Server folder"),
    ("role_server", "Server operator"),
    (
        "role_server_hint",
        "A Fabric server for Minecraft 1.21.1: the bridge JAR and Fabric API in its mods folder, in an existing server folder or a new one.",
    ),
    ("action_server", "Install on the server"),
    ("server_title", "Server folder"),
    (
        "server_body",
        "Type the folder of a Fabric server for Minecraft 1.21.1, or an empty or new folder to make one with Fabric's server launcher. Then check it.",
    ),
    (
        "server_placeholder",
        "Server folder, for example /srv/minecraft or C:\\Servers\\Minecraft",
    ),
    ("server_check", "Check"),
    ("server_pick", "Type the server folder first."),
    (
        "server_new",
        "New server: {path} gets Fabric's server launcher, start.sh, start.bat and a mods folder",
    ),
    ("server_ours", "Server made by this installer: {path}"),
    (
        "server_existing",
        "Fabric server for Minecraft 1.21.1, Fabric Loader {loader}: {path}",
    ),
    (
        "server_existing_unknown",
        "Fabric server for Minecraft 1.21.1 (Fabric Loader version not shown yet): {path}",
    ),
    ("server_bridge_absent", "Bridge: not installed yet"),
    (
        "server_bridge_owned",
        "Bridge: installed by this installer (updated if this release differs)",
    ),
    ("server_java", "Java {version} runs the server"),
    ("server_eula_true", "Mojang's EULA: accepted in eula.txt"),
    (
        "server_eula_false",
        "Mojang's EULA: eula.txt says false. The server starts once you set eula=true there, after reading it.",
    ),
    ("server_eula_missing", "Mojang's EULA: no eula.txt yet"),
    (
        "server_offline",
        "online-mode=false: anyone can join under any name. The installer leaves server.properties as it is.",
    ),
    (
        "server_eula_body",
        "The Minecraft server starts only once Mojang's EULA is accepted in eula.txt. Read it; tick the box only if you accept it for this server, or edit eula.txt yourself later.",
    ),
    (
        "server_eula_check",
        "I have read Mojang's Minecraft EULA and accept it for this server",
    ),
    (
        "server_never_touched",
        "server.properties (online-mode stays as it is), the worlds, other mods and an existing eula.txt. Nothing is downloaded and the server is never started.",
    ),
    (
        "server_done_installed",
        "Bridge JAR and Fabric API in {path}",
    ),
    (
        "server_done_current",
        "This release is already installed: nothing changed.",
    ),
    ("server_done_created", "New server folder: {path}"),
    (
        "server_done_start",
        "Start the server with start.sh (Linux) or start.bat (Windows), or as you usually do.",
    ),
    (
        "server_done_eula_todo",
        "Before the first start, read Mojang's EULA ({url}) and set eula=true in eula.txt in {path} if you accept it.",
    ),
    (
        "server_done_removed",
        "Bridge JAR removed. Fabric API, the launcher, the start scripts, eula.txt and the worlds stay.",
    ),
];
