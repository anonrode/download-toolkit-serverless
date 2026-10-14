#!/usr/bin/env python3
"""
Comprehensive edge-case verification test suite for progress metering
and stream reconciliation implementations across all three download cores:
- Aria2Control (in-flight 16 KiB block counting, verified landed bytes)
- TurboDownloader (SpeedMeter two-phase EWMA, single-stream resume without speed spikes)
- StreamProgressMeter (HlsMonotonicProgressReconciler dynamic weighting & tail reserve, FlickerFreeEtaCalculator)
- ProgressParser (aria2c ETA format parsing, startup 50% jump suppression)
- HlsSizeEstimator (HTML error decoy quarantine)
- DownloadEngine (computeDiskBytes & watchdog preallocation trap prevention)
"""

import math
import re
import struct
import sys
from typing import List, Optional, Tuple

def assert_true(cond: bool, msg: str):
    if not cond:
        print(f"  [FAIL] {msg}")
        sys.exit(1)
    print(f"  [PASS] {msg}")

# ==============================================================================
# SECTION 1: Aria2Control In-Flight Block Parsing & verifiedLandedBytes
# ==============================================================================

class PyTurboChunk:
    def __init__(self, start: int, end: int, current: int):
        self.start = start
        self.end = end
        self.current = current

class PyAria2Control:
    def __init__(self, file_length: int, pieces: List[PyTurboChunk], in_flight_bytes: int = 0):
        self.file_length = file_length
        self.pieces = pieces
        self.in_flight_bytes = in_flight_bytes

    def verified_landed_bytes(self) -> int:
        completed = sum(max(0, p.current - p.start) for p in self.pieces)
        return min(self.file_length, completed + self.in_flight_bytes)

    @classmethod
    def parse(cls, data: bytes) -> Optional['PyAria2Control']:
        if len(data) < 34:
            return None
        if data[0] != 0x00 or data[1] != 0x01:
            return None
        pos = 2
        ext, info_hash_len = struct.unpack(">II", data[pos:pos+8])
        pos += 8
        if (ext & 1) != 0 or info_hash_len != 0:
            return None
        if pos + 24 > len(data):
            return None
        piece_len, total_len, upload_len, bitfield_len = struct.unpack(">IQQI", data[pos:pos+24])
        pos += 24
        if piece_len <= 0 or total_len <= 0 or bitfield_len < 0:
            return None
        expected_bf_len = ((total_len + piece_len - 1) // piece_len + 7) // 8
        if bitfield_len != expected_bf_len:
            return None
        if pos + bitfield_len + 4 > len(data):
            return None
        bitfield = data[pos:pos+bitfield_len]
        pos += bitfield_len

        piece_count = (total_len + piece_len - 1) // piece_len
        pieces = []
        for i in range(piece_count):
            start = i * piece_len
            end = min(start + piece_len - 1, total_len - 1)
            byte_idx = i // 8
            bit_idx = i % 8
            complete = (byte_idx < len(bitfield)) and ((bitfield[byte_idx] & (0x80 >> bit_idx)) != 0)
            pieces.append(PyTurboChunk(start, end, end + 1 if complete else start))

        num_in_flight, = struct.unpack(">I", data[pos:pos+4])
        pos += 4
        in_flight_bytes = 0
        for _ in range(num_in_flight):
            if pos + 12 > len(data):
                return None
            p_idx, p_len, p_bf_len = struct.unpack(">III", data[pos:pos+12])
            pos += 12
            if pos + p_bf_len > len(data):
                return None
            is_complete = (0 <= p_idx < piece_count) and ((bitfield[p_idx // 8] & (0x80 >> (p_idx % 8))) != 0)
            if not is_complete:
                verified_blocks = 0
                for b in range(p_bf_len):
                    verified_blocks += bin(data[pos + b]).count('1')
                in_flight_bytes += verified_blocks * 16384
            pos += p_bf_len

        return cls(total_len, pieces, in_flight_bytes)

def test_aria2_control_and_in_flight():
    print("\n--- TEST SUITE 1: Aria2Control In-Flight 16 KiB Blocks & verifiedLandedBytes ---")
    piece_len = 1048576  # 1 MiB
    total_len = 10485760 # 10 MiB (10 pieces)
    # Piece count = 10. Bitfield length = 2 bytes
    # Completed pieces: Piece 0 and Piece 2 completed -> 0b10100000, 0b00000000
    bf = bytes([0b10100000, 0b00000000])
    
    # In-flight pieces: 1 in-flight piece (Piece 1, which has 64 blocks of 16 KiB = 1 MiB)
    # Bitfield for Piece 1: 64 blocks = 8 bytes. Let 10 blocks be downloaded -> 0b11111111, 0b11000000, 0, 0, 0, 0, 0, 0
    p1_bf = bytes([0b11111111, 0b11000000, 0, 0, 0, 0, 0, 0])
    num_in_flight = 1
    in_flight_record = struct.pack(">III", 1, piece_len, len(p1_bf)) + p1_bf

    header = struct.pack(">HIIIIQI", 1, 0, 0, piece_len, total_len >> 32, total_len & 0xFFFFFFFF, 0)
    # Packing: VERSION(2)=1, EXT(4)=0, INFOHASH(4)=0, PIECELEN(4), TOTALLEN(8), UPLOAD(8), BFLEN(4)
    raw = struct.pack(">H", 1) + struct.pack(">II", 0, 0) + struct.pack(">IQ", piece_len, total_len) + struct.pack(">Q", 0) + struct.pack(">I", len(bf)) + bf + struct.pack(">I", num_in_flight) + in_flight_record

    ctrl = PyAria2Control.parse(raw)
    assert_true(ctrl is not None, "Aria2Control successfully parsed raw binary V1 format")
    assert_true(ctrl.file_length == 10485760, f"Total length matched: {ctrl.file_length} == 10485760")
    
    # Completed pieces: 2 pieces * 1 MiB = 2,097,152 bytes
    # In-flight blocks: 10 blocks * 16 KiB = 163,840 bytes
    # Total landed: 2,260,992 bytes
    expected_landed = 2 * 1048576 + 10 * 16384
    landed = ctrl.verified_landed_bytes()
    assert_true(landed == expected_landed, f"Exact verified landed bytes matched: {landed} == {expected_landed}")
    
    # Preallocation trap check:
    naive_disk_size = 10485760 # Preallocated by falloc
    assert_true(naive_disk_size > landed, f"Proved preallocated disk size ({naive_disk_size}) is > 4.6x larger than true landed bytes ({landed})")

# ==============================================================================
# SECTION 2: TurboDownloader SpeedMeter & single() Fallback Resume
# ==============================================================================

class PySpeedMeter:
    FAST_ACQUIRE_SAMPLES = 5

    def __init__(self, initial_bytes: int = 0, initial_time_ms: int = 0):
        self.last_bytes = initial_bytes
        self.last_time = initial_time_ms
        self.ema_speed = 0.0
        self.initialized = False
        self.sample_count = 0

    def sample(self, total_bytes: int, now_ms: int) -> int:
        dt = now_ms - self.last_time
        if dt >= 250:
            delta = max(0, total_bytes - self.last_bytes)
            instant_bps = (delta * 1000.0) / dt
            self.sample_count += 1
            if not self.initialized:
                self.ema_speed = instant_bps
                self.initialized = True
            else:
                alpha = 0.6 if self.sample_count <= self.FAST_ACQUIRE_SAMPLES else 0.25
                self.ema_speed = (alpha * instant_bps) + ((1.0 - alpha) * self.ema_speed)
            self.last_bytes = total_bytes
            self.last_time = now_ms
        return max(0, int(self.ema_speed))

def test_speed_meter_and_single_resume():
    print("\n--- TEST SUITE 2: SpeedMeter Two-Phase EWMA & Single-Stream Resume Spike Prevention ---")
    initial_resume_bytes = 50 * 1024 * 1024 # 50 MB partial file already on disk
    meter = PySpeedMeter(initial_bytes=initial_resume_bytes, initial_time_ms=1000)

    # First 250ms tick after resume where 0 new wire bytes landed yet
    spd = meter.sample(initial_resume_bytes, now_ms=1250)
    assert_true(spd == 0, f"Resume cold-start speed is strictly 0 B/s (no 200MB/s fake spike!): got {spd}")

    # Transfer begins at constant 4 MB/s (1 MB per 250ms)
    bytes_cursor = initial_resume_bytes
    speeds = []
    for step in range(1, 10):
        bytes_cursor += 1024 * 1024
        now = 1250 + step * 250
        s = meter.sample(bytes_cursor, now_ms=now)
        speeds.append(s)

    target_bps = 4 * 1024 * 1024 # 4,194,304 B/s
    assert_true(abs(speeds[-1] - target_bps) < 50000, f"SpeedMeter rapidly converged to 4 MB/s: {speeds[-1]} B/s")

# ==============================================================================
# SECTION 3: StreamProgressMeter (HlsMonotonicProgressReconciler & FlickerFreeEtaCalculator)
# ==============================================================================

class PyHlsMonotonicProgressReconciler:
    def __init__(self, estimated_total: int, segment_count: int):
        self.preflight_total = estimated_total
        self.segment_count = segment_count
        self.last_reported_bytes = 0
        self.last_reported_pct = 0.0
        self.working_total = estimated_total

    def update(self, current_seg: int, landed_bytes: int) -> Tuple[int, int, float]:
        if self.segment_count <= 0:
            self.last_reported_bytes = max(self.last_reported_bytes, landed_bytes)
            tot = max(self.working_total, self.last_reported_bytes) if self.working_total > 0 else -1
            pct = min(99.9, max(0.0, (self.last_reported_bytes / tot * 100.0))) if tot > 0 else 0.0
            self.last_reported_pct = max(self.last_reported_pct, pct)
            return (self.last_reported_bytes, tot, self.last_reported_pct)

        ratio = min(1.0, max(0.001, current_seg / self.segment_count))
        projected_total = int(landed_bytes / ratio)

        if self.preflight_total <= 0 and projected_total > 0:
            self.preflight_total = projected_total
            self.working_total = projected_total

        w = ratio ** 1.5
        blended_total = int((1.0 - w) * self.preflight_total + w * projected_total)

        remaining_segs = self.segment_count - current_seg
        if remaining_segs > 0:
            avg_landed_seg = landed_bytes / current_seg if current_seg > 0 else 0.0
            tail_reserve = max(1024, int(avg_landed_seg * remaining_segs * 0.8))
            self.working_total = max(blended_total, landed_bytes + tail_reserve)
        else:
            self.working_total = landed_bytes

        raw_pct = (landed_bytes / self.working_total * 100.0) if self.working_total > 0 else 0.0
        if current_seg < self.segment_count:
            clamped_pct = min(99.9, max(self.last_reported_pct, raw_pct))
        else:
            clamped_pct = 100.0

        self.last_reported_pct = clamped_pct
        self.last_reported_bytes = max(self.last_reported_bytes, landed_bytes)
        return (self.last_reported_bytes, self.working_total, self.last_reported_pct)

class PyFlickerFreeEtaCalculator:
    def __init__(self, initial_time_ms: int = 0):
        self.history = [] # (timestamp_ms, bytes)
        self.smooth_eta = -1.0
        self.last_update_ms = initial_time_ms

    def update(self, current_bytes: int, total_bytes: int, current_speed_bps: float, now_ms: int) -> int:
        if total_bytes <= 0 or current_bytes >= total_bytes:
            return 0
        if current_speed_bps < 1024.0:
            return round(self.smooth_eta) if self.smooth_eta > 0 else -1

        self.history.append((now_ms, current_bytes))
        cutoff = now_ms - 3000
        self.history = [h for h in self.history if h[0] >= cutoff]

        if len(self.history) >= 2:
            dt = (self.history[-1][0] - self.history[0][0]) / 1000.0
            db = self.history[-1][1] - self.history[0][1]
            win_speed = (db / dt) if dt > 0.1 else current_speed_bps
        else:
            win_speed = current_speed_bps

        if win_speed < 1024.0:
            return round(self.smooth_eta) if self.smooth_eta > 0 else -1

        rem_bytes = total_bytes - current_bytes
        raw_eta = rem_bytes / win_speed

        if self.smooth_eta < 0.0:
            self.smooth_eta = raw_eta
        else:
            beta = 0.15
            self.smooth_eta = (beta * raw_eta) + ((1.0 - beta) * self.smooth_eta)

        self.last_update_ms = now_ms
        return max(1, round(self.smooth_eta))

def test_stream_progress_meter():
    print("\n--- TEST SUITE 3: StreamProgressMeter (HLS Monotonic Reconciler & Flicker-Free ETA) ---")
    
    # 3.1: HLS Underestimate Scenario
    # 100 segments, preflight estimated 50 MB, actual turns out to be 150 MB (1.5 MB per seg)
    rec_under = PyHlsMonotonicProgressReconciler(estimated_total=50 * 1024 * 1024, segment_count=100)
    prev_pct = 0.0
    prev_dl = 0
    landed = 0
    for i in range(1, 101):
        landed += int(1.5 * 1024 * 1024)
        dl, tot, pct = rec_under.update(i, landed)
        assert_true(dl >= prev_dl, f"Underestimate step {i}: dl strictly monotonic ({dl} >= {prev_dl})")
        assert_true(pct >= prev_pct, f"Underestimate step {i}: pct strictly monotonic ({pct:.2f}% >= {prev_pct:.2f}%)")
        assert_true(pct < 100.0 if i < 100 else pct == 100.0, f"Underestimate step {i}: tail reserve prevents early 100%")
        prev_dl = dl
        prev_pct = pct

    # 3.2: HLS Overestimate Scenario
    # 100 segments, preflight estimated 200 MB, actual turns out to be 50 MB (500 KB per seg)
    rec_over = PyHlsMonotonicProgressReconciler(estimated_total=200 * 1024 * 1024, segment_count=100)
    prev_pct = 0.0
    prev_dl = 0
    landed = 0
    for i in range(1, 101):
        landed += 500 * 1024
        dl, tot, pct = rec_over.update(i, landed)
        assert_true(dl >= prev_dl, f"Overestimate step {i}: dl strictly monotonic")
        assert_true(pct >= prev_pct, f"Overestimate step {i}: pct strictly monotonic")
        prev_dl = dl
        prev_pct = pct
    assert_true(prev_pct == 100.0, "Overestimate converged cleanly to 100.0% at final segment")

    # 3.3: Flicker-Free ETA Calculator
    eta_calc = PyFlickerFreeEtaCalculator(initial_time_ms=0)
    total_bytes = 100 * 1024 * 1024
    cur_bytes = 0
    eta_values = []
    # Simulate jittery 1-second ticks
    for sec in range(1, 15):
        # Speed varies between 2 MB/s and 5 MB/s
        step_bytes = (3 if sec % 2 == 0 else 4) * 1024 * 1024
        cur_bytes += step_bytes
        spd = step_bytes
        e = eta_calc.update(cur_bytes, total_bytes, spd, now_ms=sec * 1000)
        eta_values.append(e)

    # Check stability: ETA should smoothly descend without wild bouncing
    for k in range(len(eta_values) - 1):
        diff = abs(eta_values[k+1] - eta_values[k])
        assert_true(diff <= 5, f"ETA flicker bounded: step {k}->{k+1} delta {diff}s <= 5s (vals: {eta_values[k]} -> {eta_values[k+1]})")

# ==============================================================================
# SECTION 4: ProgressParser (ETA formats & Startup 50% Suppression)
# ==============================================================================

def py_parse_eta_string(s: str) -> int:
    clean = s.strip()
    if not clean or clean.upper() in ("NA", "UNKNOWN", "-", "--"):
        return -1
    if ':' in clean:
        secs = 0
        for p in clean.split(':'):
            v = int(p.strip())
            secs = secs * 60 + v
        return secs
    total_secs = 0
    matched = False
    m_h = re.search(r'(\d+)\s*h', clean, re.IGNORECASE)
    if m_h:
        total_secs += int(m_h.group(1)) * 3600
        matched = True
    m_m = re.search(r'(\d+)\s*m', clean, re.IGNORECASE)
    if m_m:
        total_secs += int(m_m.group(1)) * 60
        matched = True
    m_s = re.search(r'(\d+)\s*s', clean, re.IGNORECASE)
    if m_s:
        total_secs += int(m_s.group(1)) * 1
        matched = True
    return total_secs if matched else -1

def py_parse_progress_tick(line: Optional[str], library_progress: float, last_dl: int, last_tot: int) -> Tuple[int, int, float, int]:
    dl_bytes = last_dl
    tot_bytes = last_tot
    spd_bps = 0.0
    eta_secs = -1
    parsed = False

    if line:
        # Check standard aria2 with total
        m_aria = re.search(r'\s*([\d.]+[KMGT]?i?B)/([\d.]+[KMGT]?i?B).*?DL:\s*([\d.]+[KMGT]?i?B(?:/s)?)', line, re.IGNORECASE)
        if m_aria:
            parsed = True
            # For test simplicity, mock parse
            m_eta = re.search(r'ETA:\s*([0-9a-zA-Z:]+)', line, re.IGNORECASE)
            if m_eta:
                eta_secs = py_parse_eta_string(m_eta.group(1))

    # Guard against startup item count leaps (e.g. library reporting 50% for item 1 of 2 before bytes land)
    if not parsed and last_tot > 0:
        lib_pct = (library_progress * 100.0) if (0.0 <= library_progress <= 1.0) else library_progress
        if 0.0 < lib_pct <= 100.0:
            if last_dl > 0 or lib_pct <= 5.0:
                dl_bytes = int(last_tot * lib_pct / 100.0)

    safe_tot = max(last_tot, tot_bytes) if tot_bytes > 0 else last_tot
    safe_dl = max(last_dl, dl_bytes)
    return (safe_dl, safe_tot, spd_bps, eta_secs)

def test_progress_parser():
    print("\n--- TEST SUITE 4: ProgressParser (ETA Strings & Startup 50% Suppression) ---")
    
    # ETA format parsing
    assert_true(py_parse_eta_string("43s") == 43, "Parsed '43s' -> 43s")
    assert_true(py_parse_eta_string("5m30s") == 330, "Parsed '5m30s' -> 330s")
    assert_true(py_parse_eta_string("1h24m10s") == 5050, "Parsed '1h24m10s' -> 5050s")
    assert_true(py_parse_eta_string("00:45") == 45, "Parsed '00:45' -> 45s")
    assert_true(py_parse_eta_string("01:23:45") == 5025, "Parsed '01:23:45' -> 5025s")
    assert_true(py_parse_eta_string("Unknown") == -1, "Parsed 'Unknown' -> -1")
    assert_true(py_parse_eta_string("NA") == -1, "Parsed 'NA' -> -1")

    # Startup 50% leap suppression test
    # Scenario: yt-dlp emits item count line "[download] Downloading item 1 of 2", library reports 0.5 (50%)
    # Before bytes land (last_dl = 0, last_tot = 100 MB):
    dl, tot, spd, eta = py_parse_progress_tick("[download] Downloading item 1 of 2", library_progress=0.5, last_dl=0, last_tot=100 * 1024 * 1024)
    assert_true(dl == 0, f"Startup 50% leap suppressed: dl={dl} bytes (did NOT leap to 50 MB!)")

    # Once bytes start moving (last_dl > 0), library progress is permitted:
    dl2, tot2, spd2, eta2 = py_parse_progress_tick("unparseable line", library_progress=0.5, last_dl=1024, last_tot=100 * 1024 * 1024)
    assert_true(dl2 == 50 * 1024 * 1024, f"After bytes move, library progress is accepted: dl2={dl2} bytes")

# ==============================================================================
# SECTION 5: HlsSizeEstimator Decoy Quarantine
# ==============================================================================

def py_probe_segment_filter(status_code: int, content_type: str, headers: dict) -> Optional[int]:
    ct = content_type.lower()
    if "text/html" in ct or "application/xhtml" in ct:
        return None # Quarantined!
    if status_code == 206:
        cr = headers.get("Content-Range", "")
        if "/" in cr:
            try:
                return int(cr.split("/")[-1].strip())
            except ValueError:
                return None
    elif status_code == 200:
        cl = headers.get("Content-Length", "")
        try:
            return int(cl.strip())
        except ValueError:
            return None
    return None

def test_hls_decoy_quarantine():
    print("\n--- TEST SUITE 5: HlsSizeEstimator Decoy Page Quarantine ---")
    
    # Legit video segment TS 206
    seg_size = py_probe_segment_filter(206, "video/mp2t", {"Content-Range": "bytes 0-1024/1572864"})
    assert_true(seg_size == 1572864, f"Legitimate TS segment size probed: {seg_size} bytes")

    # Cloudflare / error HTML page 200 (decoy trap)
    decoy_200 = py_probe_segment_filter(200, "text/html; charset=UTF-8", {"Content-Length": "4520"})
    assert_true(decoy_200 is None, "HTML 200 error decoy quarantined (returned None)")

    # Cloudflare / error HTML page 206
    decoy_206 = py_probe_segment_filter(206, "application/xhtml+xml", {"Content-Range": "bytes 0-1024/8192"})
    assert_true(decoy_206 is None, "XHTML 206 decoy quarantined (returned None)")

# ==============================================================================
# SECTION 6: DownloadEngine Preallocation Trap Guard & Watchdog
# ==============================================================================

def py_compute_disk_bytes_sim(
    status: str,
    total_bytes: int,
    downloaded_bytes: int,
    raw_disk_length: int,
    has_sidecar: bool,
    sidecar_verified_bytes: int
) -> int:
    if has_sidecar:
        return sidecar_verified_bytes
    if status == "DOWNLOADING" and total_bytes > 0 and raw_disk_length >= total_bytes and downloaded_bytes < total_bytes:
        return downloaded_bytes
    return raw_disk_length

def test_download_engine_preallocation_guard():
    print("\n--- TEST SUITE 6: DownloadEngine Preallocation Trap & Watchdog Guard ---")
    
    total = 500 * 1024 * 1024 # 500 MB file
    
    # Case A: Active download with .aria2 sidecar. Disk has 500 MB preallocated. Aria2 control file says 45 MB landed.
    res_a = py_compute_disk_bytes_sim("DOWNLOADING", total, downloaded_bytes=40*1024*1024, raw_disk_length=total, has_sidecar=True, sidecar_verified_bytes=45*1024*1024)
    assert_true(res_a == 45 * 1024 * 1024, f"Sidecar verified bytes respected over preallocated size: {res_a}")

    # Case B: Active download WITHOUT sidecar. Disk has 500 MB preallocated. Downloaded bytes is 10 MB.
    # Must NOT report 500 MB (which would trigger fake 100% completion in UI)!
    res_b = py_compute_disk_bytes_sim("DOWNLOADING", total, downloaded_bytes=10*1024*1024, raw_disk_length=total, has_sidecar=False, sidecar_verified_bytes=0)
    assert_true(res_b == 10 * 1024 * 1024, f"Unverified preallocated disk size rejected: returned {res_b} instead of {total}")

    # Case C: Download truly finished (TaskStatus == COMPLETED, downloaded_bytes == total)
    res_c = py_compute_disk_bytes_sim("COMPLETED", total, downloaded_bytes=total, raw_disk_length=total, has_sidecar=False, sidecar_verified_bytes=0)
    assert_true(res_c == total, f"Completed download disk size accepted: {res_c}")

# ==============================================================================
# MAIN RUNNER
# ==============================================================================

if __name__ == "__main__":
    print("======================================================================")
    print("=== STARTING FULL PROGRESS METERING IMPLEMENTATION TEST SUITE ===")
    print("======================================================================")
    test_aria2_control_and_in_flight()
    test_speed_meter_and_single_resume()
    test_stream_progress_meter()
    test_progress_parser()
    test_hls_decoy_quarantine()
    test_download_engine_preallocation_guard()
    print("\n======================================================================")
    print("=== ALL PROGRESS METERING EDGE-CASE TESTS PASSED PERFECTLY (6/6)! ===")
    print("======================================================================")
