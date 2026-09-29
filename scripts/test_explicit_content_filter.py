import re

MAINSTREAM_WHITELIST = re.compile(
    r'\bxxx(?::\s*|\s+)(?:return of\s+)?xander\s*cage\b|\bxxx(?::\s*|\s+)state of the union\b|\bxxx\s*\((?:19\d\d|2002)\)|\byoung\s*adult\b',
    re.IGNORECASE
)

EXPLICIT_URL_REGEX = re.compile(
    r'/(?:18-section|18-plus|18plus|\+18|full-adult-video|adult-movies?|erotic(?:a|-stories)?|xxx|nsfw)/'
    r'|[-_](?:18|18plus|\+18|xxx)(?:-movie|-series|-video)?/?$'
    r'|[-_]adult-(?:movie|series|video)/?$'
    r'|/(?:ullu|kooku|voovi|primeplay|hotshots|cineprime|hunters|bigshots|moodx)/',
    re.IGNORECASE
)

EXPLICIT_TITLE_REGEX = re.compile(
    r'\b(?:'
    r'xxx|'
    r'porn(?:o|ography|star)?|'
    r'hentai|'
    r'jav(?:hd)?|'
    r'erotica?|'
    r'x-?rated|'
    r'brazzers|naughty\s*america|bangbros|reality\s*kings|blacked|tushy|vixen|'
    r'onlyfans\s*leak\w*|leaked\s*nudes?|nude\s*leaks?|celebrity\s*nudes?|'
    r'sex\s*tape|sextape|hardcore\s*sex|uncensored\s*hentai|'
    r'gangbang|blowjob|creampie|deepthroat|masturbat\w*|dildo|camgirl|chaturbate|threesomes?|'
    r'ullu|kooku|voovi|primeplay|hotshots|cineprime|bigshots|moodx|hunters\s*(?:app|original)?|'
    r'charmsukh|palang\s*tod|siskiyaan|kavita\s*bhabhi|riti\s*riwaj|jalebi\s*bai|dunali|'
    r'hotwife|cuckold|swinger|sensual\s*massage|erotic\s*(?:story|stories|positions?)|'
    r'sex\s*positions?|bedroom\s*positions?|'
    r'18\+\s*(?:adult|porn|sex|erotic)|'
    r'adult\s*18\+|'
    r'adult\s*(?:film|movie|video|content)|'
    r'(?:nude|sex)\s*(?:\w+\s+)?scenes?|'
    r'(?:leaked\s+)?nudes?\s*(?:pack|collection|leak|tape|video|pics?)'
    r')\b|'
    r'(?:\[|\(|\b)(?:\+18|18\+|18\s*plus)(?:\]|\)|\b)',
    re.IGNORECASE
)

ADULT_NORMALIZED_TERMS = {
    "porn", "pornography", "adult", "adults", "erotica", "erotic", "hentai",
    "jav", "javhd", "xxx", "nsfw", "softcore", "hardcore", "xrated", "fulladultvideo",
    "18section", "18movies", "adultmovies", "eroticmovies", "18webseries", "18"
}

def is_explicit(title, url="", categories=None):
    if MAINSTREAM_WHITELIST.search(title):
        return False
    if url and EXPLICIT_URL_REGEX.search(url):
        return True
    if EXPLICIT_TITLE_REGEX.search(title):
        return True
    if categories:
        for cat in categories:
            raw_lower = cat.lower().strip()
            if any(k in raw_lower for k in ["18+", "+18", "18-plus", "18 plus", "full adult video", "18-section", "18 section", "sex tape", "leaked nudes"]):
                return True
            if "adult" in raw_lower and not any(k in raw_lower for k in ["young adult", "adult beginner"]):
                return True
            if any(k in raw_lower for k in ["erotic", "porn", "hentai", "jav", "softcore", "hardcore", "x-rated", "nsfw"]):
                return True
            clean = "".join(c for c in raw_lower if c.isalnum())
            if clean in ADULT_NORMALIZED_TERMS:
                return True
    return False

# 1. Test live leak cases identified by the auditor (MUST ALL BE BLOCKED -> True)
leak_cases = [
    ("Isla (2026) – Filipino Movie (18+)", "https://9jarocks.net/isla-2026-filipino-movie-18/", []),
    ("Bulong Ng Laman (2025) [18+]", "https://naijaprey.tv/bulong-ng-laman-2025-18/", []),
    ("Masahe (2026) [+18]", "https://naijavault.com/masahe-2026-18/", []),
    ("Scandal Queen (2026) [+18]", "https://naijavault.com/scandal-queen-2026-18/", []),
    ("Adventures Of A Hotwife Vol. 6 (2025) [+18]", "https://naijavault.com/adventures-of-a-hotwife-vol-6-2025-18/", []),
    ("Section 307 (2020) – Bollywood Movie (18+)", "https://naijavault.com/section-307-2020-18/", []),
    ("Angkinin Mo Ako (2026)", "https://naijaprey.tv/angkinin-mo-ako-2026/", ["Adult", "Vivamax"]),
    ("Pleasure or Pain (2013)", "https://thenkiri.com/pleasure-or-pain-2013/", ["adult"]),
    ("Random Post", "https://9jarocks.net/18-section/post-123/", []),
    ("Erotic Story: The girl I met on the beach (part 2)", "https://9jarocks.net/erotic-story-the-girl/", []),
    ("Charmsukh: Chawl House (2023) S03", "https://9jarocks.net/charmsukh-chawl-house/", []),
    ("Palang Tod: Siskiyaan (2024)", "https://9jarocks.net/palang-tod-siskiyaan/", []),
    ("Sensual Massage: 5 techniques that set the mood", "https://9jarocks.net/sensual-massage/", [])
]

failed_leaks = []
for title, url, tags in leak_cases:
    if not is_explicit(title, url, tags):
        failed_leaks.append((title, url, tags))

print(f"Explicit Leak Tests: {len(leak_cases) - len(failed_leaks)}/{len(leak_cases)} blocked.")
if failed_leaks:
    print("FAILED TO BLOCK:")
    for f in failed_leaks:
        print(" ", f)
    exit(1)

# 2. Test mainstream titles (MUST NOT BE BLOCKED -> False)
mainstream_cases = [
    ("Deadpool & Wolverine (2024)", "https://thenkiri.com/deadpool-wolverine/", ["Action", "Comedy"]),
    ("The Boys Season 4 (2024)", "https://thenkiri.com/the-boys-season-4/", ["Action", "Sci-Fi"]),
    ("Game of Thrones Season 8 (Complete)", "https://9jarocks.net/game-of-thrones/", ["Drama"]),
    ("Sex Education Season 4 (Complete)", "https://thenkiri.com/sex-education-season-4/", ["Comedy", "Drama"]),
    ("Fifty Shades of Grey (2015)", "https://thenkiri.com/fifty-shades-of-grey/", ["Romance", "Drama"]),
    ("xXx: Return of Xander Cage (2017)", "https://thenkiri.com/xxx-return-of-xander-cage/", ["Action"]),
    ("Adult Beginners (2015)", "https://thenkiri.com/adult-beginners/", ["Comedy"]),
    ("Young Adult (2011)", "https://thenkiri.com/young-adult/", ["Comedy", "Drama"])
]

false_positives = []
for title, url, tags in mainstream_cases:
    if is_explicit(title, url, tags):
        false_positives.append((title, url, tags))

print(f"Mainstream Whitelist Tests: {len(mainstream_cases) - len(false_positives)}/{len(mainstream_cases)} correctly preserved.")
if false_positives:
    print("FALSE POSITIVES (Mainstream blocked):")
    for fp in false_positives:
        print(" ", fp)
    exit(1)

print("\nALL EXPLICIT FILTER TESTS PASSED 100%!")
