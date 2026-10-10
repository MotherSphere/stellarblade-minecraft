//! The window's strings in English (canonical) and French, one file per locale as in Colony. A key missing from both
//! tables falls back to colony-ui's shared labels (themes, Preferences), then renders as itself so the gap shows.
//! Refusals from the core are typed ([`crate::Refusal`]) and shown from these tables with their path; their raw
//! English text goes only to the log.

mod en;
mod fr;

use std::sync::RwLock;

use crate::{Refusal, ops};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Lang {
    #[default]
    En,
    Fr,
}

impl Lang {
    pub const ALL: [Lang; 2] = [Lang::En, Lang::Fr];

    /// `"fr"`, `"fr_FR.UTF-8"` → French; anything else → English.
    pub fn from_tag(tag: &str) -> Lang {
        let language = tag.split(['-', '_', '.']).next().unwrap_or_default();
        if language.eq_ignore_ascii_case("fr") {
            Lang::Fr
        } else {
            Lang::En
        }
    }

    pub fn tag(self) -> &'static str {
        match self {
            Lang::En => "en",
            Lang::Fr => "fr",
        }
    }

    /// The language's own name, the same in both locales.
    pub fn name(self) -> &'static str {
        match self {
            Lang::En => "English",
            Lang::Fr => "Français",
        }
    }

    fn table(self) -> &'static [(&'static str, &'static str)] {
        match self {
            Lang::En => en::STRINGS,
            Lang::Fr => fr::STRINGS,
        }
    }
}

static ACTIVE: RwLock<Lang> = RwLock::new(Lang::En);

/// Switches the window's language, and colony-ui's shared labels with it.
pub fn set_lang(lang: Lang) {
    colony_ui::i18n::set_locale(colony_ui::i18n::Locale::from_tag(lang.tag()));
    *ACTIVE.write().unwrap_or_else(|e| e.into_inner()) = lang;
}

pub fn lang() -> Lang {
    *ACTIVE.read().unwrap_or_else(|e| e.into_inner())
}

/// The saved choice, else the environment's `LC_ALL`, `LC_MESSAGES` or `LANG`, else the system's language (Windows
/// sets none of these variables).
pub fn initial(saved: Option<&str>) -> Lang {
    if let Some(saved) = saved {
        return Lang::from_tag(saved);
    }
    ["LC_ALL", "LC_MESSAGES", "LANG"]
        .iter()
        .filter_map(|var| std::env::var(var).ok())
        .find(|value| !value.is_empty())
        .or_else(sys_locale::get_locale)
        .map_or(Lang::En, |value| Lang::from_tag(&value))
}

fn find(lang: Lang, key: &str) -> Option<&'static str> {
    lang.table()
        .iter()
        .find(|(k, _)| *k == key)
        .map(|(_, v)| *v)
}

pub fn t(key: &str) -> &'static str {
    find(lang(), key)
        .or_else(|| find(Lang::En, key))
        .unwrap_or_else(|| colony_ui::i18n::t(key))
}

/// [`t`] with `{name}` placeholders replaced.
pub fn t_fmt(key: &str, vars: &[(&str, &str)]) -> String {
    vars.iter().fold(t(key).to_string(), |text, (name, value)| {
        text.replace(&format!("{{{name}}}"), value)
    })
}

/// A key's English string with `{path}` replaced: a [`Refusal`]'s text in the log.
pub(crate) fn english(key: &str, path: &str) -> String {
    find(Lang::En, key).unwrap_or(key).replace("{path}", path)
}

/// A refusal of the core in the window's language, its path kept.
pub fn refusal(refusal: &Refusal) -> String {
    t_fmt(refusal.kind.key(), &[("path", &refusal.subject)])
}

/// An error of the core in the window's language: its refusals, outermost first. An error without one says the log
/// has the details; the raw text belongs there only.
pub fn error(error: &anyhow::Error) -> String {
    // The outermost refusal may be a context, which the chain does not give as a `Refusal`.
    let outer = error.downcast_ref::<Refusal>();
    let inner = error
        .chain()
        .filter_map(|e| e.downcast_ref::<Refusal>())
        .filter(|r| !outer.is_some_and(|o| std::ptr::eq(o, *r)));
    outer
        .into_iter()
        .chain(inner)
        .map(refusal)
        .reduce(|error, cause| t_fmt("err_cause", &[("error", &error), ("cause", &cause)]))
        .unwrap_or_else(|| t("err_unexpected").into())
}

/// The name of an installed piece ([`ops::JAR`], [`ops::LUA`], [`ops::ADAPTER`]).
pub fn piece(name: &str) -> &'static str {
    match name {
        ops::JAR => t("piece_jar"),
        ops::LUA => t("piece_lua"),
        _ => t("piece_adapter"),
    }
}

/// A piece left alone or refused, by name, in the window's language.
pub fn piece_refusal(name: &str, refusal: &Refusal) -> String {
    t_fmt(
        "piece_refusal",
        &[("piece", piece(name)), ("refusal", &self::refusal(refusal))],
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;
    use std::sync::Mutex;

    /// The language is global: the tests that switch it take turns.
    static SWITCH: Mutex<()> = Mutex::new(());

    fn keys(lang: Lang) -> BTreeSet<&'static str> {
        lang.table().iter().map(|(k, _)| *k).collect()
    }

    #[test]
    fn both_locales_have_the_same_keys_once_each() {
        for lang in Lang::ALL {
            assert_eq!(
                keys(lang).len(),
                lang.table().len(),
                "{lang:?} repeats a key"
            );
        }
        assert_eq!(keys(Lang::En), keys(Lang::Fr));
    }

    #[test]
    fn placeholders_match_between_locales() {
        let placeholders = |text: &str| -> BTreeSet<String> {
            text.split('{')
                .skip(1)
                .filter_map(|rest| rest.split_once('}').map(|(name, _)| name.to_string()))
                .collect()
        };
        for (key, english) in en::STRINGS {
            let french = find(Lang::Fr, key).unwrap();
            assert_eq!(placeholders(english), placeholders(french), "{key}");
        }
    }

    /// Every `t("…")` and `t_fmt("…"` in the window's code names a key of the tables.
    #[test]
    fn every_key_the_window_uses_exists() {
        let known = keys(Lang::En);
        let mut used = 0;
        for source in [
            include_str!("../app.rs"),
            include_str!("../ui.rs"),
            include_str!("../main.rs"),
        ] {
            for call in ["t(\"", "t_fmt(\""] {
                for (at, _) in source.match_indices(call) {
                    // `text("…")` is not a lookup.
                    let before = source[..at].chars().next_back().unwrap_or(' ');
                    if before.is_alphanumeric() || before == '_' {
                        continue;
                    }
                    let key = source[at + call.len()..].split('"').next().unwrap();
                    assert!(
                        known.contains(key) || colony_ui::i18n::t(key) != key,
                        "missing string: {key}"
                    );
                    used += 1;
                }
            }
        }
        assert!(used > 50, "the scan found only {used} lookups");
    }

    /// Every refusal kind of the core has its string in both locales, with the path where English shows one.
    #[test]
    fn every_refusal_kind_has_both_strings() {
        for kind in crate::Kind::ALL {
            let english = find(Lang::En, kind.key()).unwrap_or_default();
            let french = find(Lang::Fr, kind.key()).unwrap_or_default();
            assert!(!english.is_empty() && !french.is_empty(), "{kind:?}");
            assert_ne!(english, french, "{kind:?} is not translated");
        }
        let foreign = Refusal::new(crate::Kind::ForeignJar, "/mods/stellar-bridge-0.1.0.jar");
        assert_eq!(
            foreign.to_string(),
            "Refusing a bridge JAR this installer did not install: /mods/stellar-bridge-0.1.0.jar"
        );
        let _turn = SWITCH.lock().unwrap_or_else(|e| e.into_inner());
        set_lang(Lang::Fr);
        let shown = refusal(&foreign);
        let bare = error(&anyhow::Error::from(foreign.clone()));
        let wrapped = anyhow::Error::from(foreign.clone())
            .context("raw")
            .context(Refusal::new(crate::Kind::InstallRestored, ""));
        let chained = error(&wrapped);
        let untyped = error(&anyhow::anyhow!("No such file or directory"));
        set_lang(Lang::En);
        assert!(shown.contains("/mods/stellar-bridge-0.1.0.jar") && !shown.contains("Refusing"));
        assert_eq!(bare, shown, "shown once");
        assert!(
            chained.ends_with(&shown) && !chained.contains("raw"),
            "{chained}"
        );
        assert_eq!(untyped, find(Lang::Fr, "err_unexpected").unwrap());
    }

    #[test]
    fn french_is_used_once_chosen() {
        let _turn = SWITCH.lock().unwrap_or_else(|e| e.into_inner());
        set_lang(Lang::Fr);
        assert_eq!(t("start"), "Commencer");
        assert_eq!(
            t_fmt("done_ok", &[("action", "Installer")]),
            "Installer : terminé"
        );
        // Shared labels follow: colony-ui's theme names.
        assert_eq!(
            t("settings_cat_appearance"),
            colony_ui::i18n::t_in(colony_ui::i18n::Locale::Fr, "settings_cat_appearance")
        );
        set_lang(Lang::En);
        assert_eq!(t("start"), "Start");
    }

    #[test]
    fn language_tags() {
        assert_eq!(Lang::from_tag("fr_FR.UTF-8"), Lang::Fr);
        assert_eq!(Lang::from_tag("en_GB"), Lang::En);
        assert_eq!(Lang::from_tag(""), Lang::En);
        assert_eq!(initial(Some("fr")), Lang::Fr);
    }
}
