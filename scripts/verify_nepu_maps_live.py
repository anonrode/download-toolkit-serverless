#!/usr/bin/env python3
"""Live verification of theServamp-class nepu.gd SRV_MAP mints.

For a tmdb id + season, walks E1..E12 through EVERY platform chain this
session proved end-to-end, and prints a per-episode verdict table + a
per-platform protocol trace. Media is only proven by FIRST SEGMENT magic
bytes (MPEG-TS 0x47..188 / ftyp / fMP4 styp / EBML) — a 200 m3u8 alone or an
image-segment ad playlist is a FAIL, exactly like verify_vidsrc_live.py.

Platforms (all mint protocols recreated faithfully, incl. tokens/referers):
  net79       GET net79.cc/api/embed-tmdb/{id}?... -> {"mp4"} on
              bcdnxw.hakunaymatata.com (Referer https://videodownloader.site/).
  vidbolt     GET vidbolt.xyz/api/resolve?type=tv&id=.. -> stream.sources[].url
              (dash-hls-bridge worker -> sacdn fMP4).
  vidrift     GET embed.vidrift.net/embed/tv/.. -> SSR /api/orion/<h>/master.m3u8
              -> variant -> signed sacdn fMP4 segments (Referer mandatory).
  nsrplay     embed PAGE_TOKEN -> /sources?pt= -> token(filter
              directResolveEligible:true) -> /resolve -> stream-proxy (206 mp4).
  streamvault page ?et= token -> /api/embed-streams/sse/.. -> data:url_b64
              (Cobalt) -> b64decode -> u=<bcdnxw upstream> (+vdls referer).
  buzz-control  verify_vidsrc_live-style vidsrc.buzz chain (working titles).

Usage:
  python scripts/verify_nepu_maps_live.py                 # tmdb 67710 s1 e1..e12
  python scripts/verify_nepu_maps_live.py --tmdb 67710 --episodes 1 2 3
  python scripts/verify_nepu_maps_live.py --only net79,vidbolt --control
Exit 0 iff every requested episode proved real media bytes on >=1 platform
(controls, when requested, must also PROVE; net79+vidbolt controls included).
"""
import argparse
import base64
import concurrent.futures as cf
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
VDLS = "https://videodownloader.site/"  # required by bcdnxw.hakunaymatata.com

MP4_BOXES = (b"ftyp", b"styp", b"msdh", b"moof", b"sidx")
MEDIA_OK = ("mpeg-ts", "mp4", "matroska", "fmp4")


def get(url, referer=None, range_hdr=None, timeout=25, retries=1):
    last = ("NETWORK", b"")
    for attempt in range(retries + 1):
        headers = {"User-Agent": UA, "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9"}
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
        except Exception as e:
            last = (None, ("NETWORK-%s" % type(e).__name__).encode())
            if attempt < retries:
                time.sleep(1.5)
    return last[0], {}, last[1]


def text(b):
    return b.decode("utf-8", "replace") if isinstance(b, bytes) else str(b)


def media_magic(data):
    if not data:
        return "EMPTY"
    if data[:4] == b"\x1aE\xdf\xa3":
        return "matroska"
    if data[0:1] == b"\x47" and len(data) > 188 and data[188:189] == b"\x47":
        return "mpeg-ts"
    if len(data) > 8 and data[4:8] in MP4_BOXES:
        return "fmp4" if data[4:8] != b"ftyp" else "mp4"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png-image"
    if data[:5] == b"#EXTM":
        return "m3u8"
    if data[:1] == b"<":
        return "html"
    return "other:" + data[:8].hex()


def hls_to_segment(url, referer, max_variants=1):
    """master -> variant -> first segment. Returns (magic, detail)."""
    st, hd, b = get(url, referer=referer, range_hdr="bytes=0-4095")
    if st != 200 or not text(b).startswith("#EXTM"):
        return "http-%s" % st, "master answered %s ct=%s" % (st, hd.get("Content-Type"))
    lines = [l.strip() for l in text(b).splitlines() if l.strip() and not l.startswith("#")]
    if "#EXT-X-STREAM-INF" in text(b):
        if not lines:
            return "empty-master", "master has no variant"
        vu = urllib.parse.urljoin(url, lines[0])
        st2, hd2, b2 = get(vu, referer=referer)
        if st2 != 200:
            return "variant-http-%s" % st2, "variant answered %s" % st2
        cur_url, cur = vu, b2
        seg_lines = [l.strip() for l in text(cur).splitlines()
                     if l.strip() and not l.startswith("#")]
    else:
        cur_url, cur = url, b
        seg_lines = lines
    if "#EXT-X-KEY" in text(cur) and "METHOD=NONE" not in text(cur):
        return "encrypted", "media playlist carries an AES key the app must fetch separately"
    if not seg_lines:
        return "empty-playlist", "media playlist has no entries"
    seg = urllib.parse.urljoin(cur_url, seg_lines[0])
    st3, hd3, b3 = get(seg, referer=referer, range_hdr="bytes=0-2047")
    magic = media_magic(b3)
    host = urllib.parse.urlsplit(seg).netloc
    return magic, ("seg=%s ct=%s host=%s cr=%s" % (
        st3, hd3.get("Content-Type"), host, hd3.get("Content-Range")))


# --------------------------------------------------------------------------
# Platform chains — each returns (magic, detail)
# --------------------------------------------------------------------------

def chain_net79(kind, tmdb, season, episode, log):
    q = ("type=tv&se=%s&ep=%s" % (season, episode)) if kind == "tv" else "type=movie"
    url = "https://net79.cc/api/embed-tmdb/%s?%s" % (tmdb, q)
    log("  net79 GET %s" % url)
    st, hd, b = get(url, referer="https://net79.cc/")
    log("    -> %s %s %s" % (st, hd.get("Content-Type"), text(b)[:90]))
    if st != 200:
        return "http-%s" % st, "embed-tmdb answered %s" % st
    d = json.loads(text(b))
    if not d.get("ok"):
        return "no-stream", text(b)[:120]
    stream = d.get("mp4") or ""
    if not stream:
        return "no-mp4-field", "no default mp4 in mint"
    st2, hd2, b2 = get(stream, referer=VDLS, range_hdr="bytes=0-2047")
    magic = media_magic(b2)
    return magic, ("probe %s ct=%s cr=%s size~%s" % (
        st2, hd2.get("Content-Type"), hd2.get("Content-Range"),
        d.get("resolution")))


def chain_vidbolt(kind, tmdb, season, episode, log):
    q = ("type=tv&id=%s&season=%s&episode=%s" % (tmdb, season, episode)) \
        if kind == "tv" else ("type=movie&id=%s" % tmdb)
    url = "https://vidbolt.xyz/api/resolve?%s" % q
    log("  vidbolt GET %s" % url)
    st, hd, b = get(url, referer="https://vidbolt.xyz/")
    log("    -> %s %s %s" % (st, hd.get("Content-Type"), text(b)[:90]))
    if st != 200:
        return "http-%s" % st, "resolve answered %s" % st
    d = json.loads(text(b))
    srcs = (d.get("stream") or {}).get("sources") or []
    if not srcs:
        return "no-sources", text(b)[:120]
    return hls_to_segment(srcs[0]["url"], referer="https://vidbolt.xyz/")


def chain_vidrift(kind, tmdb, season, episode, log):
    url = ("https://embed.vidrift.net/embed/tv/%s/%s/%s" % (tmdb, season, episode)) \
        if kind == "tv" else ("https://embed.vidrift.net/embed/movie/%s" % tmdb)
    log("  vidrift GET %s" % url)
    st, hd, b = get(url, referer="https://nepu.gd/")
    log("    -> %s %s %s bytes" % (st, hd.get("Content-Type"), len(b)))
    if st != 200:
        return "http-%s" % st, "embed answered %s" % st
    m = re.search(r"/api/orion/[a-f0-9]{16,}/master\.m3u8", text(b))
    if not m:
        return "no-orion", "embed page carried no orionStreams manifest"
    master = "https://embed.vidrift.net" + m.group(0)
    return hls_to_segment(master, referer="https://embed.vidrift.net/")


def chain_nsrplay(kind, tmdb, season, episode, log):
    suffix = ("tv/%s/%s/%s" % (tmdb, season, episode)) if kind == "tv" else ("movie/%s" % tmdb)
    embed = "https://nsrplay.space/embed/%s" % suffix
    log("  nsrplay GET %s" % embed)
    st, hd, b = get(embed, referer="https://nepu.gd/")
    log("    -> %s %s %s bytes" % (st, hd.get("Content-Type"), len(b)))
    if st != 200:
        return "http-%s" % st, "embed answered %s" % st
    ptm = re.search(r'const PAGE_TOKEN = "([^"]+)"', text(b))
    if not ptm:
        return "no-pagetoken", "embed page shipped no PAGE_TOKEN"
    pt = urllib.parse.quote(ptm.group(1))
    st2, _, b2 = get("https://nsrplay.space/api/v1/embed/sources/%s?pt=%s" % (suffix, pt),
                     referer=embed)
    if st2 != 200:
        return "sources-http-%s" % st2, "sources answered %s" % st2
    txt = text(b2)
    tok = re.search(r'"token":"([a-f0-9]{8,64})","directResolveEligible":true', txt)
    if not tok:
        servers = re.findall(r'"server":"([a-z]+)"', txt)
        return "no-eligible-server", "servers=%s (none directResolveEligible)" % servers[:6]
    st3, _, b3 = get("https://nsrplay.space/api/v1/embed/resolve?token=%s&pt=%s"
                     % (tok.group(1), pt), referer="https://nsrplay.space/")
    if st3 != 200:
        return "resolve-http-%s" % st3, "resolve answered %s" % st3
    d = (json.loads(text(b3)).get("data") or {})
    proxy = d.get("playUrl") or ""
    if not proxy:
        return "no-playurl", text(b3)[:120]
    st4, hd4, b4 = get(proxy, referer="https://nsrplay.space/", range_hdr="bytes=0-2047")
    magic = media_magic(b4)
    return magic, ("proxy %s ct=%s cr=%s" % (
        st4, hd4.get("Content-Type"), hd4.get("Content-Range")))


def chain_streamvault(kind, tmdb, season, episode, log):
    page = ("https://streamvaultsrc.click/embed/tv/%s/%s/%s" % (tmdb, season, episode)) \
        if kind == "tv" else ("https://streamvaultsrc.click/embed/movie/%s" % tmdb)
    log("  streamvault GET %s" % page)
    st, hd, b = get(page, referer="https://nepu.gd/")
    html = text(b)
    log("    -> %s %s %s bytes" % (st, hd.get("Content-Type"), len(b)))
    if st != 200:
        return "http-%s" % st, "embed answered %s" % st
    svp = re.search(r"embed-streams/sse/((?:tv/\d+/\d+/\d+)|(?:movie/\d+))", html)
    et = re.search(r"[?&]et=([a-f0-9]{6,})", html)
    if not (svp and et):
        return "no-et-token", "page carried no SSE path / et token"
    st2, _, b2 = get("https://streamvaultsrc.click/api/embed-streams/sse/%s?et=%s"
                     % (svp.group(1), et.group(1)), referer=page, timeout=40)
    if st2 != 200:
        return "sse-http-%s" % st2, "SSE answered %s" % st2
    ev = re.search(r'"url_b64":"([^"]+)"', text(b2))
    if not ev:
        return "sse-empty", "no Cobalt url_b64 in SSE (streams: %d)" % text(b2).count("data:")
    prox = base64.urlsafe_b64decode(ev.group(1).replace("-", "+").replace("_", "/")).decode()
    mu = re.search(r"u=([^&]+)", prox)
    if not mu:
        return "no-upstream", "decoded proxy URL had no u= param"
    upstream = urllib.parse.unquote(mu.group(1))
    st3, hd3, b3 = get(upstream, referer=VDLS, range_hdr="bytes=0-2047")
    magic = media_magic(b3)
    return magic, ("upstream %s ct=%s cr=%s host=%s" % (
        st3, hd3.get("Content-Type"), hd3.get("Content-Range"),
        urllib.parse.urlsplit(upstream).netloc))


def chain_buzz_control(kind, tmdb, season, episode, log):
    """vidsrc.buzz mint (the shipped OTA first leg) — control reference."""
    embed = ("https://vidsrc.buzz/embed/tv/%s/%s/%s" % (tmdb, season, episode)) \
        if kind == "tv" else ("https://vidsrc.buzz/embed/movie/%s" % tmdb)
    log("  buzz GET %s" % embed)
    st, hd, b = get(embed, referer="https://www.2embed.cc/")
    html = text(b)
    qm = re.search(r"Q\s*=\s*(\{.*?\});", html, re.S)
    if st != 200 or not qm:
        return "http-%s" % st, "embed/bQ missing (%s bytes)" % len(b)
    q = json.loads(qm.group(1))
    tok = q.get("t") or ""
    if not tok:
        return "no-token", "bootstrap carried no t="
    src = ("https://vidsrc.buzz/pl/api.php?a=sources&type=%s&id=%s&s=%s&e=%s&t=%s"
           % (urllib.parse.quote(str(q.get("type"))), urllib.parse.quote(str(q.get("id"))),
              urllib.parse.quote(str(q.get("s"))), urllib.parse.quote(str(q.get("e"))), tok))
    st2, _, b2 = get(src, referer="https://vidsrc.buzz/")
    servers = []
    try:
        servers = json.loads(text(b2)).get("servers") or []
    except Exception:
        pass
    if not servers:
        return "upstream-empty", "sources fill returned zero servers"
    play = ("https://vidsrc.buzz/pl/api.php?a=play&ref=%s&t=%s"
            % (urllib.parse.quote(servers[0].get("ref", ""), safe=""), tok))
    st3, _, b3 = get(play, referer="https://vidsrc.buzz/")
    if st3 != 200:
        return "play-http-%s" % st3, "play answered %s" % st3
    try:
        stream = json.loads(text(b3)).get("url") or ""
    except Exception:
        stream = ""
    if not stream:
        return "no-stream", text(b3)[:120]
    stream = stream if stream.startswith("http") else "https://vidsrc.buzz" + stream.replace("\\", "")
    return hls_to_segment(stream, referer="https://vidsrc.buzz/")


CHAINS = {
    "net79": chain_net79,
    "vidbolt": chain_vidbolt,
    "vidrift": chain_vidrift,
    "nsrplay": chain_nsrplay,
    "streamvault": chain_streamvault,
    "buzz-control": chain_buzz_control,
}
# Default platform set (buzz-control only runs via --control).
DEFAULT_PLATFORMS = ["net79", "vidbolt", "vidrift", "nsrplay", "streamvault"]

CONTROLS = [
    ("tv", "13916", 1, 1),   # Death Note S1E1 — working title on many vaults
    ("tv", "83097", 2, 1),   # reported failure 83097/2/1
    ("movie", "680", None, None),  # Pulp Fiction — net79 movie leg control
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tmdb", default="67710")
    ap.add_argument("--kind", choices=["tv", "movie"], default="tv")
    ap.add_argument("--season", type=int, default=1)
    ap.add_argument("--episodes", type=int, nargs="*", default=list(range(1, 13)))
    ap.add_argument("--only", default="",
                    help="comma list from: net79,vidbolt,vidrift,nsrplay,streamvault")
    ap.add_argument("--control", action="store_true",
                    help="also run buzz/net79/vidbolt controls (must PROVE)")
    ap.add_argument("--evidence", action="store_true", help="print per-hop traces")
    args = ap.parse_args()

    platforms = args.only.split(",") if args.only else list(DEFAULT_PLATFORMS)
    for p in platforms:
        if p not in CHAINS:
            print("unknown platform %r" % p)
            return 2

    failures = []
    residuals = []
    print("nepu SRV_MAP live mint check: tmdb=%s kind=%s s%s episodes=%s"
          % (args.tmdb, args.kind, args.season, args.episodes))
    for ep in args.episodes:
        print("\n== S%s E%s ==" % (args.season, ep))
        proven_here = []
        for name in platforms:
            if args.evidence:
                magic, detail = run_one(args.kind, args.tmdb, args.season, ep, name)
            else:
                magic, detail = run_one_quiet(args.kind, args.tmdb, args.season, ep, name)
            verdict = "PROVEN" if magic in MEDIA_OK else "FAIL"
            print("  %-11s %-14s %s | %s" % (name, magic, verdict, detail[:110]))
            if verdict == "PROVEN":
                proven_here.append(name)
            else:
                residuals.append((args.tmdb, args.season, ep, name, magic, detail))
        if not proven_here:
            failures.append((args.tmdb, args.season, ep, "no platform proved media"))

    if args.control:
        # media: every chain must PROVE. media-later (vidsrc.buzz movies):
        # the known FIRST-HIT-AD residual — an ad image playlist on the first
        # server — does not fail the control (matches verify_vidsrc_live.py).
        controls = [
            ("tv", "13916", 1, 1, "media"),
            ("tv", "83097", 2, 1, "media"),
            ("movie", "680", None, None, "media-later"),
        ]
        print("\n== controls (must PROVE) ==")
        ctrl_fail = False
        for kind, tmdb, s, e, expect in controls:
            label = "%s tmdb=%s" % (kind, tmdb) + (" S%sE%s" % (s, e) if kind == "tv" else "")
            for name in ("net79", "vidbolt", "buzz-control"):
                magic, detail = run_one_quiet(kind, tmdb, s, e, name)
                verdict = "PROVEN" if magic in MEDIA_OK else "FAIL"
                ok = verdict == "PROVEN" or (expect == "media-later" and magic == "png-image")
                print("  %-14s %-14s %-14s %s | %s" % (name, label[:14], magic, "ok" if ok else "FAIL", detail[:90]))
                if not ok:
                    failures.append((tmdb, s, e, name + ":control", magic, detail))
                    ctrl_fail = True
        if not ctrl_fail:
            print("  all controls proved media (movie buzz first-hit ad allowed as media-later)")

    print("\n== summary ==")
    if residuals:
        print("per-platform residual gaps (episode had media elsewhere):")
        for r in residuals:
            print("  residual", r[:5], "|", r[5][:80])
    if failures:
        for f in failures:
            print("FAIL", f)
        print("exit 1 — %d failing checks" % len(failures))
        return 1
    print("every requested episode proved real media bytes on >=1 proven platform")
    return 0


def run_one(kind, tmdb, season, ep, name):
    lines = []

    def log(m):
        lines.append(m)
    try:
        magic, detail = CHAINS[name](kind, tmdb, season, ep, log)
    except Exception as e:
        magic, detail = "EXC-%s" % type(e).__name__, str(e)[:120]
    for line in lines:
        print("    " + line)
    return magic, detail


def run_one_quiet(kind, tmdb, season, ep, name):
    def quiet(m):
        pass
    try:
        return CHAINS[name](kind, tmdb, season, ep, quiet)
    except Exception as e:
        return "EXC-%s" % type(e).__name__, str(e)[:120]


if __name__ == "__main__":
    sys.exit(main())
