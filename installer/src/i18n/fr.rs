//! Français. Mêmes clés que `en.rs`, vouvoiement.

pub(super) const STRINGS: &[(&str, &str)] = &[
    // Chrome
    ("nav_steps", "Étapes"),
    ("screen_welcome", "Bienvenue"),
    ("screen_detect", "Détection"),
    ("screen_verify", "Vérification du jeu"),
    ("screen_target", "Minecraft"),
    ("screen_consent", "Récapitulatif"),
    ("screen_working", "Installation"),
    ("screen_done", "Terminé"),
    ("dry_run_badge", "Simulation"),
    ("dry_run_finish", "Voir le résultat (simulation)"),
    (
        "dry_run_nothing_written",
        "Simulation : rien n'a été écrit.",
    ),
    ("language", "Langue"),
    (
        "language_desc",
        "La langue de l'installateur. S'applique immédiatement.",
    ),
    (
        "prefs_not_saved",
        "Les préférences n'ont pas pu être enregistrées : {error}",
    ),
    ("copied", "Copié dans le presse-papiers"),
    (
        "still_writing",
        "Des fichiers sont en cours d'écriture : la fenêtre se fermera à la fin de cette étape.",
    ),
    (
        "no_payload",
        "Cette version ne contient pas les éléments du pont : elle peut détecter et désinstaller, pas installer.",
    ),
    ("no_payload_short", "Sans éléments du pont"),
    // Buttons
    ("start", "Commencer"),
    ("back", "Retour"),
    ("continue", "Continuer"),
    ("cancel", "Annuler"),
    ("rescan", "Détecter à nouveau"),
    ("copy", "Copier"),
    ("back_to_status", "Retour à l'état de l'installation"),
    ("action_install", "Installer"),
    ("action_update", "Mettre à jour"),
    ("action_up_to_date", "À jour"),
    ("action_repair", "Réparer"),
    ("action_rollback", "Revenir en arrière"),
    ("action_uninstall", "Désinstaller"),
    // Welcome
    ("welcome_title", "Stellar Bridge"),
    (
        "welcome_body",
        "Cet installateur met en place le pont Stellar Blade x Minecraft : le mod Lua UE4SS et l'adaptateur d'entrées côté Stellar Blade, le mod du pont et Fabric API côté Minecraft. Il vérifie tout d'abord et vous demande votre accord avant d'écrire quoi que ce soit.",
    ),
    ("role_title", "Sur ce PC"),
    ("role_eve", "Joueur d'EVE"),
    (
        "role_eve_hint",
        "Stellar Blade et Minecraft tournent tous les deux sur ce PC.",
    ),
    ("role_viewer", "Spectateur"),
    (
        "role_viewer_hint",
        "Minecraft seul : rejoignez le serveur d'un ami et voyez les joueurs d'EVE. Installe le mod du pont et Fabric API.",
    ),
    ("welcome_changes_title", "Ce qui change"),
    (
        "welcome_changes",
        "Un dossier dans les Mods d'UE4SS, xinput1_3.dll à côté de l'exécutable du jeu, le mod du pont et Fabric API dans une instance Minecraft, et un dossier privé pour le pont. Chaque fichier est enregistré pour pouvoir être mis à jour, réparé ou retiré plus tard.",
    ),
    ("never_touched_title", "Jamais modifié"),
    (
        "never_touched",
        "Vos sauvegardes (une copie est faite une fois, avant la première installation), UE4SS, dwmapi.dll, dinput8.dll, les autres mods, les réglages et options de lancement Steam, et le registre (celui du préfixe Proton sous Linux, celui de Windows, qu'il lit seulement pour trouver Steam). L'installateur ne lance jamais un jeu ni un launcher.",
    ),
    // Detect
    ("detect_title", "Ce qui se trouve sur ce PC"),
    (
        "detecting",
        "Recherche de Steam, Stellar Blade, UE4SS et Minecraft…",
    ),
    ("row_steam", "Steam"),
    ("row_steam_missing", "Introuvable"),
    (
        "row_steam_found",
        "{count} installation(s), {libraries} bibliothèque(s)",
    ),
    ("row_game", "Stellar Blade"),
    ("row_game_build", "Build Steam {build}"),
    ("row_proton", "Proton"),
    ("row_proton_default", "L'outil par défaut de Steam"),
    (
        "row_proton_untested",
        "{name} : non testé, le pont a été testé avec {tested}",
    ),
    ("row_prefix", "Préfixe Proton"),
    (
        "row_prefix_missing",
        "Introuvable : lancez d'abord Stellar Blade une fois depuis Steam",
    ),
    ("row_ue4ss", "UE4SS"),
    ("row_ue4ss_known", "Une version connue pour fonctionner"),
    (
        "row_ue4ss_untested",
        "Une version non testée : elle peut fonctionner",
    ),
    ("row_override", "Surcharge xinput1_3"),
    ("row_override_registry", "Définie dans le préfixe Proton"),
    (
        "row_override_launch",
        "Définie dans les options de lancement",
    ),
    (
        "row_override_missing",
        "Introuvable : l'option de lancement à copier est affichée à la fin",
    ),
    ("row_minecraft", "Minecraft"),
    (
        "row_minecraft_none",
        "Aucun Prism Launcher trouvé : créez une instance, chargez un dossier MultiMC ou choisissez un dossier mods plus tard",
    ),
    (
        "row_minecraft_found",
        "{launchers} : {count} instance(s), {compatible} prête(s) pour le pont",
    ),
    ("row_install", "Installation du pont"),
    ("row_install_none", "Rien n'est installé"),
    ("row_pack", "Pack d'avatar"),
    ("row_record", "Historique de l'installateur"),
    ("row_payload", "Éléments du pont"),
    ("row_exe", "Exécutable du jeu"),
    ("piece_jar", "Mod du pont"),
    ("piece_lua", "Mod Lua"),
    ("piece_adapter", "Adaptateur d'entrées"),
    ("piece_absent", "Non installé"),
    ("piece_current", "Installé, cette version"),
    (
        "piece_other_release",
        "Installé, une autre version : Mettre à jour la remplace",
    ),
    (
        "pack_missing",
        "Absent : la vue avatar en a besoin, les vues capture fonctionnent sans",
    ),
    (
        "pack_untracked",
        "Présent, construit avant le suivi des fichiers du jeu",
    ),
    ("pack_unreadable", "Présent, son pack.txt est illisible"),
    (
        "pack_stale",
        "Présent, mais {count} fichier(s) du jeu ont changé depuis sa construction : reconstruisez-le",
    ),
    (
        "pack_stale_short",
        "Pack d'avatar : construit à partir d'autres fichiers du jeu, reconstruisez-le",
    ),
    ("pack_fresh", "Présent et à jour"),
    (
        "state_interrupted",
        "Une opération sur {release} a été interrompue : installez à nouveau pour la terminer, ou revenez en arrière",
    ),
    ("state_installed", "{release} installé"),
    ("state_removed", "Désinstallé (dernière version {release})"),
    (
        "blocker_no_game",
        "Stellar Blade est introuvable dans les bibliothèques Steam.",
    ),
    (
        "blocker_updating",
        "Steam met Stellar Blade à jour : attendez la fin.",
    ),
    (
        "blocker_build",
        "Stellar Blade a été mis à jour : attendez une version du pont qui prend en charge cette version du jeu.",
    ),
    (
        "blocker_no_ue4ss",
        "UE4SS est introuvable : installez-le d'abord (l'installateur ne l'installe jamais).",
    ),
    (
        "blocker_ue4ss_mods",
        "Le dossier Mods d'UE4SS a été déplacé (ModsFolderPath) : cette version ne prend en charge que le dossier par défaut.",
    ),
    (
        "blocker_game_files",
        "Un fichier à l'emplacement du pont dans le dossier du jeu n'est pas celui du pont, ou a été modifié (voir les lignes ci-dessus). UE4SS 2.x, par exemple, utilise aussi xinput1_3.dll. Supprimez ou déplacez ce fichier ; s'il s'agit de celui du pont, utilisez Réparer.",
    ),
    (
        "viewer_still_possible",
        "Le rôle de spectateur reste possible.",
    ),
    ("maintenance_title", "Éléments installés"),
    // Verify
    ("verify_title", "Vérification du jeu"),
    (
        "verify_body",
        "L'adaptateur fonctionne avec un exécutable précis de Stellar Blade. Son empreinte est vérifiée maintenant : environ 360 Mo sont lus.",
    ),
    ("exe_ok", "La version prise en charge"),
    (
        "exe_mismatch",
        "Stellar Blade a été mis à jour : attendez une nouvelle version du pont. Le côté Stellar Blade reste désactivé ; le rôle de spectateur reste disponible.",
    ),
    (
        "exe_not_verified",
        "L'exécutable du jeu n'a pas été vérifié.",
    ),
    ("continue_as_viewer", "Continuer en spectateur"),
    // Target
    ("target_title", "Où Minecraft reçoit le pont"),
    (
        "target_body",
        "Une instance dédiée garde vos autres mondes et mods à part. Prism télécharge Minecraft et Fabric Loader à son premier lancement.",
    ),
    ("pick_target", "Choisissez où installer le côté Minecraft."),
    ("choice_current", "L'instance utilisée la dernière fois"),
    ("choice_new", "Nouvelle instance « {name} » dans {launcher}"),
    (
        "choice_flatpak_hint",
        "Les dossiers du pont vont dans le bac à sable Flatpak et sont transmis à l'instance.",
    ),
    ("choice_manual", "Un dossier mods"),
    (
        "choice_manual_hint",
        "Tout autre launcher. Ses versions de Minecraft et de Fabric Loader ne peuvent pas être vérifiées : utilisez Minecraft 1.21.1 avec Fabric Loader 0.19.5 ou plus récent.",
    ),
    ("choice_manual_placeholder", "/chemin/vers/l/instance/mods"),
    (
        "target_others",
        "{count} autre(s) instance(s) ne sont pas en Minecraft 1.21.1 avec Fabric Loader 0.19.5 ou plus récent, ou appartiennent à un Prism Flatpak (utilisez son instance dédiée).",
    ),
    (
        "mods_missing",
        "Cette instance n'a pas encore de dossier mods : lancez-la une fois dans son launcher, ou choisissez-en une autre. ({path})",
    ),
    (
        "manual_flatpak",
        "Ce dossier mods appartient à un lanceur Flatpak, qui ne voit pas le dossier du pont. Choisissez plutôt la nouvelle instance dédiée : elle porte les arguments Java qui indiquent à Minecraft le bon dossier.",
    ),
    ("multimc_title", "MultiMC"),
    ("multimc_placeholder", "/chemin/vers/MultiMC"),
    ("multimc_load", "Charger ses instances"),
    ("multimc_loaded", "{count} instance(s) MultiMC chargée(s)"),
    (
        "multimc_not_found",
        "Aucun dossier de données MultiMC ici (il contient multimc.cfg ou instances/).",
    ),
    // Consent
    ("consent_title", "Vérifiez avant d'installer"),
    (
        "consent_nothing",
        "Cette version est déjà installée ici : rien ne changera.",
    ),
    ("consent_writes", "Sera écrit"),
    (
        "consent_replaced",
        "Remplacé (conservé d'abord, pour revenir en arrière)",
    ),
    ("consent_backups", "Copies faites d'abord"),
    (
        "consent_eula",
        "Modifier Stellar Blade se fait à vos risques selon son contrat de licence :",
    ),
    (
        "consent_check",
        "J'ai fermé Stellar Blade et Minecraft, et j'accepte ces modifications.",
    ),
    // Games
    ("games_title", "Fermez les jeux"),
    (
        "games_body",
        "Rien n'est écrit tant qu'ils tournent. Cette liste se met à jour chaque seconde.",
    ),
    ("games_running", "En cours d'exécution"),
    ("games_all_closed", "Tout est fermé"),
    ("running_sb", "Stellar Blade"),
    ("running_minecraft", "Minecraft"),
    ("running_launcher", "Le launcher Minecraft"),
    // Confirm
    ("confirm_title", "{action} ?"),
    (
        "confirm_repair",
        "Ces fichiers installés ont changé ou ont disparu. Ils sont remis exactement comme enregistrés :",
    ),
    (
        "confirm_rollback",
        "Les éléments actuels sont remplacés par ceux conservés, avec les mêmes vérifications qu'une installation :",
    ),
    ("confirm_uninstall", "Ces fichiers installés sont retirés :"),
    (
        "uninstall_keeps",
        "Conservés : l'instance Minecraft et ses mondes, le pack d'avatar, les copies des sauvegardes et l'historique de l'installateur.",
    ),
    (
        "remove_fabric",
        "Retirer aussi Fabric API (seulement si aucun autre mod n'en a besoin)",
    ),
    ("left_in_place", "Laissé en place : {what}"),
    ("no_current_target", "Aucune installation n'a été trouvée."),
    (
        "no_previous",
        "Aucune version précédente vers laquelle revenir.",
    ),
    ("rollback_to", "Revenir à {release}"),
    // Working
    (
        "working_body",
        "Chaque étape est vérifiée avant la suivante. Si l'une échoue, ce qui a été écrit est retiré et l'état précédent est remis.",
    ),
    (
        "step_checks",
        "Vérifications : jeux fermés, version du jeu, propriété des fichiers",
    ),
    ("step_save_backup", "Copie des sauvegardes de Stellar Blade"),
    (
        "step_keep_previous",
        "Conservation des éléments actuels pour revenir en arrière",
    ),
    ("step_remove", "Retrait des éléments remplacés"),
    ("step_minecraft", "Mod du pont et Fabric API"),
    ("step_lua", "Dossier du pont et mod Lua"),
    ("step_adapter", "Adaptateur d'entrées"),
    ("step_repair", "Remise en place des fichiers modifiés"),
    ("step_verify", "Vérification"),
    ("step_restore", "Remise en place de l'état précédent"),
    ("step_record", "Enregistrement de l'installation"),
    ("log_instance_created", "Instance créée : {path}"),
    // Done
    ("done_ok", "{action} : terminé"),
    ("done_failed", "{action} : échec"),
    ("done_dry_run", "{action} : simulation"),
    ("done_release", "Version du pont {release}"),
    (
        "done_nothing_to_repair",
        "Rien à réparer : chaque fichier installé est conforme à l'enregistrement.",
    ),
    (
        "done_removed_all",
        "Tous les éléments installés ont été retirés.",
    ),
    (
        "done_left_in_place",
        "Ce qui appartient à cet installateur a été retiré. Laissé en place (non installé par lui, ou modifié depuis) :",
    ),
    ("launch_option_title", "Une dernière étape dans Steam"),
    (
        "launch_option_body",
        "Proton doit charger l'adaptateur. Dans Steam, ouvrez les propriétés de Stellar Blade et définissez ces options de lancement (vos options actuelles sont conservées) :",
    ),
    ("next_title", "Ensuite"),
    (
        "next_steps",
        "Lancez Minecraft depuis l'instance, ouvrez votre monde de test, placez-vous sur un bloc plein et tapez /stellar start. Avec EVE chargée dans Stellar Blade, appuyez sur F5 puis F8 (ou tapez /stellar import puis /stellar enter), puis /stellaravatar play dans Minecraft.",
    ),
    ("log_at", "Journal : {path}"),
    // Refusals and failures of the core, by `Kind` key; `{path}` is what they are about
    ("err_cause", "{error} : {cause}"),
    ("piece_refusal", "{piece} : {refusal}"),
    (
        "err_unexpected",
        "Erreur inattendue : le journal contient les détails.",
    ),
    (
        "err_foreign_jar",
        "Refusé : un JAR du pont que cet installateur n'a pas installé : {path}",
    ),
    (
        "err_modified_jar",
        "Refusé : des fichiers du pont modifiés : {path}",
    ),
    (
        "err_foreign_lua",
        "Refusé : un dossier qui n'appartient pas au pont : {path}",
    ),
    (
        "err_modified_lua",
        "Refusé : un contenu modifié ou ajouté : {path}",
    ),
    (
        "err_foreign_adapter",
        "Refusé : un adaptateur d'entrées déjà présent : {path}",
    ),
    (
        "err_modified_adapter",
        "Refusé : un adaptateur d'entrées modifié : {path}",
    ),
    (
        "err_unknown_manifest",
        "Le manifeste d'appartenance n'est pas reconnu : {path}",
    ),
    ("err_not_directory", "Un vrai dossier est attendu : {path}"),
    (
        "err_real_file",
        "Un vrai fichier, sans lien symbolique, est attendu : {path}",
    ),
    (
        "err_real_dir",
        "Un vrai dossier, sans lien symbolique, est attendu : {path}",
    ),
    ("err_symlink", "Refusé : un lien symbolique : {path}"),
    ("err_not_regular", "Refusé : un fichier spécial : {path}"),
    (
        "err_non_utf8",
        "Refusé : un chemin qui n'est pas en UTF-8 : {path}",
    ),
    (
        "err_changed_lua_folders",
        "Refusé : les dossiers du mod Lua ont changé : {path}",
    ),
    (
        "err_unlisted_lua_file",
        "Refusé : un fichier non répertorié dans le mod Lua : {path}",
    ),
    ("err_replace", "Refusé : remplacer {path}"),
    (
        "err_nothing_to_remove",
        "Rien du pont à retirer dans {path}",
    ),
    (
        "err_remove_first",
        "Retirez d'abord le JAR du pont installé : {path}",
    ),
    (
        "err_fabric_missing",
        "Fabric API manque dans {path} et n'est pas fourni",
    ),
    (
        "err_expected_exe",
        "SB-Win64-Shipping.exe est attendu : {path}",
    ),
    (
        "err_unsupported_exe",
        "Exécutable de Stellar Blade non pris en charge : attendez une version du pont qui prend en charge cette version du jeu ({path})",
    ),
    (
        "err_bridge_control",
        "Le chemin du pont ne peut pas contenir de saut de ligne : {path}",
    ),
    (
        "err_bridge_not_absolute",
        "Le dossier du pont doit être un chemin absolu : {path}",
    ),
    (
        "err_bridge_mode",
        "Le dossier du pont doit appartenir à cet utilisateur, avec le mode 0700 : {path}",
    ),
    (
        "err_bridge_volume",
        "Le dossier du pont doit se trouver sur un disque NTFS local, jamais sur un partage réseau : {path}",
    ),
    (
        "err_bridge_ascii",
        "Le Lua de Stellar Blade n'ouvre que des chemins ASCII, et ce dossier contient d'autres caractères sans nom court (8.3) : {path}",
    ),
    (
        "err_no_ancestor",
        "Aucun dossier existant au-dessus de {path}",
    ),
    (
        "err_process_list",
        "Impossible de lister les processus pour vérifier que les jeux sont fermés",
    ),
    (
        "err_server_running",
        "Arrêtez d'abord le serveur Minecraft lancé dans {path}",
    ),
    (
        "err_server_folder",
        "Ni un dossier vide, ni un serveur Fabric pour Minecraft 1.21.1 : {path}",
    ),
    (
        "err_server_loader",
        "Le Fabric Loader de ce serveur est antérieur à 0.19.5, la version dont le pont a besoin : {path}",
    ),
    (
        "err_java_version",
        "Le serveur a besoin de Java 21 ou plus récent, en tant que `java` dans le PATH (trouvé : {path}). Installez-le, puis réessayez.",
    ),
    (
        "err_download",
        "Le téléchargement depuis {path} a échoué. Vérifiez la connexion, puis réessayez.",
    ),
    (
        "err_digest",
        "{path} n'est pas le fichier que cette version épingle (taille ou SHA-256 différents) : il n'a pas été utilisé.",
    ),
    (
        "err_oodle_entry",
        "{path} ne contient pas la bibliothèque Oodle à l'endroit où la version d'OodleUE la range.",
    ),
    (
        "err_usmap_invalid",
        "{path} n'est pas un usmap écrit par UE4SS (en-tête incorrect). Supprimez-le, puis appuyez sur Ctrl+Pavé numérique 6 dans Stellar Blade avec une sauvegarde chargée.",
    ),
    (
        "err_no_usmap",
        "Pas encore d'usmap pour cette version de Stellar Blade. Lancez Stellar Blade avec le pont installé, chargez une sauvegarde jusqu'à ce qu'EVE soit dans le monde, puis quittez ; ou appuyez sur Ctrl+Pavé numérique 6 en jeu.",
    ),
    (
        "err_usmap_rejected",
        "EveExtract n'a pas pu lire la table des objets du jeu avec l'usmap de la version {path} : il ne décrit pas cette version du jeu. Supprimez le .usmap du dossier d'UE4SS, lancez Stellar Blade, chargez une sauvegarde, appuyez sur Ctrl+Pavé numérique 6, quittez, puis relancez la création.",
    ),
    (
        "err_no_sidecar",
        "Cet installateur a été compilé sans EveExtract : utilisez une version publiée pour créer le pack d'avatar.",
    ),
    ("err_close_first", "Fermez d'abord {path}"),
    (
        "err_save_backup_differs",
        "La copie des sauvegardes diffère des sauvegardes : {path}",
    ),
    (
        "err_protected_changed",
        "Des fichiers protégés ont changé : {path}",
    ),
    (
        "err_saves_changed",
        "Les sauvegardes de Stellar Blade ont changé : {path}",
    ),
    (
        "err_mods_missing",
        "Le dossier mods de Minecraft n'existe pas : {path}",
    ),
    (
        "err_left_behind",
        "Le pont est encore installé dans {path} : désinstallez-le là d'abord, puis installez ici",
    ),
    (
        "err_no_jar",
        "{path} ne contient pas de JAR du pont à installer",
    ),
    (
        "err_no_game_side",
        "{path} ne contient pas la partie Stellar Blade à installer",
    ),
    (
        "err_mismatch_after_write",
        "{path} ne correspond pas à la version après l'écriture",
    ),
    (
        "err_install_restored",
        "L'installation a échoué ; l'état précédent a été rétabli",
    ),
    (
        "err_install_not_restored",
        "L'installation a échoué et l'état précédent n'a pas pu être rétabli ; les fichiers remplacés sont conservés dans {path}",
    ),
    (
        "err_install_not_removed",
        "L'installation a échoué et les fichiers écrits n'ont pas tous pu être retirés ; le journal contient les détails",
    ),
    (
        "err_no_previous",
        "Il n'y a pas de version précédente à rétablir",
    ),
    (
        "err_previous_changed",
        "La version précédente conservée a changé depuis : {path}",
    ),
    (
        "err_still_modified",
        "{path} n'est toujours pas tel qu'installé",
    ),
    (
        "err_run_update",
        "{path} diffère de ce que contient cet installateur : lancez plutôt la mise à jour",
    ),
    ("err_invalid_release", "Nom de version invalide : {path}"),
    ("err_cannot_keep", "Impossible de conserver {path}"),
    (
        "err_java_argument",
        "Argument Java non pris en charge pour l'instance (ni espace ni guillemet) : {path}",
    ),
    (
        "err_instance_unreadable",
        "La nouvelle instance ne peut pas être relue : {path}",
    ),
    (
        "err_pick_mods",
        "Choisissez le dossier mods de l'instance : {path}",
    ),
    // Server folder
    ("screen_server", "Dossier du serveur"),
    ("role_server", "Administrateur de serveur"),
    (
        "role_server_hint",
        "Un serveur Fabric pour Minecraft 1.21.1 : le JAR du pont et Fabric API dans son dossier mods, dans un dossier de serveur existant ou nouveau.",
    ),
    ("action_server", "Installer sur le serveur"),
    ("server_title", "Dossier du serveur"),
    (
        "server_body",
        "Saisissez le dossier d'un serveur Fabric pour Minecraft 1.21.1, ou un dossier vide ou nouveau pour en créer un avec le lanceur de serveur de Fabric. Puis vérifiez-le.",
    ),
    (
        "server_placeholder",
        "Dossier du serveur, par exemple /srv/minecraft ou C:\\Serveurs\\Minecraft",
    ),
    ("server_check", "Vérifier"),
    ("server_pick", "Saisissez d'abord le dossier du serveur."),
    (
        "server_new",
        "Nouveau serveur : {path} reçoit le lanceur de serveur de Fabric, start.sh, start.bat et un dossier mods",
    ),
    ("server_ours", "Serveur créé par cet installateur : {path}"),
    (
        "server_existing",
        "Serveur Fabric pour Minecraft 1.21.1, Fabric Loader {loader} : {path}",
    ),
    (
        "server_existing_unknown",
        "Serveur Fabric pour Minecraft 1.21.1 (version de Fabric Loader pas encore visible) : {path}",
    ),
    ("server_bridge_absent", "Pont : pas encore installé"),
    (
        "server_bridge_owned",
        "Pont : installé par cet installateur (mis à jour si cette version diffère)",
    ),
    ("server_java", "Java {version} lance le serveur"),
    ("server_eula_true", "CLUF de Mojang : accepté dans eula.txt"),
    (
        "server_eula_false",
        "CLUF de Mojang : eula.txt indique false. Le serveur démarrera quand vous y mettrez eula=true, après l'avoir lu.",
    ),
    (
        "server_eula_missing",
        "CLUF de Mojang : pas encore de eula.txt",
    ),
    (
        "server_offline",
        "online-mode=false : n'importe qui peut rejoindre sous n'importe quel nom. L'installateur laisse server.properties tel quel.",
    ),
    (
        "server_eula_body",
        "Le serveur Minecraft ne démarre qu'une fois le CLUF de Mojang accepté dans eula.txt. Lisez-le ; cochez la case seulement si vous l'acceptez pour ce serveur, ou modifiez eula.txt vous-même plus tard.",
    ),
    (
        "server_eula_check",
        "J'ai lu le CLUF de Minecraft de Mojang et je l'accepte pour ce serveur",
    ),
    (
        "server_never_touched",
        "server.properties (online-mode reste tel quel), les mondes, les autres mods et un eula.txt existant. Rien n'est téléchargé et le serveur n'est jamais lancé.",
    ),
    (
        "server_done_installed",
        "JAR du pont et Fabric API dans {path}",
    ),
    (
        "server_done_current",
        "Cette version est déjà installée : rien n'a changé.",
    ),
    ("server_done_created", "Nouveau dossier de serveur : {path}"),
    (
        "server_done_start",
        "Lancez le serveur avec start.sh (Linux) ou start.bat (Windows), ou comme d'habitude.",
    ),
    (
        "server_done_eula_todo",
        "Avant le premier lancement, lisez le CLUF de Mojang ({url}) et mettez eula=true dans eula.txt dans {path} si vous l'acceptez.",
    ),
    (
        "server_done_removed",
        "JAR du pont retiré. Fabric API, le lanceur, les scripts de lancement, eula.txt et les mondes restent.",
    ),
    // Avatar pack
    ("screen_pack_ready", "Pack d'avatar"),
    ("screen_pack_oodle", "Oodle"),
    ("screen_pack_working", "Création"),
    ("pack_build", "Créer le pack d'avatar"),
    ("pack_rebuild", "Recréer le pack d'avatar"),
    (
        "pack_button_hint",
        "Créé sur ce PC à partir de votre propre copie de Stellar Blade, en lecture seule. Il ne quitte jamais ce PC.",
    ),
    ("pack_title", "Créer le pack d'avatar"),
    (
        "pack_body",
        "Minecraft dessine EVE à partir de ce pack : ses maillages et ses textures, lus dans vos fichiers de Stellar Blade par EveExtract, puis préparés ici. Une création complète prend environ 10 à 15 minutes et environ 3 Go ; moins vous possédez de tenues, plus elle est petite et rapide. Votre pack installé reste tel quel jusqu'à ce que le nouveau soit complet.",
    ),
    (
        "pack_checking",
        "Vérification de ce dont la création a besoin…",
    ),
    ("pack_row_usmap", "Correspondances du jeu (usmap)"),
    ("pack_row_owned", "Tenues possédées"),
    ("pack_row_disk", "Espace disque libre"),
    ("pack_row_games", "Stellar Blade et Minecraft"),
    ("pack_row_memory", "Mémoire libre"),
    ("pack_row_sidecar", "EveExtract"),
    ("pack_row_oodle", "Oodle"),
    (
        "pack_build_untested",
        "Ce n'est pas la version testée par cette version de l'installateur : il peut manquer des objets au pack.",
    ),
    ("pack_owned_every", "Toutes les tenues sont créées"),
    (
        "pack_owned_list",
        "La liste de garde-robe de l'adaptateur du {date}",
    ),
    (
        "pack_owned_save",
        "Votre sauvegarde la plus récente, du {date} (en lecture seule)",
    ),
    (
        "pack_owned_none",
        "Ni liste de garde-robe ni sauvegarde trouvée : jouez une fois avec le pont, ou créez toutes les tenues",
    ),
    ("pack_disk_ok", "{free} libres ({need} nécessaires)"),
    (
        "pack_disk_low",
        "{free} libres : une création a besoin de {need}",
    ),
    ("pack_memory_ok", "{free} libres"),
    (
        "pack_memory_low",
        "{free} libres : les textures seront préparées par un seul processus, lentement",
    ),
    ("pack_unknown", "Inconnu"),
    (
        "pack_games_running",
        "En cours : {names}. Fermez-les, puis vérifiez à nouveau.",
    ),
    ("pack_sidecar_embedded", "Inclus dans cet installateur"),
    ("pack_oodle_ready", "Prêt (accepté le {date})"),
    (
        "pack_oodle_ask",
        "Pas encore sur ce PC : les pages suivantes vous le demandent",
    ),
    (
        "pack_every",
        "Créer toutes les tenues, pas seulement celles que vous possédez",
    ),
    (
        "pack_every_hint",
        "Plus gros et plus lent, et permet à votre Minecraft de dessiner les tenues de vos amis en multijoueur.",
    ),
    ("pack_consent_title", "Vos propres fichiers de jeu"),
    (
        "pack_consent_body",
        "Le créateur lit le Stellar Blade de ce PC, en lecture seule, les deux jeux fermés. Il écrit un pack privé dans {path}, que ce projet n'envoie ni ne partage jamais : il est dérivé de vos fichiers de jeu et reste sur ce PC. Rien n'est écrit dans le dossier du jeu.",
    ),
    (
        "pack_consent_eula",
        "Le contrat de licence de Stellar Blade interdit de modifier le jeu, de le désosser ou d'en faire des œuvres dérivées, ainsi que les mods non autorisés. Le pont et ce créateur en relèvent : vous continuez à vos risques.",
    ),
    (
        "pack_consent_check",
        "J'ai fermé Stellar Blade et Minecraft, j'accepte ce risque et j'accepte de créer le pack à partir de mes propres fichiers de jeu.",
    ),
    ("oodle_title", "Oodle"),
    (
        "oodle_body",
        "Les fichiers de Stellar Blade sont compressés avec Oodle, dont EveExtract a besoin pour les lire. Oodle est propriétaire (Epic Games, RAD Game Tools) : ce projet ne le fournit pas. Le créateur peut le télécharger une fois depuis la publication d'un tiers, celle qu'utilise aussi CUE4Parse, puis le vérifie par son empreinte épinglée.",
    ),
    ("oodle_source", "Source"),
    ("oodle_release", "Publication"),
    ("oodle_size", "Taille"),
    (
        "oodle_eula",
        "Cette publication est fournie sous l'EULA d'Unreal Engine, que vous acceptez en l'utilisant :",
    ),
    ("oodle_download", "Télécharger"),
    ("oodle_have_title", "Utiliser un fichier que j'ai"),
    (
        "oodle_have_body",
        "Ou indiquez le chemin de {file} de cette publication, déjà sur ce PC. Il n'est utilisé que si son SHA-256 est celui ci-dessus.",
    ),
    ("oodle_have_placeholder", "Chemin du zip"),
    ("oodle_have_use", "Utiliser ce fichier"),
    ("oodle_pick", "Saisissez d'abord le chemin du zip."),
    ("oodle_ready", "Oodle est prêt"),
    ("pack_working_title", "Création du pack d'avatar"),
    (
        "pack_working_body",
        "Votre pack installé reste tel quel jusqu'à ce que le nouveau soit complet. Annuler arrête EveExtract et supprime le dossier de travail.",
    ),
    ("pack_elapsed", "Temps écoulé : {time}"),
    ("pack_cancelling", "Annulation…"),
    ("pack_step_inputs", "Préparation d'EveExtract et de l'usmap"),
    ("pack_step_tables", "Lecture des tables d'objets"),
    ("pack_step_meshes", "Lecture des maillages"),
    ("pack_step_export", "Export des matériaux et des textures"),
    ("pack_step_bake", "Préparation des textures"),
    ("pack_step_install", "Installation du nouveau pack"),
    ("pack_done_ok", "Pack d'avatar créé en {time}"),
    ("pack_done_failed", "Pack d'avatar : non créé"),
    (
        "pack_result_meshes",
        "{meshes} maillages, {items} objets de garde-robe, {files} fichiers ({size})",
    ),
    ("pack_result_every", "Toutes les tenues"),
    (
        "pack_result_owned_list",
        "{count} tenues possédées, d'après la liste de garde-robe de l'adaptateur du {date}",
    ),
    (
        "pack_result_owned_save",
        "{count} tenues possédées, d'après votre sauvegarde du {date}",
    ),
    (
        "pack_result_incremental",
        "Créé à partir du pack installé : {count} maillages exportés, {baked} textures préparées",
    ),
    (
        "pack_result_full",
        "Créé depuis le début : {count} maillages exportés, {baked} textures préparées",
    ),
    (
        "pack_result_untextured",
        "{count} section(s) de maillage sans texture ; la prochaine création les réessaie",
    ),
    (
        "pack_result_bones",
        "{count} maillage(s) utilisent plus de 256 os ; Minecraft en dessine jusqu'à 512",
    ),
    (
        "pack_result_missing",
        "{count} maillage(s) de garde-robe absents de vos fichiers de jeu",
    ),
    (
        "pack_result_note",
        "Créé à partir de vos fichiers de jeu tels qu'ils sont maintenant : les tenues obtenues après votre dernière sauvegarde apparaîtront à la prochaine création.",
    ),
    ("pack_warnings", "Avertissements ({count})"),
    ("pack_log_tail", "Les dernières lignes du journal"),
    ("pack_cancelled", "Annulé."),
    (
        "pack_failed",
        "La création s'est arrêtée. Les dernières lignes du journal sont ci-dessous.",
    ),
    ("pack_unchanged", "Votre pack précédent est inchangé."),
];
