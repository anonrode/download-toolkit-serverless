#!/usr/bin/env bash
# Generate the synthetic HLS media for the VPS test origin.
# Sources are ffmpeg lavfi only (testsrc2 + sine) — no third-party media,
# no scraping, no network.
#
#   ./generate_media.sh /home/Anon/hls-test-origin/media
#
# Result: media/v0/seg_000.ts … seg_299.ts, media/v0/index.m3u8,
# media/manifest.json. Target ~123 MB (90–180 MB accepted).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$HERE/media}"
VARIANT="$OUT/v0"
mkdir -p "$VARIANT"

command -v ffmpeg >/dev/null 2>&1 || { echo "ffmpeg not found" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "python3 not found" >&2; exit 2; }

ffmpeg -y -hide_banner -loglevel warning \
  -f lavfi -i "testsrc2=size=640x360:rate=30" \
  -f lavfi -i "sine=frequency=440:sample_rate=44100" \
  -t 600 \
  -c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p \
  -b:v 1500k -maxrate 1600k -bufsize 3200k \
  -g 60 -keyint_min 60 -sc_threshold 0 \
  -c:a aac -b:a 64k -ac 2 -ar 44100 \
  -f hls -hls_time 2 -hls_list_size 0 -hls_flags independent_segments \
  -hls_segment_filename "$VARIANT/seg_%03d.ts" \
  "$VARIANT/index.m3u8"

python3 - "$VARIANT" "$OUT" <<'PY'
import json
import sys
from pathlib import Path

variant = Path(sys.argv[1])
out = Path(sys.argv[2])
playlist = variant / "index.m3u8"
lines = playlist.read_text(encoding="utf-8").splitlines()
segments = sorted(variant.glob("seg_*.ts"))
if len(segments) < 300:
    raise SystemExit(f"expected >=300 segments, generated {len(segments)}")
keep = segments[:300]
for extra in segments[300:]:
    extra.unlink()

# Rebuild the playlist as 300 complete EXTINF+URI blocks, dropping extras.
rebuilt = []
count = 0
i = 0
while i < len(lines):
    line = lines[i]
    if line.startswith("#EXTINF") and i + 1 < len(lines):
        if count < 300:
            rebuilt.append(line)
            rebuilt.append(lines[i + 1])
            count += 1
        i += 2
        continue
    if line.startswith("#EXT-X-ENDLIST"):
        i += 1
        continue
    if line.strip():
        rebuilt.append(line)
    i += 1
rebuilt.append("#EXT-X-ENDLIST")
playlist.write_text("\n".join(rebuilt) + "\n", encoding="utf-8")

total = sum(p.stat().st_size for p in keep)
info = {
    "segment_count": len(keep),
    "total_bytes": total,
    "duration_seconds": len(keep) * 2,
    "resolution": "640x360",
    "codecs": "h264/aac",
    "generated_by": "ffmpeg testsrc2 + sine",
}
(variant.parent / "manifest.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
print(f"segments={len(keep)} total_bytes={total} ({total / (1024*1024):.1f} MiB)")
if not (90 * 1024 * 1024 <= total <= 180 * 1024 * 1024):
    raise SystemExit(f"size outside 90-180 MiB: {total} bytes")
PY

echo "media ready: $VARIANT"
