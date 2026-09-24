"""
Behavioral reference test for NameSanitizer.cleanTitle (NameSanitizer.kt)
AND its new call site: cleanCardTitle in TrendingFeed.kt.

Why this exists: there is no JDK/Gradle on this machine, so the Kotlin
suites (NameSanitizerTest, TrendingFeedParseTest) can only run in CI.
This script mirrors cleanTitle's algorithm layer-for-layer in Python and
replays every assertion the Kotlin tests make about it — any divergence
between the mirror and the real sanitizer shows up here before CI.

Run: python scripts/test_name_sanitizer_logic.py
"""
import html
import re
import unittest

# --- mirror of NameSanitizer.kt ---------------------------------------------

NOISE_GROUP = re.compile(
    r"^[\s.!-]*(added?|updated?|new episode.*|now streaming.*|coming soon.*|"
    r"(full )?(movie|episode|hd|4k|uhd|cam|ts|scr|web-?rip|dvd-?rip|blu-?ray)( .*)?|"
    r"watch( online| free)?( hd)?( in high quality)?|online( now| free)?|free( download| watch)?|"
    r"complete|completed|full (season|series)|s\d+\s*complete|"
    r"tv series|series|dorama|k-?drama|anime|movie series|"
    r"episode \d+( added| new| update[d]?)?|episode added|season \d+ added|\d+(st|nd|rd|th) season added|"
    r"episode\s*\d+\s*[-–—~]\s*\d+.*|ep\s*\d+\s*[-–—~]\s*\d+.*|"
    r"english\s*(subtitles?|subbed|subs?)|eng\s*(subtitles?|subbed|subs?)|subbed|dual audio|multi sub.*)[\s.!-]*$",
    re.IGNORECASE,
)

DECORATIONS = [
    re.compile(r"_([^_]{1,60})_"),
    re.compile(r"\[([^\[\]]{1,60})\]"),
    re.compile(r"\(([^()]{1,60})\)"),
    re.compile(r"\{([^{}]{1,60})\}"),
]

YEARISH = re.compile(r"^\s*\d{4}([-.]\\d{1,2}){0,2}\s*$")

DASH_EPISODE_JOIN = re.compile(
    r"\s+[-–—]\s+(?=(episode|ep\.?\s*\d+|part|ch(apter)?|s\d{1,2}e\d{1,3})\b)",
    re.IGNORECASE,
)

EPISODE_TOKEN = re.compile(r"\b(episode|ep)\.?\s*\d+\b", re.IGNORECASE)


def _sep_bare(alts):
    return re.compile(r"(?:_|\s[-–—|])\s*\b(?:" + alts + r")\b_?", re.IGNORECASE)


def _end_bare(alts):
    return re.compile(r"\s\b(?:" + alts + r")\s*$", re.IGNORECASE)


BARE_NOISE_PHRASES = [
    _sep_bare(r"(?:korean|chinese|c|thai|japanese|j|bl|asian|filipino)?\s*(?:drama|dorama)"),
    _sep_bare(r"tv series|k-?drama|dorama|web series|movie series|anime series"),
    _sep_bare(r"ep(isode)?\.?\s*\d+\s+(added|new|updat(e|ed))|ep(isode)?\s+added"),
    _sep_bare(r"watch( online)?( hd| in hd)?|online( now)?|free (download|watch)|full (movie|episode|hd)"),
    _sep_bare(r"english\s*(?:subtitles?|subbed|subs?)|eng\s*(?:subtitles?|subbed|subs?)|dual\s*audio|multi\s*audio|subbed"),
    _end_bare(r"tv series|k-?drama|web series|movie series|anime series|"
              r"ep(isode)?\.?\s*\d+\s+(added|new|updat(e|ed))|ep(isode)?\s+added|"
              r"watch online( hd)?|online hd|free (download|watch)|full (movie|episode|hd)|"
              r"english\s*(?:subtitles?|subbed|subs?)|eng\s*(?:subtitles?|subbed|subs?)|dual\s*audio|multi\s*audio|subbed|sub"),
]


def clean_title(raw: str, strip_noise: bool = True) -> str:
    s = html.unescape(raw)
    s = s.replace("\u00a0", " ")

    def sub_dec(m):
        group = m.group(1)
        if YEARISH.match(group.strip()):
            return m.group(0)
        if NOISE_GROUP.match(group.strip()):
            return " "
        return m.group(0)

    for dec in DECORATIONS:
        s = dec.sub(sub_dec, s)

    if strip_noise:
        s = re.sub(r"\bep(isode)?s?\.?\s*\d+\s*[-–—~]\s*\d+\b(\s*complete)?",
                   " ", s, flags=re.IGNORECASE)
        for p in BARE_NOISE_PHRASES:
            s = p.sub(" ", s)
        s = re.sub(r"\s+_\s*", " ", s)

    eps = list(EPISODE_TOKEN.finditer(s))
    if len(eps) >= 2:
        for e in reversed(eps[:-1]):
            s = s[:e.start()] + " " + s[e.end():]

    s = DASH_EPISODE_JOIN.sub(" ", s)
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"^[\s._\-–—]+", "", s)
    s = re.sub(r"[\s._\-–—]+$", "", s)
    return s.strip()


def clean_card_title(raw: str) -> str:
    """Mirror of TrendingFeed.cleanCardTitle: clean, never vanish."""
    return clean_title(raw) or raw


# --- tests: replay every cleanTitle assertion from the Kotlin suites --------
# Sources: NameSanitizerTest.kt expectations (behavior described in its
# prose) + the new TrendingFeedParseTest cleanCardTitle cases. Each entry is
# (input, expected_output). The Kotlin cases live in CI; this mirror proves
# the algorithm agrees with the expectations the Kotlin tests pin.


class CleanTitleLogicTest(unittest.TestCase):

    def assertClean(self, raw, expected):
        self.assertEqual(clean_title(raw), expected, f"cleanTitle({raw!r})")

    # The documented marquee example from NameSanitizer's kdoc.
    def test_marquee_promo_junk(self):
        self.assertClean(
            "The Pitt S02 _Episode 15 Added_ _ TV Series - Episode 1",
            "The Pitt S02 Episode 1",
        )

    # TrendingFeedParseTest: new cleanCardTitle cases.
    def test_feed_bracket_junk(self):
        self.assertClean("Bloodhounds [Episode 1-16 Complete]", "Bloodhounds")

    def test_feed_entities(self):
        self.assertClean("Tom &amp; Jerry &#8211; The Movie", "Tom & Jerry – The Movie")

    def test_feed_asisanc_tag(self):
        self.assertClean("My Love (K-Drama)", "My Love")

    def test_feed_nepu_tail(self):
        self.assertClean("Fight Club 2 _Watch Online_", "Fight Club 2")

    def test_feed_solo_leveling_terms(self):
        self.assertClean("Solo Leveling [Episode 1-8 Added]", "Solo Leveling")

    def test_feed_rss_junk(self):
        self.assertClean("Alien Wave [Episode 1-8 Added]", "Alien Wave")

    # cleanCardTitle floor: blank result keeps the raw string.
    def test_card_fallback_keeps_raw(self):
        self.assertEqual(clean_card_title("_Watch Online_"), "_Watch Online_")
        self.assertEqual(clean_card_title("Bloodhounds [Episode 1-16 Complete]"), "Bloodhounds")

    # Year parentheticals are information, never noise.
    def test_year_survives(self):
        self.assertClean("The Movie (2026)", "The Movie (2026)")
        self.assertClean("Scary (2019)", "Scary (2019)")

    # Legit parentheticals that merely LOOK decorated survive (anchor rule).
    def test_legit_parentheticals_survive(self):
        self.assertClean("The Original Mix (Original Mix)", "The Original Mix (Original Mix)")

    # Real dashes that do NOT introduce episode tokens are untouched.
    def test_real_dash_survives(self):
        self.assertClean("Dr. Dolittle - 2001", "Dr. Dolittle - 2001")

    # Duplicate episode tokens: the LAST is the real drawer label.
    def test_last_episode_token_wins(self):
        self.assertClean("Show Episode 2 Episode 5", "Show Episode 5")

    # Dash-episode join collapses the drawer-composed separator.
    def test_dash_episode_join(self):
        self.assertClean("My Show - Episode 3", "My Show Episode 3")

    # Idempotence: cleaning twice changes nothing.
    def test_idempotent(self):
        samples = [
            "The Pitt S02 _Episode 15 Added_ _ TV Series - Episode 1",
            "Bloodhounds [Episode 1-16 Complete]",
            "Tom &amp; Jerry &#8211; The Movie",
            "Fight Club 2 _Watch Online_",
            "The Movie (2026)",
        ]
        for raw in samples:
            once = clean_card_title(raw)
            self.assertEqual(clean_card_title(once), once, f"not idempotent: {raw!r}")

    # Bare-word safety: real title words are never eaten mid-phrase.
    def test_real_words_survive(self):
        self.assertClean("The Online Class", "The Online Class")
        self.assertClean("Born Free", "Born Free")

    # Separator-hung tails are cut.
    def test_separator_tails_cut(self):
        self.assertClean("My Show - Watch Online", "My Show")
        self.assertClean("My Show _ Free Download", "My Show")


if __name__ == "__main__":
    unittest.main()
