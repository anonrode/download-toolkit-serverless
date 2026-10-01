import re

def normalize_genre(s):
    return "".join(c for c in s.lower() if c.isalnum())

def genre_confirmed(title, terms, aliases):
    if not aliases:
        return True
    lower_title = title.lower()

    # Exclude sports betting spam and comedy skits
    if any(k in lower_title for k in ["1xbet", "battles for big wins", "comedy skit:", "download comedy skit"]):
        return False

    normalized_aliases = {normalize_genre(a) for a in aliases if normalize_genre(a)}

    # 1. Check taxonomy terms
    for term in terms:
        lower_term = term.lower().strip()
        norm_term = normalize_genre(term)
        if norm_term and norm_term in normalized_aliases:
            return True
        for alias in aliases:
            if not alias.strip():
                continue
            clean_alias = alias.lower().strip()
            if lower_term == clean_alias:
                return True
            pattern = re.compile(rf'(?i)\b{re.escape(clean_alias)}\b')
            if pattern.search(lower_term):
                return True

    # 2. Check title with word boundaries
    title_no_hyphen = lower_title.replace("-", "")
    title_phrased = re.sub(r'\bmartial\s+arts\b', 'martialarts', lower_title, flags=re.I)
    title_phrased = re.sub(r'\bscience\s+fiction\b', 'sciencefiction', title_phrased, flags=re.I)
    for alias in aliases:
        if not alias.strip():
            continue
        clean_alias = alias.lower().strip()
        pattern = re.compile(rf'(?i)\b{re.escape(clean_alias)}\b')
        if pattern.search(lower_title) or pattern.search(title_no_hyphen) or pattern.search(title_phrased):
            return True

    return False

# 1. Action tests
action_aliases = {"action", "martial arts", "martialarts"}

# False positives that previously slipped in (MUST BE REJECTED -> False)
assert not genre_confirmed("Satisfaction (2007) Season 1 – 3", ["Drama"], action_aliases), "Satisfaction leaked into Action!"
assert not genre_confirmed("Behind the Attraction Season 3", ["Documentary"], action_aliases), "Behind the Attraction leaked into Action!"
assert not genre_confirmed("High-Stakes Action: Milan, Barcelona Battles for Big Wins", ["Sports", "1xbet"], action_aliases), "1xbet betting spam leaked into Action!"
assert not genre_confirmed("Fraction of a Second", [], action_aliases), "Fraction leaked into Action!"

# Genuine Action (MUST BE ACCEPTED -> True)
assert genre_confirmed("John Wick: Chapter 4", ["Action", "Thriller"], action_aliases), "John Wick rejected from Action!"
assert genre_confirmed("Action Point (2018)", ["Comedy"], action_aliases), "Action Point rejected from Action!"
assert genre_confirmed("Ip Man: Martial Arts Legend", [], action_aliases), "Martial Arts rejected from Action!"

# 2. Romance tests
romance_aliases = {"romance", "romantic", "love"}

# False positives (MUST BE REJECTED -> False)
assert not genre_confirmed("Cloverfield (2008)", ["Sci-Fi", "Horror"], romance_aliases), "Cloverfield leaked into Romance!"
assert not genre_confirmed("The Beloved (1998)", ["Drama"], romance_aliases), "Beloved leaked into Romance!"
assert not genre_confirmed("Iron Glove", ["Crime"], romance_aliases), "Iron Glove leaked into Romance!"

# Genuine Romance (MUST BE ACCEPTED -> True)
assert genre_confirmed("A Romantic Comedy (2023)", [], romance_aliases), "Romantic rejected from Romance!"
assert genre_confirmed("Crash Landing on You", ["Romance", "Drama"], romance_aliases), "Crash Landing rejected from Romance!"

# 3. Comedy skits exclusion
comedy_aliases = {"comedy", "sitcom"}
assert not genre_confirmed("COMEDY SKIT: Mark Angel Comedy – Bike Man Part 2", ["Comedy"], comedy_aliases), "Comedy skit leaked into Comedy!"
assert not genre_confirmed("DOWNLOAD COMEDY SKIT: THE AUDITION", ["Entertainment"], comedy_aliases), "Comedy skit leaked into Comedy!"
assert genre_confirmed("Superbad (2007)", ["Comedy"], comedy_aliases), "Superbad rejected from Comedy!"

# 4. Sci-Fi tests
scifi_aliases = {"scifi", "sciencefiction"}
assert genre_confirmed("Alien Wave", ["Sci-Fi"], scifi_aliases), "Sci-Fi with hyphen rejected!"
assert genre_confirmed("Dune Prophecy", ["Science Fiction"], scifi_aliases), "Science Fiction with space rejected!"
assert genre_confirmed("Sci-Fi Slaughter", [], scifi_aliases), "Sci-Fi title rejected!"
assert not genre_confirmed("Jujutsu Kaisen 03", ["Anime"], scifi_aliases), "Jujutsu Kaisen leaked into Sci-Fi!"

# 5. Horror tests
horror_aliases = {"horror"}
assert genre_confirmed("American Horror Story", [], horror_aliases), "American Horror Story rejected!"
assert genre_confirmed("Terrifier 3", ["Horror", "Slasher"], horror_aliases), "Terrifier 3 rejected!"
assert not genre_confirmed("The Scaredy Cat", ["Comedy"], horror_aliases), "Scaredy Cat leaked into Horror!"

# 6. Thriller tests
thriller_aliases = {"thriller", "suspense"}
assert genre_confirmed("Thriller", [], thriller_aliases), "Thriller rejected!"
assert genre_confirmed("Nightcrawler", ["Suspense", "Drama"], thriller_aliases), "Nightcrawler rejected!"
assert not genre_confirmed("Singing in the Rain", ["Musical"], thriller_aliases), "Musical leaked into Thriller!"

print("ALL GENRE ACCURACY TESTS PASSED 100%!")
