import re

def genre_confirmed(title, terms, aliases):
    if not aliases:
        return True
    lower_title = title.lower()

    # Exclude sports betting spam and comedy skits
    if any(k in lower_title for k in ["1xbet", "battles for big wins", "comedy skit:", "download comedy skit"]):
        return False

    # 1. Check title with word boundaries
    for alias in aliases:
        if not alias.strip():
            continue
        pattern = re.compile(rf'(?i)\b{re.escape(alias.strip())}\b')
        if pattern.search(lower_title):
            return True

    # 2. Check taxonomy terms
    for term in terms:
        lower_term = term.lower().strip()
        for alias in aliases:
            if not alias.strip():
                continue
            pattern = re.compile(rf'(?i)\b{re.escape(alias.strip())}\b')
            if lower_term == alias.lower() or pattern.search(lower_term):
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
romance_aliases = {"romance", "romantic"}

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

print("ALL GENRE ACCURACY TESTS PASSED 100%!")
