"""Unit test suite for Scoped C-Drama, K-Drama, and Western TV Federated Episode Failover.
Verifies:
1. Source-level architecture wiring and invariant guards in DownloadEngine.kt.
2. Behavioral model of Asian and Western cluster routing.
3. 4-layer 'Suits' remake guard (Cluster boundary, Year, Country, Season & Episode).
4. Progress banking guard (downloadedBytes == 0 && bytesLanded == 0).
5. Atomic pause guard and quality retention.

Run: python scripts/test_federated_failover.py
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE_PATH = ROOT / "app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt"


# ==============================================================================
# Behavioral Reference Model of DownloadEngine Failover Logic
# ==============================================================================

ASIAN_DRAMA_CLUSTER = {"asianc", "dramarain", "dramakey", "pluto", "nepu"}
WESTERN_CLUSTER = {"nkiri", "9jarocks", "naijavault", "naijaprey"}
DEDICATED_ASIAN_SITES = {"asianc", "dramarain", "dramakey"}
# Proven mixed-site secondaries (coverage probe 2026-09-20, search-only over
# 50 random Asian series: 9jarocks 98%, nkiri 62%, 6/6 rescue rate on titles
# all dedicated sites missed). Asian tasks may reach them at EQUAL rank.
SECONDARY_ASIAN_FALLBACK = {"nkiri", "9jarocks"}
YEAR_REGEX = re.compile(r"\b(19\d\d|20[0-3]\d)\b")
PART_REGEX = re.compile(r"\b(?:part|chapter|pt)[\s._-]?(\d+|[ivx]+)\b", re.I)


class DramaCountry:
    KOREAN = "KOREAN"
    CHINESE = "CHINESE"
    JAPANESE = "JAPANESE"
    THAI = "THAI"
    TAIWANESE = "TAIWANESE"
    WESTERN = "WESTERN"
    UNKNOWN = "UNKNOWN"


def extract_year(text: str):
    if not text:
        return None
    m = YEAR_REGEX.search(text)
    return int(m.group(1)) if m else None


def extract_country(text: str, site: str = "") -> str:
    lower = text.lower()
    if any(k in lower for k in ("korean", "k-drama", "kdrama", "k drama", "korea")):
        return DramaCountry.KOREAN
    if any(k in lower for k in ("chinese", "c-drama", "cdrama", "c drama", "china")):
        return DramaCountry.CHINESE
    if any(k in lower for k in ("japanese", "j-drama", "jdrama", "j drama", "japan")):
        return DramaCountry.JAPANESE
    if any(k in lower for k in ("taiwanese", "tw-drama", "taiwan")):
        return DramaCountry.TAIWANESE
    if any(k in lower for k in ("thai", "thailand", "lakorn", "th-drama")):
        return DramaCountry.THAI
    if any(k in lower for k in ("western", "hollywood", "american", "nollywood")):
        return DramaCountry.WESTERN
    if site.lower() in WESTERN_CLUSTER:
        return DramaCountry.WESTERN
    return DramaCountry.UNKNOWN


def extract_explicit_country(text: str) -> str:
    """Keyword-only country read — same tag vocabulary as extract_country,
    WITHOUT the site-default tail. Mirror of DownloadEngine.extractExplicitCountry."""
    lower = text.lower()
    if any(k in lower for k in ("korean", "k-drama", "kdrama", "k drama", "korea")):
        return DramaCountry.KOREAN
    if any(k in lower for k in ("chinese", "c-drama", "cdrama", "c drama", "china")):
        return DramaCountry.CHINESE
    if any(k in lower for k in ("japanese", "j-drama", "jdrama", "j drama", "japan")):
        return DramaCountry.JAPANESE
    if any(k in lower for k in ("taiwanese", "tw-drama", "taiwan")):
        return DramaCountry.TAIWANESE
    if any(k in lower for k in ("thai", "thailand", "lakorn", "th-drama")):
        return DramaCountry.THAI
    if any(k in lower for k in ("western", "hollywood", "american", "nollywood")):
        return DramaCountry.WESTERN
    return DramaCountry.UNKNOWN


def extract_failover_country(is_asian: bool, text: str, site: str = "") -> str:
    """Layer-3 country read for cross-provider failover. Mirror of
    DownloadEngine.extractFailoverCountry: an Asian task reading a
    mixed-site secondary skips the WESTERN site default (untagged text is
    UNKNOWN there and never rejects); every other case is plain
    extract_country."""
    if is_asian and site.lower() in SECONDARY_ASIAN_FALLBACK:
        return extract_explicit_country(text)
    return extract_country(text, site)


def is_asian_drama_context(site: str, show_title: str, ep_title: str = "", source_url: str = "") -> bool:
    site_low = site.lower()
    if site_low in DEDICATED_ASIAN_SITES:
        return True
    combined = f"{show_title} {ep_title} {source_url}"
    country = extract_country(combined, site)
    if country in (DramaCountry.KOREAN, DramaCountry.CHINESE, DramaCountry.JAPANESE, DramaCountry.THAI, DramaCountry.TAIWANESE):
        return True
    if site_low in ASIAN_DRAMA_CLUSTER and site_low not in WESTERN_CLUSTER:
        return True
    return False


def parse_season_and_episode(site: str, episode_num: int, show_title: str, ep_title: str = "", source_url: str = ""):
    is_asian = is_asian_drama_context(site, show_title, ep_title, source_url)
    if site.lower() == "9jarocks" and episode_num >= 100:
        return (episode_num // 100, episode_num % 100)
    if episode_num >= 100 and not is_asian:
        return (episode_num // 100, episode_num % 100)

    combined = f"{show_title} {ep_title} {source_url}".lower()
    s_match = re.search(r"(?:s|season)[\s._-]?0*(\d{1,2})\b", combined)
    season = int(s_match.group(1)) if s_match else 1

    if episode_num > 0:
        ep = episode_num
    else:
        e_match = re.search(r"(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b", combined)
        ep = int(e_match.group(1)) if e_match else 0

    return (season, ep)


def parse_candidate_season_and_episode(cand_site: str, cand_title: str, ep_num: int, ep_title: str = "", ep_url: str = ""):
    if cand_site.lower() == "9jarocks" and ep_num >= 100:
        return (ep_num // 100, ep_num % 100)

    combined = f"{cand_title} {ep_title} {ep_url}".lower()
    s_match = re.search(r"(?:s|season)[\s._-]?0*(\d{1,2})\b", combined)
    season = int(s_match.group(1)) if s_match else 1

    if ep_num > 0:
        if ep_num >= 100 and cand_site.lower() not in ASIAN_DRAMA_CLUSTER:
            ep = ep_num % 100
        else:
            ep = ep_num
    else:
        e_match = re.search(r"(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b", combined)
        ep = int(e_match.group(1)) if e_match else 0

    return (season, ep)


def title_matches(query: str, candidate: str) -> bool:
    norm_card = "".join(c for c in candidate.lower() if c.isalnum())
    if not norm_card:
        return False
    stopwords = {
        "the", "a", "an", "is", "of", "and", "to", "in", "on", "le", "la", "de",
        "movie", "film", "series", "complete", "season", "episode", "download",
        "full", "hd", "hindi", "english", "dubbed", "subbed", "korean", "chinese",
        "drama", "web", "dl", "webdl", "bluray", "brrip", "dvdrip", "hevc", "x264",
        "x265", "aac", "480p", "720p", "1080p", "2160p", "4k"
    }
    words = [w for w in re.findall(r"[a-z0-9]+", query.lower())
             if len(w) >= 2 and w not in stopwords and not w.isdigit()]
    if not words:
        return False
    if not all(w in norm_card for w in words):
        return False

    # Layer 2: Year check
    q_year = extract_year(query)
    c_year = extract_year(candidate)
    if q_year is not None and c_year is not None and q_year != c_year:
        return False

    # Sequel / Part check
    q_part = PART_REGEX.search(query.lower())
    c_part = PART_REGEX.search(candidate.lower())
    if q_part and c_part and q_part.group(1) != c_part.group(1):
        return False

    return True


def evaluate_failover_candidate(
    orig_site: str,
    orig_show_title: str,
    orig_ep_title: str,
    orig_ep_num: int,
    cand_site: str,
    cand_show_title: str,
    cand_year_str: str,
    cand_category: str,
    cand_total_episodes: int,
    cand_episodes: list  # list of (ep_num, ep_title, ep_url)
) -> bool:
    """Evaluates whether candidate passes all 4 layers of remake guard."""
    is_asian = is_asian_drama_context(orig_site, orig_show_title, orig_ep_title)
    cand_site_low = cand_site.lower()

    # Layer 1: Genre / Cluster Boundary Guard. Asian tasks reach the Asian
    # cluster PLUS the proven mixed-site secondaries (nkiri/9jarocks); the
    # Western one-way door (never query dedicated Asian sites) stays shut.
    if is_asian:
        if cand_site_low not in ASIAN_DRAMA_CLUSTER and cand_site_low not in SECONDARY_ASIAN_FALLBACK:
            return False
    else:
        if cand_site_low not in WESTERN_CLUSTER or cand_site_low in DEDICATED_ASIAN_SITES:
            return False

    # Title match (includes year and sequel guard)
    query = orig_show_title if orig_show_title else orig_ep_title
    if not title_matches(query, cand_show_title):
        return False

    # Layer 2: Release Year Matching
    orig_year = extract_year(orig_show_title) or extract_year(orig_ep_title)
    cand_year = extract_year(cand_year_str) or extract_year(cand_show_title)
    if orig_year is not None and cand_year is not None and orig_year != cand_year:
        return False

    # Layer 3: Country Tag Matching. Mixed-site secondaries prove country by
    # explicit tag only — untagged text is UNKNOWN there, never a rejection.
    orig_country = extract_country(f"{orig_show_title} {orig_ep_title}", orig_site)
    cand_country = extract_failover_country(is_asian, f"{cand_show_title} {cand_category}", cand_site)
    if orig_country != DramaCountry.UNKNOWN and cand_country != DramaCountry.UNKNOWN and orig_country != cand_country:
        return False

    orig_season, orig_ep = parse_season_and_episode(orig_site, orig_ep_num, orig_show_title, orig_ep_title)
    if orig_ep > 0:
        # Layer 4 pre-check
        if cand_total_episodes > 0 and cand_total_episodes < orig_ep:
            return False

        # Layer 4: Season & Episode Boundary Verification
        if len(cand_episodes) < orig_ep:
            return False

        matching_ep = False
        for c_ep_num, c_ep_title, c_ep_url in cand_episodes:
            cand_s, cand_e = parse_candidate_season_and_episode(cand_site, cand_show_title, c_ep_num, c_ep_title, c_ep_url)
            if cand_s == orig_season and cand_e == orig_ep:
                matching_ep = True
                break
        if not matching_ep:
            return False

    return True


# ==============================================================================
# Unit Test Cases
# ==============================================================================

class FederatedFailoverTestSuite(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.source = ENGINE_PATH.read_text(encoding="utf-8")

    # --------------------------------------------------------------------------
    # 1. Source Code Architecture & Invariant Checks
    # --------------------------------------------------------------------------

    def test_progress_banking_guard_in_engine(self):
        """Invariant: failover MUST only run if task.downloadedBytes == 0L && bytesLanded(task) == 0L."""
        self.assertIn("if (task.downloadedBytes > 0L || bytesLanded(task) > 0L)", self.source)
        self.assertIn("failover: skipped because task already banked", self.source)

    def test_cluster_definitions_in_engine(self):
        """Verify cluster sets and dedicated Asian site constants exist."""
        self.assertIn('private val ASIAN_DRAMA_CLUSTER = setOf("asianc", "dramarain", "dramakey", "pluto", "nepu")', self.source)
        self.assertIn('private val WESTERN_CLUSTER = setOf("nkiri", "9jarocks", "naijavault", "naijaprey")', self.source)
        self.assertIn('private val DEDICATED_ASIAN_SITES = setOf("asianc", "dramarain", "dramakey")', self.source)

    def test_drama_country_enum_in_engine(self):
        """Verify DramaCountry enum with all 7 entries."""
        for country in ("KOREAN", "CHINESE", "JAPANESE", "THAI", "TAIWANESE", "WESTERN", "UNKNOWN"):
            self.assertIn(country, self.source)

    def test_year_regex_portable(self):
        """Verify 4-digit year regex 1900..2039 is declared."""
        self.assertIn(r'Regex("""\b(19\d\d|20[0-3]\d)\b""")', self.source)

    def test_atomic_pause_guard_before_deleting_partials(self):
        """Verify task.userPaused is checked inside repository.update before any partial delete."""
        self.assertIn("if (current.userPaused)", self.source)
        self.assertIn("if (wasUserPaused)", self.source)
        self.assertIn("failover: aborted because task was paused by user", self.source)

    def test_preserve_user_quality_in_engine(self):
        """Verify user quality is preserved across failover instead of reset to null."""
        self.assertIn("quality = current.quality,", self.source)
        self.assertNotIn("quality = null,", self.source[self.source.find("attemptCrossProviderFailover"):self.source.find("attemptCrossProviderFailover") + 5000])

    def test_dedicated_asian_sites_query_guard_in_engine(self):
        """Invariant: Asian drama failover MUST allow searching dedicated Asian sites and extract country from sourceUrl."""
        self.assertIn("(isAsian || pName !in DEDICATED_ASIAN_SITES)", self.source)
        self.assertIn('val origCountry = extractCountry("${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}", task.site)', self.source)

    # --------------------------------------------------------------------------
    # 2. Progress Banking Guard Behavioral Tests
    # --------------------------------------------------------------------------

    def test_progress_banking_guard_behavior(self):
        def can_failover(downloaded_bytes: int, landed_bytes: int) -> bool:
            return not (downloaded_bytes > 0 or landed_bytes > 0)

        self.assertTrue(can_failover(0, 0), "Task with 0 bytes banked should be allowed to fail over")
        self.assertFalse(can_failover(1024, 1024), "Task with downloaded bytes > 0 must abort failover")
        self.assertFalse(can_failover(0, 512), "Task with landed bytes on disk must abort failover")
        self.assertFalse(can_failover(1024, 0), "Task with downloaded bytes must abort failover")

    # --------------------------------------------------------------------------
    # 3. Asian & Western Cluster Routing
    # --------------------------------------------------------------------------

    def test_asian_cluster_routing(self):
        """Asian drama task must only route within ASIAN_DRAMA_CLUSTER."""
        # Dedicated Asian sites are always Asian context
        for site in ("asianc", "dramarain", "dramakey"):
            self.assertTrue(is_asian_drama_context(site, "Suits"), f"{site} must be recognized as Asian drama context")

        # Western sites are never Asian context unless tagged
        for site in ("nkiri", "9jarocks", "naijavault", "naijaprey"):
            self.assertFalse(is_asian_drama_context(site, "Suits"), f"{site} 'Suits' must be recognized as Western")

        # Explicit Asian tag on Western site routes to Asian cluster
        self.assertTrue(is_asian_drama_context("9jarocks", "Suits (Korean Drama)"))
        self.assertTrue(is_asian_drama_context("nkiri", "Hidden Love [Chinese Drama]"))

    def test_western_cluster_routing(self):
        """Western series must never route to dedicated Asian sites."""
        # Western Suits S01E03 on Nkiri
        cand_asian = evaluate_failover_candidate(
            orig_site="nkiri",
            orig_show_title="Suits",
            orig_ep_title="Episode 3",
            orig_ep_num=3,
            cand_site="asianc",
            cand_show_title="Suits",
            cand_year_str="2018",
            cand_category="Drama",
            cand_total_episodes=16,
            cand_episodes=[(3, "Episode 3", "https://asianc.to/ep3")]
        )
        self.assertFalse(cand_asian, "Western Suits on Nkiri must NEVER failover to AsianC")

        # Western Suits S01E03 on Nkiri -> 9jaRocks S01E03 (Rocks ep 103)
        cand_rocks = evaluate_failover_candidate(
            orig_site="nkiri",
            orig_show_title="Suits Season 1",
            orig_ep_title="Episode 3",
            orig_ep_num=3,
            cand_site="9jarocks",
            cand_show_title="Suits Season 1",
            cand_year_str="2011",
            cand_category="Series",
            cand_total_episodes=12,
            cand_episodes=[
                (101, "Suits S01E01", "https://9jarocks.net/ep101"),
                (102, "Suits S01E02", "https://9jarocks.net/ep102"),
                (103, "Suits S01E03", "https://9jarocks.net/ep103")
            ]
        )
        self.assertTrue(cand_rocks, "Western Suits S01E03 on Nkiri should successfully failover to 9jaRocks ep 103")

    # --------------------------------------------------------------------------
    # 4. 4-Layer "Suits" Remake Guard Tests
    # --------------------------------------------------------------------------

    def test_layer1_genre_cluster_boundary(self):
        """Layer 1: cluster boundary with the proven mixed-site secondaries.

        Asian tasks reach the Asian cluster PLUS nkiri/9jarocks (coverage
        probe 2026-09-20): an untagged "Suits" on asianc IS rescuable from
        nkiri now — the old hard-reject would strand it. The Western
        one-way door (never query dedicated Asian sites) is unchanged.
        """
        # Asian Suits on AsianC failing over to Nkiri: rescued (was a
        # hard reject before the secondary-fallback expansion).
        cand_nkiri = evaluate_failover_candidate(
            orig_site="asianc",
            orig_show_title="Suits",
            orig_ep_title="Episode 1",
            orig_ep_num=1,
            cand_site="nkiri",
            cand_show_title="Suits",
            cand_year_str="2011",
            cand_category="Series",
            cand_total_episodes=16,
            cand_episodes=[(1, "Episode 1", "https://nkiri.com/ep1")]
        )
        self.assertTrue(cand_nkiri, "Untagged Asian Suits must be rescuable from Nkiri")
        # Explicitly Western-tagged candidate still loses to the Asian task.
        cand_western_tag = evaluate_failover_candidate(
            orig_site="asianc",
            orig_show_title="Suits (Korean Drama)",
            orig_ep_title="Episode 1",
            orig_ep_num=1,
            cand_site="nkiri",
            cand_show_title="Suits (Hollywood)",
            cand_year_str="2011",
            cand_category="Series",
            cand_total_episodes=16,
            cand_episodes=[(1, "Suits S01E01", "https://nkiri.com/ep1")]
        )
        self.assertFalse(cand_western_tag, "Hollywood tag must still block the match")

    def test_layer2_release_year_matching(self):
        """Layer 2: Reject remake / sequel when both declare years and they differ."""
        # Suits 2011 (US) vs Suits 2018 (Korean remake)
        self.assertFalse(
            evaluate_failover_candidate(
                orig_site="nkiri",
                orig_show_title="Suits (2011)",
                orig_ep_title="Episode 1",
                orig_ep_num=1,
                cand_site="naijavault",
                cand_show_title="Suits 2018",
                cand_year_str="2018",
                cand_category="Series",
                cand_total_episodes=16,
                cand_episodes=[(1, "Episode 1", "https://naijavault.com/ep1")]
            ),
            "Year mismatch (2011 vs 2018) must be rejected"
        )

        # Scream 1996 vs Scream 2022
        self.assertFalse(title_matches("Scream 1996", "Scream 2022"))
        self.assertTrue(title_matches("Scream 1996", "Scream 1996"))

        # Sequel protection: Dune Part Two vs Dune Part One
        self.assertFalse(title_matches("Dune Part Two", "Dune Part One"))
        self.assertFalse(title_matches("Dune Part 2", "Dune Part 1"))
        self.assertTrue(title_matches("Dune Part 2", "Dune Part 2"))

    def test_layer3_country_tag_matching(self):
        """Layer 3: Reject when both have known countries and they differ."""
        self.assertEqual(extract_country("Suits [Korean Drama]"), DramaCountry.KOREAN)
        self.assertEqual(extract_country("Suits", "nkiri"), DramaCountry.WESTERN)
        self.assertEqual(extract_country("The Untamed [Chinese Drama]"), DramaCountry.CHINESE)
        self.assertEqual(extract_country("Alice in Borderland [Japanese Drama]"), DramaCountry.JAPANESE)

        # Korean Suits vs Chinese drama candidate
        cand_chinese = evaluate_failover_candidate(
            orig_site="asianc",
            orig_show_title="Suits [Korean Drama]",
            orig_ep_title="Episode 1",
            orig_ep_num=1,
            cand_site="dramarain",
            cand_show_title="Suits [Chinese Drama]",
            cand_year_str="2018",
            cand_category="Drama",
            cand_total_episodes=16,
            cand_episodes=[(1, "Episode 1", "https://dramarain.me/ep1")]
        )
        self.assertFalse(cand_chinese, "Korean original must reject Chinese candidate")

    def test_layer4_season_and_episode_boundary(self):
        """Layer 4: Season decomposition and total episodes check."""
        # 9jaRocks S02E03 is encoded as 203
        s, e = parse_season_and_episode("9jarocks", 203, "Suits")
        self.assertEqual((s, e), (2, 3), "Rocks 203 must decompose to Season 2 Episode 3")

        # Total episodes check: US Suits Episode 18 vs Korean remake with only 16 episodes
        cand_too_few = evaluate_failover_candidate(
            orig_site="nkiri",
            orig_show_title="Suits",
            orig_ep_title="Episode 18",
            orig_ep_num=18,
            cand_site="naijavault",
            cand_show_title="Suits",
            cand_year_str="",
            cand_category="Series",
            cand_total_episodes=16,
            cand_episodes=[(i, f"Episode {i}", f"https://nv.com/ep{i}") for i in range(1, 17)]
        )
        self.assertFalse(cand_too_few, "Candidate with 16 episodes must be rejected for Episode 18 request")

        # Wrong season check: S02E03 request must reject Season 1 candidate
        cand_wrong_season = evaluate_failover_candidate(
            orig_site="9jarocks",
            orig_show_title="Suits Season 2",
            orig_ep_title="Episode 3",
            orig_ep_num=203,
            cand_site="nkiri",
            cand_show_title="Suits Season 1",
            cand_year_str="2011",
            cand_category="Series",
            cand_total_episodes=12,
            cand_episodes=[(i, f"Episode {i}", f"https://nkiri.com/s01e0{i}") for i in range(1, 13)]
        )
        self.assertFalse(cand_wrong_season, "Season 2 request must reject Season 1 candidate")

        # Correct season check: S02E03 request matches Season 2 Episode 3
        cand_correct_season = evaluate_failover_candidate(
            orig_site="9jarocks",
            orig_show_title="Suits Season 2",
            orig_ep_title="Episode 3",
            orig_ep_num=203,
            cand_site="nkiri",
            cand_show_title="Suits Season 2",
            cand_year_str="2012",
            cand_category="Series",
            cand_total_episodes=16,
            cand_episodes=[(i, f"Suits S02E0{i}", f"https://nkiri.com/s02e0{i}") for i in range(1, 17)]
        )
        self.assertTrue(cand_correct_season, "Season 2 request should match Season 2 Episode 3 candidate")


if __name__ == "__main__":
    unittest.main()


    # --------------------------------------------------------------------------
    # 5. Secondary Asian fallback (nkiri / 9jarocks, coverage probe 2026-09-20)
    # --------------------------------------------------------------------------

    def test_asian_task_reaches_nkiri_and_9jarocks(self):
        """Asian tasks may fail over onto the proven mixed-site secondaries."""
        for site, ep in (("nkiri", 5), ("9jarocks", 105)):
            cand = evaluate_failover_candidate(
                orig_site="asianc",
                orig_show_title="Queen of Tears",
                orig_ep_title="Episode 5",
                orig_ep_num=5,
                cand_site=site,
                cand_show_title="Queen of Tears",
                cand_year_str="2024",
                cand_category="Series",
                cand_total_episodes=16,
                cand_episodes=[(ep, "Queen of Tears Episode 5", f"https://{site}/ep5")],
            )
            self.assertTrue(cand, f"Asian task must reach {site} secondary")

    def test_secondary_rescues_untagged_nkiri_card(self):
        """Untagged nkiri K-drama card: no WESTERN site-default poisoning."""
        self.assertEqual(
            extract_failover_country(True, "Queen of Tears Series", "nkiri"),
            DramaCountry.UNKNOWN,
        )
        # ...while the same untagged card is still WESTERN under the plain read.
        self.assertEqual(
            extract_country("Queen of Tears Series", "nkiri"),
            DramaCountry.WESTERN,
        )
        cand = evaluate_failover_candidate(
            orig_site="asianc",
            orig_show_title="Queen of Tears (Korean Drama)",
            orig_ep_title="Episode 5",
            orig_ep_num=5,
            cand_site="nkiri",
            cand_show_title="Queen of Tears",
            cand_year_str="2024",
            cand_category="Series",
            cand_total_episodes=16,
            cand_episodes=[(5, "Queen of Tears Episode 5", "https://nkiri/ep5")],
        )
        self.assertTrue(cand, "Untagged nkiri card must NOT be country-rejected")

    def test_secondary_explicit_western_tag_still_rejects(self):
        """Explicit Hollywood tag on a mixed site still loses to a Korean task."""
        cand = evaluate_failover_candidate(
            orig_site="asianc",
            orig_show_title="Suits (Korean Drama)",
            orig_ep_title="Episode 1",
            orig_ep_num=1,
            cand_site="9jarocks",
            cand_show_title="Suits (Hollywood)",
            cand_year_str="2011",
            cand_category="Series",
            cand_total_episodes=16,
            cand_episodes=[(101, "Suits S01E01", "https://9jarocks/ep101")],
        )
        self.assertFalse(cand, "Hollywood-tagged card must lose to a Korean task")

    def test_secondary_western_door_stays_shut(self):
        """Western tasks still never touch dedicated Asian sites."""
        for dedicated in ("asianc", "dramarain", "dramakey"):
            cand = evaluate_failover_candidate(
                orig_site="nkiri",
                orig_show_title="Suits",
                orig_ep_title="Episode 3",
                orig_ep_num=3,
                cand_site=dedicated,
                cand_show_title="Suits",
                cand_year_str="2011",
                cand_category="Drama",
                cand_total_episodes=16,
                cand_episodes=[(3, "Episode 3", f"https://{dedicated}/ep3")],
            )
            self.assertFalse(cand, f"Western task must NEVER reach {dedicated}")

    def test_secondary_tagged_card_outranks_untagged(self):
        """Probe-backed ranking: an explicitly-tagged same-country card must
        sort (probe-for-load-episodes order) ahead of an untagged twin —
        70% of real nkiri cards carry tags, so the likely-right card goes
        first without rejecting anything.
        """
        def order_key(card_title, card_site, norm_query, proven, orig_country):
            cand = extract_failover_country(True, card_title, card_site)
            return (
                normalize_title(card_title) == norm_query,
                proven,
                orig_country != DramaCountry.UNKNOWN and cand == orig_country,
            )

        norm = normalize_title("Queen of Tears")
        tagged = order_key("Queen of Tears (Korean Drama)", "nkiri", norm, False, DramaCountry.KOREAN)
        untagged = order_key("Queen of Tears", "nkiri", norm, False, DramaCountry.KOREAN)
        self.assertGreater(tagged, untagged, "tagged same-country card must probe first")
        # Probing order is nonzero-cost but never a rejection: the untagged
        # twin is still accepted by the candidate filter.
        cand = evaluate_failover_candidate(
            orig_site="asianc",
            orig_show_title="Queen of Tears (Korean Drama)",
            orig_ep_title="Episode 5",
            orig_ep_num=5,
            cand_site="nkiri",
            cand_show_title="Queen of Tears",
            cand_year_str="2024",
            cand_category="Series",
            cand_total_episodes=16,
            cand_episodes=[(5, "Queen of Tears Episode 5", "https://nkiri/ep5")],
        )
        self.assertTrue(cand, "untagged twin must still be accepted")

    def test_secondary_ranking_in_engine(self):
        """Invariant: the tiebreak comparator sits below exact/host-health."""
        src = self.source
        exact = src.index("normalizeTitleQuery(it.title) == normQuery")
        health = src.index("hasProvenLocker(it.url)")
        agree = src.index("agreeWith[it] == true")
        self.assertLess(exact, health, "exact match sorts above host health")
        self.assertLess(health, agree, "host health sorts above country agreement")
        self.assertIn("for (card in ordered)", src)

    def test_secondary_country_split_keeps_working(self):
        """Refactor guard: plain extractCountry behavior is unchanged."""
        self.assertEqual(extract_country("Hidden Love [Chinese Drama]"), DramaCountry.CHINESE)
        self.assertEqual(extract_country("Queen of Tears", "nkiri"), DramaCountry.WESTERN)
        self.assertEqual(extract_country("Goblin", "pluto"), DramaCountry.UNKNOWN)

    def test_secondary_cluster_constant_in_engine(self):
        """Invariant: the engine declares the secondary set with both members."""
        self.assertIn('private val SECONDARY_ASIAN_FALLBACK = setOf("nkiri", "9jarocks")', self.source)
        self.assertIn("ASIAN_DRAMA_CLUSTER + SECONDARY_ASIAN_FALLBACK", self.source)
        self.assertIn("extractFailoverCountry(isAsian,", self.source)
