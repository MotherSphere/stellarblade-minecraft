//! The window's strings in English (canonical) and French, one file per locale as in Colony. A key missing from both
//! tables falls back to colony-ui's shared labels (themes, Preferences), then renders as itself so the gap shows.
//! Refusals from the core (`ops`, `deploy`, `adapter`, `mods`) stay in English, word for word.

mod en;
mod fr;

use std::sync::RwLock;

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

/// The saved choice, else the environment's `LC_ALL`, `LC_MESSAGES` or `LANG`.
pub fn initial(saved: Option<&str>) -> Lang {
    if let Some(saved) = saved {
        return Lang::from_tag(saved);
    }
    ["LC_ALL", "LC_MESSAGES", "LANG"]
        .iter()
        .filter_map(|var| std::env::var(var).ok())
        .find(|value| !value.is_empty())
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

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
        for source in [include_str!("../app.rs"), include_str!("../ui.rs")] {
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

    #[test]
    fn french_is_used_once_chosen() {
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
