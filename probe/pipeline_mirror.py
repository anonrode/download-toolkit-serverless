"""Python mirror of the app's step-pipeline executor (RulesPipeline.kt).

Lets the probe harnesses live-verify the signed playbook's `pipelines`
entries by executing them the way the app would: same templating, same
failover/merge semantics, same JSON-path walking, same filters. This is
what turns the four migrated sites (nkiri/anitaku/dramakey/nepu) from
"parity guessed" into "rules-verified" — the exact payload that ships is
the one exercised here.

Deliberately mirrors the CLOSED VOCABULARY: a primitive this mirror does
not implement is a verification gap to fix here, not something to paper
over with site-specific guessing.

Keep in sync with:
  app/src/main/java/com/anonrode/downloader/providers/RulesPipeline.kt
  scripts/encrypt_rules.py (validate_pipelines)

Verification-only simplifications (documented, intentional):
  - episode label chains are not rendered (probes count episodes/URLs,
    they don't assert titles);
  - capture-sort order is ignored (counts, not order, are verified);
  - meta/titleCleanup are skipped (UI cosmetics, not navigation).
"""
import json
import re
from urllib.parse import quote, urljoin


def walk_json(root, path):
    """Mirror of RulesPipeline.walkJson: dot paths, `name[]` array
    expansion, `*` wildcard object keys, arrays descended element-wise."""
    if not path:
        return [root] if root is not None else []
    current = [root]
    for segment in path.split("."):
        if not segment:
            continue
        iterate = segment.endswith("[]")
        key = segment[:-2] if iterate else segment
        nxt = []
        for node in current:
            focus = list(node) if isinstance(node, list) else [node]
            for f in focus:
                if not isinstance(f, dict):
                    continue
                targets = list(f.values()) if key == "*" else [f.get(key)]
                for t in targets:
                    if t is None:
                        continue
                    if iterate and isinstance(t, list):
                        nxt.extend(t)
                    else:
                        nxt.append(t)
        current = nxt
    return current


def render_template(tmpl, resolve):
    """Pure {var} substitution; a blank/missing/'null' var fails the whole
    template (returns None) so spec alternative lists can fall through."""
    out = []
    cursor = 0
    matched = False
    for m in re.finditer(r"\{([^{}]+)\}", tmpl):
        matched = True
        out.append(tmpl[cursor:m.start()])
        val = resolve(m.group(1))
        if val is None or str(val).strip() == "" or str(val) == "null":
            return None
        out.append(str(val))
        cursor = m.end()
    if not matched:
        return tmpl
    out.append(tmpl[cursor:])
    return "".join(out)


def json_field(item, path):
    """Dot-path lookup with | fallbacks and an optional :before(<char>) cut.

    The cut applies to the whole fallback chain, whichever alternative hit —
    parsing it per-alt would leave early hits uncut (mirrors RulesPipeline.kt).
    """
    cut = None
    m = re.search(r":before\((.)\)$", path)
    if m:
        cut = m.group(1)
        path = path[: m.start()]
    for alt in path.split("|"):
        node = item
        for seg in alt.split("."):
            node = node.get(seg) if isinstance(node, dict) else None
        if node is None:
            continue
        s = str(node)
        if cut:
            s = s.split(cut)[0] if cut in s else ""
        if s.strip():
            return s
    return None


def _bases_for(rules, site):
    bases = [rules.get("domains", {}).get(site, "")]
    bases += [m for m in rules.get("mirrors", {}).get(site, []) if m]
    seen, out = set(), []
    for b in bases:
        b = (b or "").rstrip("/")
        if b and b not in seen:
            seen.add(b)
            out.append(b)
    return out


def _fetch_source(paced, src, step, vars_):
    url = render_template(src.get("url", ""), lambda n: vars_.get(n))
    if not url or not url.startswith("http"):
        return None
    headers = {}
    for k, v in (src.get("headers") or {}).items():
        rendered = render_template(v, lambda n: vars_.get(n))
        if rendered:
            headers[k] = rendered
    referer = None
    for k in list(headers):
        if k.lower() == "referer":
            referer = headers.pop(k)

    method = (src.get("method") or "GET").upper()
    if method == "POST":
        form = {}
        for k, v in (src.get("form") or {}).items():
            form[k] = render_template(v, lambda n: vars_.get(n)) or ""
        st, body = paced.post_form(url, form, referer=referer, headers=headers)[:2]
    else:
        st, body = paced.get(url, referer)[:2]
    if st != 200 or not body:
        return None

    as_format = step.get("as", "html")
    if as_format == "json":
        try:
            return {"body": body, "json": json.loads(body), "soup": None, "url": url}
        except Exception:
            return None
    from bs4 import BeautifulSoup
    if as_format == "rss":
        try:
            soup = BeautifulSoup(body, "xml")
        except Exception:
            soup = BeautifulSoup(body, "html.parser")
        return {"body": body, "json": None, "soup": soup, "url": url}
    return {"body": body, "json": None,
            "soup": BeautifulSoup(body, "html.parser"), "url": url}


def _run_step(paced, rules, site, step, vars_, extract):
    """Mirror of RulesPipeline.runStep source modes."""
    sources = step.get("sources") or []
    mode = step.get("mode", "single")

    expanded = []
    if mode == "failover" and len(sources) == 1 and "{base}" in sources[0].get("url", ""):
        for b in _bases_for(rules, site) or [vars_.get("base", "")]:
            v = dict(vars_)
            v["base"] = b
            expanded.append((sources[0], v))
    else:
        expanded = [(src, vars_) for src in sources]

    merged = []
    last_items = []
    for src, v in expanded:
        outcome = _fetch_source(paced, src, step, v)
        if outcome is None:
            continue
        items = extract(outcome)
        if mode == "merge":
            merged.extend(items)
        elif mode == "failover":
            last_items = items
            if items:
                return items
        else:  # single: first successful fetch is authoritative
            return items
    return last_items if mode == "failover" else merged


def _resolve_field(spec, field_name, ctx, vars_):
    """Mirror of RulesPipeline.resolveField (closed vocabulary)."""
    if spec is None:
        return None
    if isinstance(spec, list):
        for s in spec:
            v = _resolve_field(s, field_name, ctx, vars_)
            if v and str(v).strip():
                return v
        return None
    if not isinstance(spec, str) or not spec:
        return None

    el, obj, base_url = ctx["el"], ctx["json"], ctx["base_url"]

    def _abs(href):
        if not href:
            return None
        return href if href.startswith(("http://", "https://")) else urljoin(base_url, href)

    if spec == "self":
        if field_name == "url":
            return _abs(el.get("href")) if el is not None and el.name == "a" else None
        return el.get_text(" ", strip=True) if el is not None else None
    if spec.startswith("literal:"):
        return spec[len("literal:"):]
    if spec.startswith("link:"):
        if el is None:
            return None
        target = el if el.name == "a" else el.select_one(spec[len("link:"):])
        if target is None:
            return None
        if field_name == "url":
            return _abs(target.get("href"))
        return target.get_text(" ", strip=True)
    if spec.startswith("selector:"):
        if el is None:
            return None
        t = el.select_one(spec[len("selector:"):])
        return t.get_text(" ", strip=True) if t else None
    if spec.startswith("attr:"):
        rest = spec[len("attr:"):]
        css, _, attr = rest.rpartition(":")
        if el is None or not css:
            return None
        t = el.select_one(css)
        if t is None:
            return None
        if t.name == "meta":
            return t.get("content") or None
        val = t.get(attr) or ""
        if attr in ("src", "href"):
            return _abs(val)
        return val or None
    if spec.startswith("field:"):
        return json_field(obj, spec[len("field:"):]) if obj is not None else None
    if spec.startswith("var:"):
        return vars_.get(spec[len("var:"):])
    if spec.startswith("template:"):
        def resolve(name):
            if name in vars_:
                return vars_[name]
            return json_field(obj, name) if obj is not None else None
        return render_template(spec[len("template:"):], resolve)
    return None


def _extract_cards(step, outcome, vars_):
    """Mirror of RulesPipeline.extractSearchCards (verification subset:
    title/url/poster — category/keywords/classPrefix affect presentation,
    not whether the site is alive)."""
    items = step.get("items") or {}
    as_format = step.get("as", "html")
    defaults = items.get("defaults") or {}

    ctxs = []
    if as_format == "json":
        for obj in walk_json(outcome["json"], items.get("itemPath", "")):
            if isinstance(obj, dict):
                v = dict(vars_)
                for k, dv in defaults.items():
                    if obj.get(k) is None:
                        v.setdefault(k, dv)
                ctxs.append({"el": None, "json": obj, "vars": v,
                             "base_url": outcome["url"]})
    elif as_format == "rss":
        soup = outcome["soup"]
        for item in (soup.select("item") if soup else []):
            ctxs.append({"el": item, "json": None, "vars": vars_,
                         "base_url": outcome["url"]})
    else:
        sel = items.get("cardSelector")
        soup = outcome["soup"]
        if not sel or soup is None:
            return []
        for el in soup.select(sel):
            ctxs.append({"el": el, "json": None, "vars": vars_,
                         "base_url": outcome["url"]})

    var_specs = items.get("vars") or {}
    results = []
    seen = set()
    limit = items.get("limit") or 0
    for ctx in ctxs:
        if var_specs and ctx["json"] is not None:
            v = dict(ctx["vars"])
            for name, spec in var_specs.items():
                val = _resolve_field(spec, "var", ctx, v)
                if val:
                    v[name] = val
            ctx = dict(ctx, vars=v)

        url = _resolve_field(items.get("url"), "url", ctx, ctx["vars"])
        if url and items.get("urlStripQuery"):
            url = url.split("?")[0]
        url = (url or "").strip()
        if not url:
            continue
        low = url.lower()
        if any(b.lower() in low for b in items.get("urlBlacklist", [])):
            continue
        title = (_resolve_field(items.get("title"), "title", ctx, ctx["vars"]) or "").strip()
        if not title:
            continue
        if url in seen:
            continue
        seen.add(url)
        poster = (_resolve_field(items.get("poster"), "poster", ctx, ctx["vars"]) or "").strip()
        results.append({"title": title, "url": url, "poster": poster})
        if limit and len(results) >= limit:
            break
    return results


def _extract_episodes(step, outcome, show_url):
    """Mirror of RulesPipeline.extractEpisodes (counts + URLs; labels,
    capture-sort order and meta are verification-irrelevant)."""
    items = step.get("items") or {}
    soup = outcome["soup"]
    if soup is None:
        return {"episodes": [], "body": outcome["body"]}

    anchors = []
    href_regex = items.get("hrefRegex")
    if href_regex:
        try:
            rx = re.compile(href_regex)
        except re.error:
            rx = None
        if rx:
            for a in soup.select("a[href]"):
                raw = a.get("href") or ""
                m = rx.search(raw)
                if m:
                    anchors.append({
                        "href": urljoin(show_url, raw),
                        "captures": list(m.groups()),
                    })
    else:
        sel = items.get("anchorSelector")
        if sel:
            for a in soup.select(sel):
                raw = (a.get("href") or "").strip()
                if not raw or raw.startswith("#") or raw.startswith("javascript:"):
                    continue
                anchors.append({
                    "href": urljoin(show_url, raw).split("#")[0],
                    "captures": [],
                })

    allow = [x.lower() for x in items.get("urlAllowlist", [])]
    black = [x.lower() for x in items.get("urlBlacklist", [])]
    seen_urls, seen_caps, kept = set(), set(), []
    for ctx in anchors:
        href = ctx["href"]
        if not href or href == show_url:
            continue
        # parent-section guard (mirror of showUrl.startsWith(href))
        if show_url.startswith(href):
            continue
        low = href.lower()
        if any(b in low for b in black):
            continue
        if allow and not any(a in low for a in allow):
            continue
        if href_regex:
            key = tuple(ctx["captures"])
            if key in seen_caps:
                continue
            seen_caps.add(key)
        else:
            if href in seen_urls:
                continue
            seen_urls.add(href)
        kept.append(href)

    episodes = kept
    if not episodes:
        fb = items.get("fallbackSingle")
        if fb:
            when = fb.get("when", "always")
            hit = when == "always" or (
                isinstance(when, str) and when.startswith("hasElement:")
                and soup.select_one(when[len("hasElement:"):]) is not None)
            if hit:
                episodes = [show_url]
    return {"episodes": episodes, "body": outcome["body"]}


def _first_items(pipeline):
    for step in pipeline.get("steps", []):
        if step.get("items") is not None:
            return step["items"]
    return None


def _apply_query_transform(query, pipeline):
    qt = (_first_items(pipeline) or {}).get("queryTransform")
    if not qt:
        return query
    out = query
    sr = qt.get("stripRegex")
    if sr:
        try:
            out = re.sub(sr, "", query).strip()
        except re.error:
            out = query
    min_len = qt.get("minLength") or 0
    if min_len and len(out) < min_len:
        return query
    return out


def run_pipeline_search(paced, rules, site, pipeline, query):
    """Execute a search pipeline; returns a list of {title,url,poster}
    cards ([] on any failure — same contract as the app)."""
    if not pipeline.get("steps"):
        return []
    bases = _bases_for(rules, site)
    effective = _apply_query_transform(query, pipeline)
    vars_ = {"base": bases[0] if bases else "", "url": "",
             "query": quote(effective, safe=""),
             # Unencoded for POST form bodies (they encode themselves).
             "queryRaw": effective}
    cards = []
    for step in pipeline["steps"]:
        found = _run_step(
            paced, rules, site, step, vars_,
            extract=lambda outcome, step=step:
                _extract_cards(step, outcome, vars_)
                if step.get("items") is not None else [])
        if step.get("items") is not None:
            cards = found
    # merge-mode dedupe happens per-step in the app; mirror the final pass
    seen, out = set(), []
    for c in cards:
        if c["url"] not in seen:
            seen.add(c["url"])
            out.append(c)
    return out


def run_pipeline_episodes(paced, rules, site, pipeline, show_url):
    """Execute an episodes pipeline; returns (episode_urls, page_body).
    episode_urls is [] when nothing was fetched or found; page_body is the
    last fetched page (feeds the locker-discovery stage) or None."""
    if not pipeline.get("steps"):
        return [], None
    bases = _bases_for(rules, site)
    vars_ = {"base": bases[0] if bases else "", "url": show_url, "query": ""}
    episodes, body = [], None
    for step in pipeline["steps"]:
        found = _run_step(
            paced, rules, site, step, vars_,
            extract=lambda outcome, step=step:
                [_extract_episodes(step, outcome, show_url)]
                if step.get("items") is not None else [])
        if step.get("items") is not None and found:
            episodes = found[0]["episodes"]
            body = found[0]["body"]
    return episodes, body
