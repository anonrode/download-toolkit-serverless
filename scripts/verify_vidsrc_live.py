#!/usr/bin/env python3
"""Live regression harness for the nepu.gd -> vidsrc download chain.

Walks the EXACT chain the app uses, starting from a nepu.gd watch URL, and
prints every hop's status down to real media bytes. Two modes mirror the two
shipped paths (same UA as HttpClient.DEFAULT_UA, same header set, same
endpoints):

  --mode code  VidsrcResolver.resolveViaBuzz (Resolvers.kt):
                 embed page -> SSR servers if present, else the player's own
                 /pl/api.php?a=sources fill (the 2026-10-15 fix) -> mint the
                 first <=3 servers -> FIRST minted URL wins (shape check only,
                 exactly like the Kotlin loop).
  --mode ota   scraper_rules.json pipelines.nepu resolve+terminal:
                 embed page -> a=sources fill -> first ref only -> probe gate
                 (Range 0-1024 must answer HLS like HttpClient.probeTerminal).

Fidelity notes (never faked):
  - The verdict is what the APP's first hit serves. A later server proving
    media does NOT turn a first-hit ad playlist into a pass; it is printed as
    the diagnostic residual (live: vidsrc lists an ad server first on some
    titles, and the site's own web player skips those client-side).
  - Media is only proven by the first segment's magic bytes: MPEG-TS 0x47
    sync, MP4 ftyp, or Matroska EBML. A server the site's browser would
    discard is reported with its raw playlist/host evidence.

Usage:
  python scripts/verify_vidsrc_live.py                       # both modes
  python scripts/verify_vidsrc_live.py --mode code
  python scripts/verify_vidsrc_live.py --title https://nepu.gd/watch/tv/83097/2/1
Exit 0 = every title met its expectation (media proven, or the flagged
upstream gap reproduced with raw evidence).
"""
import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

# Same UA as HttpClient.DEFAULT_UA — the chain is live-verified under it.
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

BUZZ = "https://vidsrc.buzz"
MINT_BASE = BUZZ + "/pl"
EMBED_REFERER = "https://www.2embed.cc/"
# Identical to VidsrcResolver.Q_BLOCK.
Q_BLOCK = re.compile(r"Q\s*=\s*(\{.*?\});", re.DOTALL)
# VidsrcResolver.TMDB_PATTERN semantics for nepu watch URLs.
WATCH_TMDB = re.compile(r"/(?:movie|tv|filme|serie|embed|embedtv)/(\d+)(?:[/_&?=-](\d+)[/_&?=-](\d+))?")
# Identical to pipelines.nepu.resolve.urlBinds.
BIND_TMDB = re.compile(r"watch/(?:tv|movie)/(\d+)")
BIND_SEASON = re.compile(r"watch/tv/\d+/(\d+)/\d+")
BIND_EPISODE = re.compile(r"watch/tv/\d+/\d+/(\d+)")

# expectation kinds:
#   media        first hit must prove media bytes
#   media-later  media must exist on SOME server (first-hit residual printed)
#   unavailable  every mint must refuse (raw 502 evidence printed)
DEFAULT_TITLES = [
    ("tv", "83097", 2, 1, "media"),        # reported live failure /watch/tv/83097/2/1
    ("tv", "83097", 2, 9, "media"),        # ssr:null case (page ships no servers)
    ("tv", "13916", 1, 1, "media"),        # one more series (Death Note S1E1)
    ("movie", "680", None, None, "media-later"),  # movie path; vidsrc lists an ad
                                                  # server first (see FIRST-HIT-AD)
    ("movie", "550", None, None, "unavailable"),  # upstream gap: every mint 502s
]


def get(url, referer=None, range_hdr=None, timeout=30):
    headers = {"User-Agent": UA, "Accept": "*/*"}
    if referer:
        headers["Referer"] = referer
    if range_hdr:
        headers["Range"] = range_hdr
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()
    except Exception as e:  # DNS/TCP/TLS — no HTTP verdict possible
        return None, {}, f"NETWORK {type(e).__name__}: {e}".encode()


def text(data):
    return data.decode("utf-8", "replace") if isinstance(data, bytes) else str(data)


def playlist_kind(body):
    t = text(body)
    if "#EXT-X-STREAM-INF" in t:
        return "master"
    if "#EXTINF" in t:
        return "media"
    return "unknown"


def first_line(playlist_text, skip_comments=True):
    for line in playlist_text.splitlines():
        line = line.strip()
        if line and (not skip_comments or not line.startswith("#")):
            return line
    return None


def media_magic(data):
    if not isinstance(data, bytes) or not data:
        return "EMPTY"
    head = data[:16]
    if head[:4] == b"\x1aE\xdf\xa3":
        return "matroska"
    if head[0] == 0x47 and len(data) > 188 and data[188] == 0x47:
        return "mpeg-ts"
    if head[4:8] == b"ftyp":
        return "mp4"
    if head[:8] == b"\x89PNG\r\n\x1a\n":
        return "png-image"
    if head[:5] == b"#EXTM":
        return "m3u8"
    if head[:1] == b"<":
        return "html"
    return "other:" + head[:8].hex()


MEDIA_KINDS = ("mpeg-ts", "mp4", "matroska")


def classify_minted_stream(stream_url, log):
    """Follows a minted URL to the FIRST SEGMENT and returns (magic, detail)."""
    st, hd, body = get(stream_url, referer=BUZZ + "/", range_hdr="bytes=0-2047")
    ct = hd.get("Content-Type") or ""
    if st != 200:
        return "http-%s" % st, f"_stream answered {st} ct={ct}"
    kind = playlist_kind(body)
    if kind == "unknown":
        return "not-playlist", f"_stream answered {st} ct={ct} head={text(body)[:80]!r}"
    playlist = text(body)
    base = stream_url
    if kind == "master":
        variant = first_line(playlist)
        if not variant:
            return "empty-master", "master playlist has no variant"
        variant = variant if variant.startswith("http") else BUZZ + variant
        stv, hdv, bodyv = get(variant, referer=BUZZ + "/")
        if stv != 200:
            return "variant-http-%s" % stv, f"variant answered {stv}"
        playlist = text(bodyv)
        base = variant
    seg_rel = first_line(playlist)
    if not seg_rel:
        return "empty-playlist", "media playlist has no entries"
    seg = seg_rel if seg_rel.startswith("http") else base.rsplit("/", 1)[0] + "/" + seg_rel
    sts, hds, bodyS = get(seg, referer=BUZZ + "/", range_hdr="bytes=0-2047")
    magic = media_magic(bodyS)
    seg_host = seg.split("/")[2] if seg.startswith("http") else "?"
    detail = (f"first segment {sts} ct={hds.get('Content-Type')} host={seg_host} magic={magic}")
    return magic, detail


def walk(mode, kind, tmdb, season, episode, log):
    """One faithful walk. Returns (verdict, detail)."""
    embed = f"{BUZZ}/embed/{kind}/{tmdb}" + (f"/{season}/{episode}" if kind == "tv" else "")
    log(f"  hop1 GET {embed}  (Referer {EMBED_REFERER})")
    st, hd, body = get(embed, referer=EMBED_REFERER)
    log(f"       -> {st} {hd.get('Content-Type')} {len(body) if isinstance(body, bytes) else body} bytes")
    if st != 200 or not isinstance(body, bytes):
        return "FAILED", f"embed page not served ({st})"
    html = text(body)
    m = Q_BLOCK.search(html)
    if not m:
        return "FAILED", "player bootstrap Q missing"
    try:
        q = json.loads(m.group(1))
    except Exception as e:
        return "FAILED", f"bootstrap Q unparseable: {e}"
    token = q.get("t")
    ssr_servers = (q.get("ssr") or {}).get("servers") or []
    log(f"       Q: type={q.get('type')} id={q.get('id')} s={q.get('s')} e={q.get('e')} "
        f"token={'yes' if token else 'NO'} ssr={'null' if q.get('ssr') is None else len(ssr_servers)}")
    if not token:
        return "FAILED", "bootstrap carries no token"

    # ---- server list: code uses SSR first / fills when empty; ota always
    # fills; fill mode forces the fill (proves the Kotlin fill branch even on
    # days the page happens to carry a warm SSR list).
    servers = [] if mode == "fill" else list(ssr_servers)
    fill_referer = embed if mode in ("code", "fill") else BUZZ + "/"
    if mode == "ota" or not servers:
        fill_url = (f"{MINT_BASE}/api.php?a=sources&type={urllib.parse.quote(str(q.get('type')))}"
                    f"&id={urllib.parse.quote(str(q.get('id')))}&s={urllib.parse.quote(str(q.get('s')))}"
                    f"&e={urllib.parse.quote(str(q.get('e')))}&t={urllib.parse.quote(token)}")
        log(f"  hop2 GET {MINT_BASE}/api.php?a=sources&type={q.get('type')}&id={q.get('id')}"
            f"&s={q.get('s')}&e={q.get('e')}&t=<token>  (Referer {fill_referer})")
        stf, hdf, bodyf = get(fill_url, referer=fill_referer)
        log(f"       -> {stf} {hdf.get('Content-Type')} {text(bodyf)[:170]}")
        if stf != 200 or not isinstance(bodyf, bytes):
            return "FAILED", f"sources fill not served ({stf}): {text(bodyf)[:120]}"
        try:
            servers = json.loads(text(bodyf)).get("servers") or []
        except Exception as e:
            return "FAILED", f"sources fill not JSON: {e} ({text(bodyf)[:120]})"
        log(f"       fill servers={[s.get('name') for s in servers]}")
    if not servers:
        return "UPSTREAM-EMPTY", f"sources fill returned zero servers: {text(bodyf)[:160]}"

    # ---- mint: code tries first 3 and takes the FIRST minted URL (shape
    # check only); ota uses the first ref only. Both are faithful to shipping.
    mint_referer = embed if mode in ("code", "fill") else BUZZ + "/"
    candidates = servers[:1] if mode == "ota" else servers[:3]
    first_hit = None
    for idx, sv in enumerate(candidates):
        play_url = (f"{MINT_BASE}/api.php?a=play&ref={urllib.parse.quote(sv.get('ref', ''), safe='')}"
                    f"&t={urllib.parse.quote(token, safe='')}")
        log(f"  hop3[{idx}] mint server={sv.get('name')!r} (Referer {mint_referer})")
        stm, hdm, bodym = get(play_url, referer=mint_referer)
        log(f"       -> {stm} {text(bodym)[:170]}")
        if stm != 200:
            continue
        try:
            url = json.loads(text(bodym)).get("url") or ""
        except Exception:
            continue
        if not url:
            continue
        stream = url if url.startswith("http") else BUZZ + url
        if mode == "ota":
            # terminal probe gate: Range 0-1024 must answer HLS (mpegurl 200),
            # mirroring acceptsTerminalResponse's HLS branch.
            stp, hdp, bodyp = get(stream, referer=BUZZ + "/", range_hdr="bytes=0-1024")
            ct = (hdp.get("Content-Type") or "").lower()
            gate = stp == 200 and ("mpegurl" in ct or stream.split("?")[0].endswith(".m3u8"))
            log(f"       probe gate (Range 0-1024) -> {stp} ct={hdp.get('Content-Type')} "
                f"{'PASS' if gate else 'REJECT'}")
            if not gate:
                continue
        first_hit = (sv, stream)
        break
    if first_hit is None:
        return "UPSTREAM-502", "every tried server refused the mint (raw bodies above)"

    sv, stream = first_hit
    magic, detail = classify_minted_stream(stream, log)
    log(f"       FIRST HIT [{sv.get('name')!r}] {stream[:100]}")
    log(f"       -> {detail}")

    # Diagnostic: what would a LATER server have served? Walks every server
    # except the first hit (including the candidates the app never reached
    # because it stopped at the first mintable URL). Residual evidence only —
    # never counted as a pass for the first-hit app path.
    later = []
    for idx2, sv2 in enumerate(servers):
        if sv2 is sv:
            continue
        play_url = (f"{MINT_BASE}/api.php?a=play&ref={urllib.parse.quote(sv2.get('ref', ''), safe='')}"
                    f"&t={urllib.parse.quote(token, safe='')}")
        stm, _, bodym = get(play_url, referer=mint_referer)
        if stm != 200:
            continue
        try:
            u2 = json.loads(text(bodym)).get("url") or ""
        except Exception:
            continue
        if not u2:
            continue
        magic2, detail2 = classify_minted_stream(u2 if u2.startswith("http") else BUZZ + u2, log)
        later.append((f"{idx2}:{sv2.get('name')}", magic2, detail2))
        if magic2 in MEDIA_KINDS:
            break
    if magic in MEDIA_KINDS:
        return "PROVEN", f"first-hit server={sv.get('name')!r} {detail}"
    if magic == "png-image":
        extra = ("; media exists on a later server: " + later[-1][0] + " (" + later[-1][2] + ")"
                 if later and later[-1][1] in MEDIA_KINDS else "; no later server proved media")
        return "FIRST-HIT-AD", f"server={sv.get('name')!r} serves an image-segment (ad) playlist{extra}"
    return "FIRST-HIT-BROKEN", f"server={sv.get('name')!r} first segment magic={magic}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["code", "ota", "fill", "both"], default="both",
                    help="code = SSR-first app path; ota = pipeline path (always fill, "
                         "first ref, probe gate); fill = force the Kotlin fill branch")
    ap.add_argument("--title", action="append", default=[],
                    help="nepu watch URL (repeatable); overrides the built-in list")
    args = ap.parse_args()

    titles = []
    if args.title:
        for u in args.title:
            m = WATCH_TMDB.search(u)
            if not m:
                print(f"!! cannot parse a tmdb id out of {u}")
                continue
            kind = "tv" if "/tv/" in u else "movie"
            s = int(m.group(2)) if m.group(2) else None
            e = int(m.group(3)) if m.group(3) else None
            titles.append((kind, m.group(1), s, e, "media"))
    else:
        titles = DEFAULT_TITLES

    modes = ["code", "ota"] if args.mode == "both" else [args.mode]
    failures = []
    for mode in modes:
        print(f"\n==================== mode={mode} ====================")
        for kind, tmdb, s, e, expect in titles:
            label = f"{kind} tmdb={tmdb}" + (f" s{s}e{e}" if kind == "tv" else "")
            print(f"\n## {label} (expect {expect})")
            lines = []
            verdict, detail = walk(mode, kind, tmdb, s, e, lines.append)
            for l in lines:
                print(l)
            print(f"  VERDICT {label}: {verdict} — {detail}")
            if expect == "media" and verdict != "PROVEN":
                failures.append(f"[{mode}] {label}: expected PROVEN, got {verdict} ({detail})")
            if expect == "media-later" and verdict not in ("PROVEN", "FIRST-HIT-AD"):
                failures.append(f"[{mode}] {label}: expected media on some server, got {verdict} ({detail})")
            if expect == "unavailable" and verdict != "UPSTREAM-502":
                failures.append(f"[{mode}] {label}: expected UPSTREAM-502, got {verdict} ({detail})")

    print("\n==================== summary ====================")
    for mode in modes:
        print(f"mode={mode}: {'OK' if not failures else 'FAILURES'}")
    if failures:
        for f in failures:
            print("FAIL:", f)
        return 1
    print("every title met its expectation (media bytes proven, or upstream gap reproduced)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
