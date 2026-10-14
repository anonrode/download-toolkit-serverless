import requests
import re
import time
from bs4 import BeautifulSoup

# Test set of 10 real shows hosted on Loadedfiles
TEST_CASES = [
    {
        "title": "The Last Goodbye To Mama S01E15 (Failed in log)",
        "url": "https://loadedfiles.net/a1b699fe62085569/The.Last.Goodbye.To.Mama.S01E15.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "The Last Goodbye To Mama S01E01",
        "url": "https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "Against The Current S01E01 (Failed in log)",
        "url": "https://loadedfiles.net/7c0632d5bcec34e0/Against.the.Current.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "Irreplaceable S01E01 (Failed in log)",
        "url": "https://loadedfiles.net/1fc81999ed037464/Irreplaceable.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "The Ordinary Jackpot S01E02",
        "url": "https://loadedfiles.net/e48497669b51e5a5/The.Ordinary.Jackpot.S01E02.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "Queen Seon Duk S01E01",
        "url": "https://loadedfiles.net/614c966b39f76711/Queen.Seon.Duk.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "Trust Me The False Prophet S01E01",
        "url": "https://loadedfiles.net/49a8c8e5424eccfc/Trust.Me.The.False.Prophet.S01E01.540p.X265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "In My Prime S01E01",
        "url": "https://loadedfiles.net/aa3ce9ff15aa03ab/In.My.Prime.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "Fated Hearts S01E03",
        "url": "https://loadedfiles.net/c700ea589bb04127/Fated.Hearts.S01E03.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
    {
        "title": "Echoes of the Self S01E02",
        "url": "https://loadedfiles.net/d507cce1f8a9c308/Echoes.of.the.Self.S01E02.540p.x265.AAC.[9jaRocks.Com].mkv",
        "referer": "https://9jarocks.net/"
    },
]

def unescape_js_url(s):
    s = s.replace(r'\/', '/')
    def unesc(m):
        try:
            return chr(int(m.group(1), 16))
        except:
            return m.group(0)
    return re.sub(r'\\u([0-9a-fA-F]{4})', unesc, s)

def crack_loadedfiles(item):
    url = item['url']
    referer = item['referer']
    title = item['title']
    
    print(f"\n========================================================")
    print(f"CRACKING: {title}")
    print(f"URL: {url}")
    print(f"========================================================")
    
    s = requests.Session()
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'
    }
    
    curr_url = url
    last_fetched_url = None
    start_time = time.time()
    direct_url = None
    
    for step in range(1, 11):
        step_referer = last_fetched_url if last_fetched_url else referer
        print(f"  Step {step}: GET {curr_url[:80]}... (Ref: {step_referer[:40]}...)")
        
        r = s.get(curr_url, headers={**headers, 'Referer': step_referer}, allow_redirects=False, timeout=12)
        last_fetched_url = curr_url
        
        # Check redirect
        if r.status_code in (301, 302, 303, 307, 308):
            loc = r.headers.get('Location', '')
            print(f"    -> HTTP {r.status_code} Redirect to: {loc[:90]}...")
            if 'gfrdaseazzs.com' in loc or '/token/download/' in loc or '/d/' in loc or 'static.' in loc:
                direct_url = loc
                print(f"    *** CRACKED DIRECT CDN URL AT STEP {step}! ***")
                break
            curr_url = loc
            continue
            
        ct = r.headers.get('Content-Type', '').lower()
        if 'video/' in ct or 'octet-stream' in ct:
            direct_url = curr_url
            print(f"    *** DIRECT MEDIA SERVED AT STEP {step}! ***")
            break
            
        body = r.text
        next_url = None
        wait_secs = 0
        
        # 1. dlTimer
        m_dl = re.search(r"dlTimer\(\{\s*seconds:\s*(\d+),\s*link:\s*['\"]([^'\"]+)['\"]", body, re.I)
        if m_dl:
            wait_secs = int(m_dl.group(1))
            next_url = unescape_js_url(m_dl.group(2))
            print(f"    Found dlTimer (wait {wait_secs}s)")
            
        # 2. link: regex (Alpine.js x-data)
        if not next_url:
            m_link = re.search(r"link\s*:\s*['\"]([^'\"]+)['\"]", body, re.I)
            if m_link:
                next_url = unescape_js_url(m_link.group(1))
                print(f"    Found Alpine link: in page text")
                
        # 3. Jsoup selector emulation
        if not next_url:
            soup = BeautifulSoup(body, 'html.parser')
            btn = soup.select_one(
                "a[href*='/d/'], a[href*='/token/download/'], a[href*='?pt='], a.download-btn, a.btn-download, a.nv-btn--primary, button.nv-btn--primary, [class*='nv-btn--primary']"
            )
            if btn:
                href = btn.get('href') or btn.get('data-href') or ''
                if href and href != curr_url:
                    next_url = href
                    if '?pt=' in href:
                        wait_secs = 5
                    print(f"    Found button with href (wait {wait_secs}s)")
            
            if not next_url:
                x_el = soup.select_one("[x-data*='link']")
                if x_el:
                    x_data = x_el.get('x-data', '')
                    m_x = re.search(r"link\s*:\s*['\"]([^'\"]+)['\"]", x_data, re.I)
                    if m_x:
                        next_url = unescape_js_url(m_x.group(1))
                        print(f"    Found link in [x-data] attribute")
                        
        if not next_url:
            print(f"    FAILED: No next URL or token found at step {step}")
            break
            
        curr_url = next_url
        if wait_secs > 0:
            print(f"    Waiting {wait_secs}s token timer...")
            time.sleep(wait_secs)
            
    elapsed = time.time() - start_time
    if direct_url:
        print(f"  [CRACK OK] Time: {elapsed:.2f}s | Direct: {direct_url[:90]}...")
        # Now probe direct URL with 1KB Range request
        probe_res = s.get(direct_url, headers={**headers, 'Range': 'bytes=0-1024', 'Referer': last_fetched_url}, timeout=10)
        status = probe_res.status_code
        content_range = probe_res.headers.get('Content-Range', '')
        content_length = probe_res.headers.get('Content-Length', '')
        media_type = probe_res.headers.get('Content-Type', '')
        raw_bytes = probe_res.content
        
        # Check magic bytes
        is_mkv = raw_bytes.startswith(b'\x1a\x45\xdf\xa3')
        is_mp4 = b'ftyp' in raw_bytes[:32]
        magic_ok = is_mkv or is_mp4
        magic_label = "MKV" if is_mkv else ("MP4" if is_mp4 else f"Raw ({raw_bytes[:4].hex()})")
        
        print(f"  [PROBE] Status: {status} | Range: {content_range} | Type: {media_type} | Magic: {magic_label}")
        return {
            "title": title,
            "success": True,
            "status": status,
            "magic": magic_label,
            "media_type": media_type,
            "range": content_range or content_length,
            "elapsed": f"{elapsed:.2f}s",
            "direct_url": direct_url
        }
    else:
        print(f"  [CRACK FAILED] after {elapsed:.2f}s")
        return {
            "title": title,
            "success": False,
            "status": 0,
            "magic": "None",
            "media_type": "None",
            "range": "None",
            "elapsed": f"{elapsed:.2f}s",
            "direct_url": "Failed"
        }

if __name__ == '__main__':
    results = []
    for item in TEST_CASES:
        res = crack_loadedfiles(item)
        results.append(res)
        time.sleep(1) # brief pause between shows
        
    print("\n" + "="*80)
    print("FINAL 10-SHOW LOADEDFILES RESOLVER SCORECARD")
    print("="*80)
    print(f"{'Show Title':<42} | {'Status':<6} | {'Magic':<5} | {'Elapsed':<8} | {'Resolved'}")
    print("-" * 80)
    for r in results:
        ok_label = "PASSED" if r['success'] and r['status'] in (200, 206) else "FAILED"
        print(f"{r['title'][:40]:<42} | {r['status']:<6} | {r['magic']:<5} | {r['elapsed']:<8} | {ok_label}")
    print("="*80)
    total_passed = sum(1 for r in results if r['success'] and r['status'] in (200, 206))
    print(f"TOTAL PASSED: {total_passed} / {len(results)} ({total_passed/len(results)*100:.1f}%)")
