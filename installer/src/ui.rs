//! The window's drawing: Colony chrome (identity button, sidebar, Preferences page from colony-ui), one function per
//! page, and what colony-ui does not provide: button styles, the error toast, the status row and the stacked modal.

use colony_ui::theme::Palette;
use colony_ui::widgets::{self, ABOUT_CATEGORY, PreferencesView, STANDARD_CATEGORIES};
use colony_ui::{Typography, contrast_on, fonts};
use iced::widget::{
    Column, button, checkbox, column, container, mouse_area, opaque, progress_bar, row, scrollable,
    space, stack, text, text_input,
};
use iced::{Alignment, Border, Color, Element, Fill, Length};

use crate::app::{Action, App, Choice, Message, Modal, Role, Screen};
use crate::detect::{Override, Pack};
use crate::i18n::{self, Lang, t, t_fmt};
use crate::launcher::{self, Kind};
use crate::ledger::Status;
use crate::ops::{self, Step};
use crate::safety::Running;
use crate::{Ownership, detect, pins};

type El<'a> = Element<'a, Message>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Ok,
    Warn,
    Bad,
    Info,
}

fn heading<'a>(typo: &Typography, label: &str) -> El<'a> {
    text(label.to_string())
        .size(typo.sz(22))
        .font(typo.bold)
        .color(Palette::TEXT_PRIMARY())
        .into()
}

fn subheading<'a>(typo: &Typography, label: &str) -> El<'a> {
    text(label.to_string())
        .size(typo.sz(15))
        .font(typo.medium)
        .color(Palette::TEXT_PRIMARY())
        .into()
}

fn para<'a>(typo: &Typography, body: &str) -> El<'a> {
    text(body.to_string())
        .size(typo.sz(13))
        .font(typo.regular)
        .color(Palette::TEXT_SECONDARY())
        .into()
}

fn muted<'a>(typo: &Typography, body: &str) -> El<'a> {
    text(body.to_string())
        .size(typo.sz(12))
        .font(typo.regular)
        .color(Palette::TEXT_MUTED())
        .into()
}

/// A path or command, in the monospace UI font.
fn code<'a>(typo: &Typography, body: &str) -> El<'a> {
    text(body.to_string())
        .size(typo.sz(12))
        .font(fonts::glyph_font())
        .color(Palette::TEXT_SECONDARY())
        .into()
}

#[derive(Clone, Copy)]
enum Tone {
    Primary,
    Secondary,
    Danger,
}

fn action_button<'a>(
    typo: &Typography,
    label: &str,
    kind: Tone,
    on_press: Option<Message>,
) -> El<'a> {
    button(text(label.to_string()).size(typo.sz(13)).font(typo.medium))
        .on_press_maybe(on_press)
        .padding([8, 18])
        .style(move |_theme, status| {
            let (bg, hover, fg) = match kind {
                Tone::Primary => (
                    Palette::ACCENT(),
                    Palette::ACCENT(),
                    contrast_on(Palette::ACCENT()),
                ),
                Tone::Secondary => (
                    Palette::BTN_DEFAULT(),
                    Palette::BTN_HOVER(),
                    Palette::TEXT_PRIMARY(),
                ),
                Tone::Danger => (
                    Palette::BTN_DANGER_BG(),
                    Palette::BTN_DANGER_HOVER(),
                    Palette::ERROR_LIGHT(),
                ),
            };
            let (background, text_color) = match status {
                button::Status::Disabled => (Palette::BG_CARD(), Palette::TEXT_DIMMEST()),
                button::Status::Hovered | button::Status::Pressed => (hover, fg),
                button::Status::Active => (bg, fg),
            };
            button::Style {
                background: Some(background.into()),
                text_color,
                border: Border::default().rounded(8),
                ..Default::default()
            }
        })
        .into()
}

fn primary<'a>(typo: &Typography, label: &str, on_press: Option<Message>) -> El<'a> {
    action_button(typo, label, Tone::Primary, on_press)
}

fn secondary<'a>(typo: &Typography, label: &str, on_press: Option<Message>) -> El<'a> {
    action_button(typo, label, Tone::Secondary, on_press)
}

fn card<'a>(content: impl Into<El<'a>>) -> El<'a> {
    container(content)
        .padding(16)
        .width(Fill)
        .style(|_theme| container::Style {
            background: Some(Palette::BG_CARD().into()),
            border: Border::default().rounded(10),
            ..Default::default()
        })
        .into()
}

/// One detection row: a status glyph, the item, what was found.
fn status_row<'a>(typo: &Typography, level: Level, label: &str, value: String) -> El<'a> {
    let (glyph, color) = match level {
        Level::Ok => ("\u{f00c}", Palette::SUCCESS()),
        Level::Warn => ("\u{f071}", Palette::WARNING()),
        Level::Bad => ("\u{f00d}", Palette::ERROR()),
        Level::Info => ("\u{f05a}", Palette::TEXT_DIMMER()),
    };
    row![
        text(glyph)
            .size(typo.sz(13))
            .font(fonts::glyph_font())
            .color(color)
            .width(22.0 * typo.scale),
        text(label.to_string())
            .size(typo.sz(13))
            .font(typo.medium)
            .color(Palette::TEXT_PRIMARY())
            .width(190.0 * typo.scale),
        text(value)
            .size(typo.sz(12))
            .font(typo.regular)
            .color(Palette::TEXT_SECONDARY())
            .width(Fill),
    ]
    .spacing(8)
    .align_y(Alignment::Start)
    .into()
}

/// colony-ui's toast is a success; an error needs its own colours.
fn error_toast<'a>(typo: &Typography, message: &str, on_dismiss: Message) -> El<'a> {
    button(
        row![
            text(message.to_string())
                .size(typo.sz(13))
                .font(typo.regular)
                .color(Palette::ERROR()),
            text(widgets::icons::CLOSE)
                .size(typo.sz(12))
                .font(fonts::glyph_font())
                .color(Palette::TEXT_DIMMER()),
        ]
        .spacing(12)
        .align_y(Alignment::Center),
    )
    .on_press(on_dismiss)
    .padding([8, 16])
    .style(|_theme, _status| button::Style {
        background: Some(Palette::ERROR_BG().into()),
        text_color: Palette::TEXT_PRIMARY(),
        border: Border::default().rounded(8),
        ..Default::default()
    })
    .into()
}

/// The stacked modal: a dimmed backdrop that cancels on click, the dialog centred over it.
fn modal<'a>(base: El<'a>, dialog: El<'a>) -> El<'a> {
    let dialog = container(dialog)
        .padding(24)
        .max_width(640)
        .style(|_theme| container::Style {
            background: Some(Palette::BG_SIDEBAR().into()),
            border: Border::default().rounded(12),
            ..Default::default()
        });
    let backdrop = container(opaque(dialog))
        .center(Fill)
        .style(|_theme| container::Style {
            background: Some(
                Color {
                    a: 0.5,
                    ..Color::BLACK
                }
                .into(),
            ),
            ..Default::default()
        });
    stack![
        base,
        opaque(mouse_area(backdrop).on_press(Message::CancelModal))
    ]
    .into()
}

fn lines<'a>(typo: &Typography, items: impl IntoIterator<Item = String>) -> El<'a> {
    Column::with_children(items.into_iter().map(|line| code(typo, &line)))
        .spacing(4)
        .into()
}

fn screen_label(screen: Screen) -> &'static str {
    match screen {
        Screen::Welcome => t("screen_welcome"),
        Screen::Detect => t("screen_detect"),
        Screen::Verify => t("screen_verify"),
        Screen::Target => t("screen_target"),
        Screen::Consent => t("screen_consent"),
        Screen::Working => t("screen_working"),
        Screen::Done => t("screen_done"),
    }
}

pub fn step_label(step: Step) -> &'static str {
    match step {
        Step::Checks => t("step_checks"),
        Step::SaveBackup => t("step_save_backup"),
        Step::KeepPrevious => t("step_keep_previous"),
        Step::Remove => t("step_remove"),
        Step::Minecraft => t("step_minecraft"),
        Step::LuaMod => t("step_lua"),
        Step::Adapter => t("step_adapter"),
        Step::Repair => t("step_repair"),
        Step::Verify => t("step_verify"),
        Step::Restore => t("step_restore"),
        Step::Record => t("step_record"),
    }
}

fn action_label(action: Action) -> &'static str {
    match action {
        Action::Install => t("action_install"),
        Action::Repair => t("action_repair"),
        Action::Rollback => t("action_rollback"),
        Action::Uninstall => t("action_uninstall"),
    }
}

fn running_label(running: Running) -> &'static str {
    match running {
        Running::StellarBlade => t("running_sb"),
        Running::Minecraft => t("running_minecraft"),
        Running::Launcher => t("running_launcher"),
    }
}

fn kind_label(kind: Kind) -> &'static str {
    match kind {
        Kind::Prism => "Prism Launcher",
        Kind::PrismFlatpak => "Prism Launcher (Flatpak)",
        Kind::MultiMc => "MultiMC",
    }
}

pub fn view(app: &App) -> El<'_> {
    let typo = Typography::current();
    let mut sidebar = column![
        widgets::identity_button(
            &typo,
            "Stellar Bridge",
            22,
            app.preferences_open,
            Message::TogglePreferences
        ),
        space().height(20),
        widgets::nav_label(&typo, t("nav_steps")),
    ]
    .spacing(8);
    for screen in Screen::WIZARD {
        let current = screen == app.screen;
        sidebar = sidebar.push(
            text(screen_label(screen))
                .size(typo.sz(13))
                .font(if current { typo.bold } else { typo.regular })
                .color(if current {
                    Palette::ACCENT()
                } else {
                    Palette::TEXT_MUTED()
                }),
        );
    }
    if app.ctx.dry_run {
        sidebar = sidebar.push(space().height(12)).push(
            text(t("dry_run_badge"))
                .size(typo.sz(12))
                .font(typo.bold)
                .color(Palette::WARNING()),
        );
    }
    let release = app
        .ctx
        .payload
        .as_ref()
        .map_or_else(|| t("no_payload_short").to_string(), |p| p.release.clone());
    let sidebar = sidebar
        .push(space().height(Length::Fill))
        .push(widgets::nav_hint(&typo, &release))
        .padding(16)
        .width(210.0 * typo.scale);

    let content: El<'_> = if app.preferences_open {
        preferences(app, &typo)
    } else {
        let page = match app.screen {
            Screen::Welcome => welcome(app, &typo),
            Screen::Detect => detect_page(app, &typo),
            Screen::Verify => verify(app, &typo),
            Screen::Target => target(app, &typo),
            Screen::Consent => consent(app, &typo),
            Screen::Working => working(app, &typo),
            Screen::Done => done(app, &typo),
        };
        scrollable(container(page).padding(28).width(Fill))
            .height(Fill)
            .into()
    };

    let content: El<'_> = if app.toasts.is_empty() {
        content
    } else {
        let toasts = Column::with_children(app.toasts.iter().map(|toast| {
            if toast.error {
                error_toast(&typo, &toast.text, Message::DismissToast(toast.id))
            } else {
                widgets::toast(&typo, &toast.text, Message::DismissToast(toast.id))
            }
        }))
        .spacing(6);
        stack![content, container(toasts).padding(16).align_bottom(Fill)].into()
    };

    let base: El<'_> = container(row![
        container(sidebar)
            .height(Fill)
            .style(|_theme| container::Style {
                background: Some(Palette::BG_SIDEBAR().into()),
                ..Default::default()
            }),
        content,
    ])
    .style(|_theme| container::Style {
        background: Some(Palette::BG_PRIMARY().into()),
        ..Default::default()
    })
    .into();

    match &app.modal {
        None => base,
        Some(Modal::Confirm(action, items)) => {
            let mut dialog = column![
                subheading(
                    &typo,
                    &t_fmt("confirm_title", &[("action", action_label(*action))])
                ),
                para(
                    &typo,
                    t(match action {
                        Action::Repair => "confirm_repair",
                        Action::Rollback => "confirm_rollback",
                        _ => "confirm_uninstall",
                    })
                ),
                scrollable(lines(&typo, items.iter().cloned())).height(Length::Shrink),
            ]
            .spacing(12);
            if *action == Action::Uninstall {
                dialog = dialog.push(para(&typo, t("uninstall_keeps")));
                if app.fabric_listed() {
                    dialog = dialog.push(
                        checkbox(app.remove_fabric)
                            .label(t("remove_fabric"))
                            .on_toggle(Message::RemoveFabric)
                            .text_size(typo.sz(13)),
                    );
                }
            }
            let confirm = if app.ctx.dry_run {
                t("dry_run_finish")
            } else {
                action_label(*action)
            };
            let kind = if *action == Action::Uninstall {
                Tone::Danger
            } else {
                Tone::Primary
            };
            dialog = dialog.push(
                row![
                    secondary(&typo, t("cancel"), Some(Message::CancelModal)),
                    action_button(&typo, confirm, kind, Some(Message::Proceed)),
                ]
                .spacing(12),
            );
            modal(base, dialog.into())
        }
        Some(Modal::Games(running)) => {
            let mut dialog = column![
                subheading(&typo, t("games_title")),
                para(&typo, t("games_body")),
            ]
            .spacing(12);
            if running.is_empty() {
                dialog = dialog.push(status_row(
                    &typo,
                    Level::Ok,
                    t("games_all_closed"),
                    String::new(),
                ));
            }
            for item in running {
                dialog = dialog.push(status_row(
                    &typo,
                    Level::Warn,
                    running_label(*item),
                    t("games_running").into(),
                ));
            }
            dialog = dialog.push(
                row![
                    secondary(&typo, t("cancel"), Some(Message::CancelModal)),
                    primary(
                        &typo,
                        t("continue"),
                        running.is_empty().then_some(Message::Proceed)
                    ),
                ]
                .spacing(12),
            );
            modal(base, dialog.into())
        }
    }
}

fn nav<'a>(typo: &Typography, back: bool, next: El<'a>) -> El<'a> {
    let mut buttons = row![].spacing(12);
    if back {
        buttons = buttons.push(secondary(typo, t("back"), Some(Message::Back)));
    }
    buttons.push(next).into()
}

fn welcome<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let role = |role: Role, label: &str, hint: &str| -> El<'a> {
        button(
            column![
                text(label.to_string()).size(typo.sz(14)).font(typo.medium),
                text(hint.to_string()).size(typo.sz(12)).font(typo.regular),
            ]
            .spacing(4),
        )
        .on_press(Message::Role(role))
        .padding([10, 14])
        .width(Fill)
        .style(widgets::selection_style(app.role == role))
        .into()
    };
    let languages = Lang::ALL.map(|lang| (lang, lang.name().to_string()));
    column![
        heading(typo, t("welcome_title")),
        para(typo, t("welcome_body")),
        widgets::setting_row(
            typo,
            t("language"),
            t("language_desc"),
            widgets::dropdown(typo, languages, &i18n::lang(), Message::Language),
        ),
        subheading(typo, t("role_title")),
        role(Role::Eve, t("role_eve"), t("role_eve_hint")),
        role(Role::Viewer, t("role_viewer"), t("role_viewer_hint")),
        card(
            column![
                subheading(typo, t("welcome_changes_title")),
                para(typo, t("welcome_changes")),
                subheading(typo, t("never_touched_title")),
                para(typo, t("never_touched")),
            ]
            .spacing(8)
        ),
        nav(typo, false, primary(typo, t("start"), Some(Message::Start))),
    ]
    .spacing(14)
    .into()
}

/// A piece's status row: its level and what it says, a refusal with its path.
pub fn piece_status(piece: &ops::Piece) -> (Level, String) {
    match &piece.ownership {
        Ownership::Absent => (Level::Info, t("piece_absent").to_string()),
        Ownership::Owned if piece.current => (Level::Ok, t("piece_current").to_string()),
        Ownership::Owned => (Level::Warn, t("piece_other_release").to_string()),
        Ownership::Foreign(refusal) | Ownership::Modified(refusal) => {
            (Level::Bad, i18n::refusal(refusal))
        }
    }
}

fn ownership_row<'a>(typo: &Typography, piece: &ops::Piece) -> El<'a> {
    let (level, value) = piece_status(piece);
    status_row(typo, level, i18n::piece(piece.name), value)
}

fn detect_page<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let mut page = column![heading(typo, t("detect_title"))].spacing(14);
    let Some(found) = &app.found else {
        return page.push(para(typo, t("detecting"))).into();
    };
    let mut rows = column![].spacing(10);
    rows = rows.push(match found.steam.len() {
        0 => status_row(
            typo,
            Level::Bad,
            t("row_steam"),
            t("row_steam_missing").into(),
        ),
        n => status_row(
            typo,
            Level::Ok,
            t("row_steam"),
            t_fmt(
                "row_steam_found",
                &[
                    ("count", &n.to_string()),
                    (
                        "libraries",
                        &found
                            .steam
                            .iter()
                            .map(|s| s.libraries.len())
                            .sum::<usize>()
                            .to_string(),
                    ),
                ],
            ),
        ),
    });
    match &found.game {
        None => {
            rows = rows.push(status_row(
                typo,
                Level::Bad,
                t("row_game"),
                t("blocker_no_game").into(),
            ))
        }
        Some(game) => {
            let build = game.build_id.clone().unwrap_or_else(|| "?".into());
            let value = format!(
                "{}\n{}",
                game.dir.display(),
                t_fmt("row_game_build", &[("build", &build)])
            );
            let (level, value) = if game.updating {
                (Level::Warn, format!("{value}\n{}", t("blocker_updating")))
            } else if game.build_supported() {
                (Level::Ok, value)
            } else {
                (Level::Bad, format!("{value}\n{}", t("blocker_build")))
            };
            rows = rows.push(status_row(typo, level, t("row_game"), value));
            rows = rows.push(match &game.proton {
                None => status_row(
                    typo,
                    Level::Info,
                    t("row_proton"),
                    t("row_proton_default").into(),
                ),
                Some(name) if name == pins::PROTON_TESTED => {
                    status_row(typo, Level::Ok, t("row_proton"), name.clone())
                }
                Some(name) => status_row(
                    typo,
                    Level::Warn,
                    t("row_proton"),
                    t_fmt(
                        "row_proton_untested",
                        &[("name", name), ("tested", pins::PROTON_TESTED)],
                    ),
                ),
            });
            rows = rows.push(match &game.prefix {
                Some(prefix) => status_row(
                    typo,
                    Level::Ok,
                    t("row_prefix"),
                    prefix.display().to_string(),
                ),
                None => status_row(
                    typo,
                    Level::Warn,
                    t("row_prefix"),
                    t("row_prefix_missing").into(),
                ),
            });
            rows = rows.push(match &found.ue4ss {
                None => status_row(
                    typo,
                    Level::Bad,
                    t("row_ue4ss"),
                    t("blocker_no_ue4ss").into(),
                ),
                Some(u) if u.mods.is_none() => status_row(
                    typo,
                    Level::Bad,
                    t("row_ue4ss"),
                    t("blocker_ue4ss_mods").into(),
                ),
                Some(u) => status_row(
                    typo,
                    if u.known { Level::Ok } else { Level::Warn },
                    t("row_ue4ss"),
                    format!(
                        "{}\n{}",
                        u.dir.display(),
                        t(if u.known {
                            "row_ue4ss_known"
                        } else {
                            "row_ue4ss_untested"
                        })
                    ),
                ),
            });
            rows = rows.push(match found.dll_override {
                Some(Override::Registry) => status_row(
                    typo,
                    Level::Ok,
                    t("row_override"),
                    t("row_override_registry").into(),
                ),
                Some(Override::LaunchOptions) => status_row(
                    typo,
                    Level::Ok,
                    t("row_override"),
                    t("row_override_launch").into(),
                ),
                _ => status_row(
                    typo,
                    Level::Warn,
                    t("row_override"),
                    t("row_override_missing").into(),
                ),
            });
        }
    }
    let compatible = found
        .instances
        .iter()
        .filter(|(_, i)| i.compatible())
        .count();
    rows = rows.push(status_row(
        typo,
        if found.launchers.is_empty() {
            Level::Info
        } else {
            Level::Ok
        },
        t("row_minecraft"),
        if found.launchers.is_empty() {
            t("row_minecraft_none").into()
        } else {
            t_fmt(
                "row_minecraft_found",
                &[
                    (
                        "launchers",
                        &found
                            .launchers
                            .iter()
                            .map(|l| kind_label(l.kind))
                            .collect::<Vec<_>>()
                            .join(", "),
                    ),
                    ("count", &found.instances.len().to_string()),
                    ("compatible", &compatible.to_string()),
                ],
            )
        },
    ));
    for piece in &found.pieces {
        rows = rows.push(ownership_row(typo, piece));
    }
    if let Some(error) = &found.pieces_error {
        rows = rows.push(status_row(
            typo,
            Level::Bad,
            t("row_install"),
            error.clone(),
        ));
    }
    if found.current.is_none() {
        rows = rows.push(status_row(
            typo,
            Level::Info,
            t("row_install"),
            t("row_install_none").into(),
        ));
    }
    if let Some(pack) = &found.pack {
        let (level, value) = match pack {
            Pack::Missing => (Level::Warn, t("pack_missing").to_string()),
            Pack::Untracked => (Level::Info, t("pack_untracked").to_string()),
            Pack::Unreadable => (Level::Warn, t("pack_unreadable").to_string()),
            Pack::Stale(changes) => (
                Level::Warn,
                t_fmt("pack_stale", &[("count", &changes.len().to_string())]),
            ),
            Pack::Fresh => (Level::Ok, t("pack_fresh").to_string()),
        };
        rows = rows.push(status_row(typo, level, t("row_pack"), value));
    }
    if let Some(state) = &found.state {
        let (level, key) = match state.status {
            Status::InProgress => (Level::Warn, "state_interrupted"),
            Status::Installed => (Level::Ok, "state_installed"),
            Status::Removed => (Level::Info, "state_removed"),
        };
        let release = if state.release.is_empty() {
            "?"
        } else {
            &state.release
        };
        rows = rows.push(status_row(
            typo,
            level,
            t("row_record"),
            t_fmt(key, &[("release", release)]),
        ));
    }
    if app.ctx.payload.is_none() {
        rows = rows.push(status_row(
            typo,
            Level::Bad,
            t("row_payload"),
            t("no_payload").into(),
        ));
    }
    page = page.push(card(rows));
    if app.role == Role::Eve
        && let Some(blocker) = app.game_blocker()
    {
        page = page.push(para(
            typo,
            &format!("{} {}", t(blocker), t("viewer_still_possible")),
        ));
    }

    let mut actions = row![
        secondary(typo, t("back"), Some(Message::Back)),
        secondary(
            typo,
            t("rescan"),
            (!app.detecting).then_some(Message::Rescan)
        ),
        {
            let (label, enabled) = app.primary_action();
            primary(typo, t(label), enabled.then_some(Message::Continue))
        },
    ]
    .spacing(12);
    page = page.push(actions);
    if found.installed() && found.current.is_some() {
        let has_previous = found
            .state
            .as_ref()
            .and_then(|s| s.previous.as_ref())
            .is_some_and(|p| p.is_dir());
        actions = row![
            secondary(
                typo,
                t("action_repair"),
                app.ctx
                    .payload
                    .is_some()
                    .then_some(Message::Begin(Action::Repair))
            ),
            secondary(
                typo,
                t("action_rollback"),
                has_previous.then_some(Message::Begin(Action::Rollback))
            ),
            action_button(
                typo,
                t("action_uninstall"),
                Tone::Danger,
                Some(Message::Begin(Action::Uninstall))
            ),
        ]
        .spacing(12);
        page = page
            .push(subheading(typo, t("maintenance_title")))
            .push(actions);
    }
    page.into()
}

fn verify<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let mut page = column![
        heading(typo, t("verify_title")),
        para(typo, t("verify_body"))
    ]
    .spacing(14);
    if let Some(progress) = app.hash_progress {
        page = page
            .push(progress_bar(0.0..=1.0, progress).girth(8))
            .push(muted(typo, &format!("{:.0} %", progress * 100.0)));
    }
    match app.exe_ok {
        Some(true) => {
            page = page.push(status_row(
                typo,
                Level::Ok,
                t("row_exe"),
                t("exe_ok").into(),
            ))
        }
        Some(false) => {
            page = page
                .push(status_row(
                    typo,
                    Level::Bad,
                    t("row_exe"),
                    t("exe_mismatch").into(),
                ))
                .push(secondary(
                    typo,
                    t("continue_as_viewer"),
                    Some(Message::Role(Role::Viewer)),
                ));
        }
        None => {}
    }
    let ready = app.exe_ok == Some(true) || app.role == Role::Viewer;
    let next = primary(typo, t("continue"), ready.then_some(Message::Continue));
    page.push(nav(typo, true, next)).into()
}

fn target<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let mut page = column![
        heading(typo, t("target_title")),
        para(typo, t("target_body"))
    ]
    .spacing(12);
    let Some(found) = &app.found else {
        return page.into();
    };
    let option = |choice: Choice, label: String, hint: String| -> El<'a> {
        button(
            column![
                text(label).size(typo.sz(14)).font(typo.medium),
                text(hint).size(typo.sz(12)).font(typo.regular),
            ]
            .spacing(4),
        )
        .on_press(Message::Choose(choice))
        .padding([10, 14])
        .width(Fill)
        .style(widgets::selection_style(app.choice == Some(choice)))
        .into()
    };
    for choice in app.choices() {
        let item = match choice {
            Choice::Current => {
                let mods = found
                    .state
                    .as_ref()
                    .and_then(|s| s.target.as_ref())
                    .map(|t| t.minecraft_mods.display().to_string())
                    .unwrap_or_default();
                option(choice, t("choice_current").into(), mods)
            }
            Choice::NewInstance(i) => {
                let launcher = &found.launchers[i];
                let mut hint = launcher
                    .instances
                    .join(launcher::INSTANCE_NAME)
                    .display()
                    .to_string();
                if launcher.kind == Kind::PrismFlatpak {
                    hint = format!("{hint}\n{}", t("choice_flatpak_hint"));
                }
                option(
                    choice,
                    t_fmt(
                        "choice_new",
                        &[
                            ("name", launcher::INSTANCE_NAME),
                            ("launcher", kind_label(launcher.kind)),
                        ],
                    ),
                    hint,
                )
            }
            Choice::Instance(i) => {
                let (kind, instance) = &app.instances[i];
                option(
                    choice,
                    format!("{} ({})", instance.name, kind_label(*kind)),
                    instance.mods().display().to_string(),
                )
            }
            Choice::Manual => column![
                option(
                    choice,
                    t("choice_manual").into(),
                    t("choice_manual_hint").into()
                ),
                text_input(t("choice_manual_placeholder"), &app.manual_path)
                    .on_input(Message::ManualPath)
                    .size(typo.sz(13))
                    .padding(8),
            ]
            .spacing(6)
            .into(),
        };
        page = page.push(item);
    }
    let others = app
        .instances
        .iter()
        .filter(|(kind, i)| !i.compatible() || *kind == Kind::PrismFlatpak)
        .count();
    if others > 0 {
        page = page.push(muted(
            typo,
            &t_fmt("target_others", &[("count", &others.to_string())]),
        ));
    }
    page = page.push(subheading(typo, t("multimc_title"))).push(
        row![
            text_input(t("multimc_placeholder"), &app.multimc_path)
                .on_input(Message::MultiMcPath)
                .on_submit(Message::LoadMultiMc)
                .size(typo.sz(13))
                .padding(8),
            secondary(typo, t("multimc_load"), Some(Message::LoadMultiMc)),
        ]
        .spacing(8)
        .align_y(Alignment::Center),
    );
    page.push(nav(
        typo,
        true,
        primary(typo, t("continue"), app.choice.map(|_| Message::ToConsent)),
    ))
    .into()
}

fn consent<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let mut page = column![heading(typo, t("consent_title"))].spacing(12);
    let Some((target, _, plan)) = &app.plan else {
        return page.into();
    };
    let show = |paths: &[std::path::PathBuf]| {
        paths
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
    };
    if plan.writes.is_empty() {
        page = page.push(para(typo, t("consent_nothing")));
    } else {
        page = page
            .push(subheading(typo, t("consent_writes")))
            .push(card(lines(typo, show(&plan.writes))));
    }
    if !plan.replaced.is_empty() {
        page = page
            .push(subheading(typo, t("consent_replaced")))
            .push(card(lines(typo, show(&plan.replaced))));
    }
    if !plan.backups.is_empty() {
        page = page
            .push(subheading(typo, t("consent_backups")))
            .push(card(lines(typo, show(&plan.backups))));
    }
    page = page
        .push(subheading(typo, t("never_touched_title")))
        .push(para(typo, t("never_touched")));
    if target.game.is_some() {
        page = page
            .push(para(typo, t("consent_eula")))
            .push(code(typo, pins::SB_EULA));
    }
    page = page.push(
        checkbox(app.consent)
            .label(t("consent_check"))
            .on_toggle(Message::Consent)
            .text_size(typo.sz(13)),
    );
    let label = if app.ctx.dry_run {
        t("dry_run_finish")
    } else {
        t("action_install")
    };
    page.push(nav(
        typo,
        true,
        primary(
            typo,
            label,
            app.consent.then_some(Message::Begin(Action::Install)),
        ),
    ))
    .into()
}

fn working<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let title = app.action.map_or(t("screen_working"), action_label);
    let mut steps = column![].spacing(6);
    for (i, step) in app.steps.iter().enumerate() {
        let last = i + 1 == app.steps.len();
        let level = if last && app.busy {
            Level::Info
        } else {
            Level::Ok
        };
        steps = steps.push(status_row(typo, level, step_label(*step), String::new()));
    }
    let mut page = column![
        heading(typo, title),
        para(typo, t("working_body")),
        card(steps)
    ]
    .spacing(12);
    if !app.log.is_empty() {
        page = page.push(card(lines(typo, app.log.iter().cloned())));
    }
    page.into()
}

fn done<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let mut page = column![].spacing(12);
    let Some(outcome) = &app.outcome else {
        return page.into();
    };
    let title = if outcome.dry_run {
        t_fmt("done_dry_run", &[("action", action_label(outcome.action))])
    } else if outcome.ok {
        t_fmt("done_ok", &[("action", action_label(outcome.action))])
    } else {
        t_fmt("done_failed", &[("action", action_label(outcome.action))])
    };
    page = page
        .push(heading(typo, &title))
        .push(card(lines(typo, outcome.lines.iter().cloned())));
    if outcome.dry_run
        && outcome.action == Action::Install
        && let Some((_, _, plan)) = &app.plan
    {
        page = page
            .push(subheading(typo, t("consent_writes")))
            .push(card(lines(
                typo,
                plan.writes.iter().map(|p| p.display().to_string()),
            )));
    }
    let installed = outcome.action == Action::Install && outcome.ok;
    if installed && app.role == Role::Eve {
        let found = app.found.as_ref();
        if found.and_then(|f| f.dll_override) == Some(Override::Missing) {
            let existing = found
                .and_then(|f| f.game.as_ref())
                .and_then(|g| g.launch_options.first().cloned())
                .unwrap_or_default();
            let option = detect::launch_options_with_override(&existing);
            page = page.push(card(
                column![
                    subheading(typo, t("launch_option_title")),
                    para(typo, t("launch_option_body")),
                    code(typo, &option),
                    secondary(typo, t("copy"), Some(Message::Copy(option.clone()))),
                ]
                .spacing(8),
            ));
        }
        let pack = match found.and_then(|f| f.pack.as_ref()) {
            Some(Pack::Fresh) => t("pack_fresh"),
            Some(Pack::Stale(_)) => t("pack_stale_short"),
            _ => t("pack_missing"),
        };
        page = page.push(card(
            column![
                subheading(typo, t("next_title")),
                para(typo, t("next_steps")),
                muted(typo, pack),
            ]
            .spacing(8),
        ));
    }
    if let Some(log) = &app.ctx.log {
        page = page.push(muted(
            typo,
            &t_fmt("log_at", &[("path", &log.display().to_string())]),
        ));
    }
    page.push(primary(
        typo,
        t("back_to_status"),
        (!app.busy).then_some(Message::Rescan),
    ))
    .into()
}

fn preferences<'a>(app: &'a App, typo: &Typography) -> El<'a> {
    let kit = PreferencesView::new(
        *typo,
        &app.prefs.standard,
        &app.expanded,
        Message::Preference,
        Message::ToggleSection,
    );
    let languages = Lang::ALL.map(|lang| (lang, lang.name().to_string()));
    let content: El<'a> = match app.category {
        0 => kit
            .general()
            .push(widgets::setting_row(
                typo,
                t("language"),
                t("language_desc"),
                widgets::dropdown(typo, languages, &i18n::lang(), Message::Language),
            ))
            .into(),
        1 => kit.appearance().into(),
        2 => kit.accessibility().into(),
        _ => widgets::category_heading(
            typo,
            t(ABOUT_CATEGORY),
            &format!(
                "stellar-bridge-installer {} · GPL-3.0-or-later",
                env!("CARGO_PKG_VERSION")
            ),
        )
        .into(),
    };
    let labels = STANDARD_CATEGORIES
        .iter()
        .map(|key| t(key))
        .chain([t(ABOUT_CATEGORY)]);
    widgets::preferences_page(
        typo,
        widgets::category_list(typo, labels, app.category, Message::Category),
        content,
        Message::TogglePreferences,
    )
}
