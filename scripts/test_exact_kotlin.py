import requests
import re
from bs4 import BeautifulSoup

def simulate_exact_kotlin():
    url = 'https://loadedfiles.net/a1b699fe62085569/The.Last.Goodbye.To.Mama.S01E15.540p.x265.AAC.[9jaRocks.Com].mkv'
    print("Testing exact Kotlin LoadedfilesResolver logic on:", url)
    
    # 1. candidate probe
    print("--- 1. Candidate probe ---")
    s = requests.Session()
    # Note: Kotlin uses noRedirectClient for each step, and manually accumulates cookies in cookieMap!
    cookie_map = {}
    
    headers = {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36'
    }
    
    # Fast probe (probeEffectiveUrl)
    probe_headers = dict(headers)
    probe_headers['Referer'] = 'https://my9jarocks.bz/'
    r_probe = requests.get(url, headers=probe_headers, allow_redirects=False, timeout=5)
    print("Probe status:", r_probe.status_code)
    curr_url = url
    working_host = "loadedfiles.net"
    
    # Main token chain loop
    pt_hops = 0
    last_fetched_url = None
    
    for step in range(1, 9):
        print(f"\n=== Kotlin Step {step} ===")
        print("curr_url:", curr_url)
        page_before_step = curr_url
        
        referer = last_fetched_url if last_fetched_url else ("https://my9jarocks.bz/" if step == 1 else f"https://{working_host}/")
        print("referer:", referer)
        
        step_headers = dict(headers)
        step_headers['Referer'] = referer
        if cookie_map:
            cookie_header = "; ".join(f"{k}={v}" for k, v in cookie_map.items())
            step_headers['Cookie'] = cookie_header
            print("cookie_header:", cookie_header)
        
        r = requests.get(curr_url, headers=step_headers, allow_redirects=False, timeout=15)
        last_fetched_url = curr_url
        print("Response status:", r.status_code)
        
        # Cookie extraction
        for sc in r.headers.get('Set-Cookie', '').split(','):
            # rudimentary set-cookie split
            parts = sc.split(';')[0].strip()
            if '=' in parts:
                k, v = parts.split('=', 1)
                k = k.strip()
                v = v.strip()
                if k and v:
                    cookie_map[k] = v
        print("Updated cookie_map:", cookie_map)
        
        # Check location
        loc = r.headers.get('Location')
        if loc:
            print("Got Location header:", loc)
            if pt_hops >= 1:
                print("SUCCESS via pt_hops >= 1 redirect:", loc)
                return loc
            if '/token/download/' in loc or '/d/' in loc:
                print("SUCCESS via direct redirect:", loc)
                return loc
            curr_url = loc
            continue
        
        body = r.text
        # Regex 1: dlTimer
        m_dl = re.search(r"dlTimer\(\{\s*seconds:\s*(\d+),\s*link:\s*['\"]([^'\"]+)['\"]", body, re.I)
        # Regex 2: var downloadUrl
        m_leg = re.search(r"var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'", body, re.I)
        # Regex 3: link:
        m_link = re.search(r"link\s*:\s*['\"]([^'\"]+)['\"]", body, re.I)
        
        next_url = None
        wait_secs = 0
        if m_dl:
            print("Matched dlTimer!")
            wait_secs = int(m_dl.group(1))
            next_url = m_dl.group(2)
        elif m_leg:
            print("Matched legacy downloadUrl!")
            next_url = m_leg.group(1)
        elif m_link:
            print("Matched link: regex!")
            raw = m_link.group(1)
            next_url = raw.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
        else:
            soup = BeautifulSoup(body, 'html.parser')
            btn = soup.select_one("a[href*='/d/'], a[href*='/token/download/'], a[href*='?pt='], a.download-btn, a.btn-download, a.nv-btn--primary")
            if btn:
                href = btn.get('href', '')
                print("Matched Jsoup selector:", href)
                if href and href != curr_url:
                    next_url = href
                    if '?pt=' in href:
                        wait_secs = 5
        
        print(f"next_url: {next_url}, wait_secs: {wait_secs}")
        if next_url:
            curr_url = next_url
            pt_hops += 1
        
        if wait_secs > 0:
            print(f"Waiting {wait_secs}s...")
            import time
            time.sleep(wait_secs)
            
        if curr_url == page_before_step:
            print("STALLED! curr_url == page_before_step")
            break

simulate_exact_kotlin()
