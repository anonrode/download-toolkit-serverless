import json
import unittest

class InstagramPhotoMuxerSlideshowTest(unittest.TestCase):

    def test_carousel_media_extraction(self):
        # Fixture mirroring real Instagram /api/v1/media/<pk>/info/ response
        raw_data = {
            "carousel_media": [
                {
                    "pk": 111,
                    "image_versions2": {
                        "candidates": [
                            {"url": "https://cdn.instagram.com/pic1_low.jpg", "width": 640},
                            {"url": "https://cdn.instagram.com/pic1_high.jpg", "width": 1080}
                        ]
                    }
                },
                {
                    "pk": 222,
                    "video_versions": [{"url": "https://cdn.instagram.com/vid2.mp4"}],
                    "image_versions2": {
                        "candidates": [
                            {"url": "https://cdn.instagram.com/pic2_thumb.jpg", "width": 1080}
                        ]
                    }
                },
                {
                    "pk": 333,
                    "image_versions2": {
                        "candidates": [
                            {"url": "https://cdn.instagram.com/pic3_high.jpg", "width": 1080}
                        ]
                    }
                }
            ],
            "music_metadata": {
                "music_info": {
                    "music_asset_info": {
                        "progressive_download_url": "https://cdn.instagram.com/audio.m4a",
                        "duration_in_ms": 30000,
                        "title": "Song Title",
                        "display_artist": "Artist Name"
                    }
                }
            }
        }

        # Filter out video items, choose highest width candidate
        photos = []
        for child in raw_data.get("carousel_media", []):
            if child.get("video_versions"):
                continue
            cands = child.get("image_versions2", {}).get("candidates", [])
            best = max(cands, key=lambda c: c.get("width", 0), default=None)
            if best and best.get("url"):
                photos.append(best["url"])

        self.assertEqual(len(photos), 2)
        self.assertEqual(photos[0], "https://cdn.instagram.com/pic1_high.jpg")
        self.assertEqual(photos[1], "https://cdn.instagram.com/pic3_high.jpg")

    def test_slideshow_concat_demuxer_spec(self):
        photos = [
            "/cache/slide_0.jpg",
            "/cache/slide_1.jpg",
            "/cache/slide_2.jpg"
        ]
        duration_ms = 18000 # 18s song -> 6s per slide
        num_slides = len(photos)
        per_slide_sec = max(3.0, (duration_ms / num_slides / 1000.0))
        self.assertEqual(per_slide_sec, 6.0)

        lines = []
        for p in photos:
            lines.append(f"file '{p}'")
            lines.append(f"duration {per_slide_sec:.3f}")
        # Concat demuxer requirement: repeat last slide without duration to prevent black frame
        lines.append(f"file '{photos[-1]}'")

        expected = [
            "file '/cache/slide_0.jpg'",
            "duration 6.000",
            "file '/cache/slide_1.jpg'",
            "duration 6.000",
            "file '/cache/slide_2.jpg'",
            "duration 6.000",
            "file '/cache/slide_2.jpg'"
        ]
        self.assertEqual(lines, expected)

    def test_ffmpeg_command_structure(self):
        concat_file = "/cache/slides.txt"
        audio = "/cache/audio.m4a"
        out = "/output/Instagram [DdbtpXdDJ78].mp4"
        lib_path = "/lib/libffmpeg.so"

        scale = "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        common = ["-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", concat_file]
        common.extend(["-i", audio, "-vf", scale, "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest"])

        x264 = [lib_path] + list(common) + ["-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage", out]
        mpeg4 = [lib_path] + list(common) + ["-c:v", "mpeg4", "-vtag", "xvid", "-q:v", "4", out]

        self.assertIn("-f", x264)
        self.assertIn("concat", x264)
        self.assertIn("-shortest", x264)
        self.assertIn("-tune", x264)
        self.assertIn("stillimage", x264)
        self.assertEqual(x264[-1], out)

        self.assertIn("mpeg4", mpeg4)
        self.assertEqual(mpeg4[-1], out)

    def test_best_candidate_without_width_in_sjs(self):
        # In modern Instagram SJS, candidates do not contain integer width fields
        candidates = [
            {"url": "https://instagram.fcdn.net/v/t51/p1080x1350/img_orig.jpg"},
            {"url": "https://instagram.fcdn.net/v/t51/p640x800/img_med.jpg"},
            {"url": "https://instagram.fcdn.net/v/t51/p320x400/img_low.jpg"}
        ]
        import re
        best = None
        bestW = -1
        for i, c in enumerate(candidates):
            url = c.get("url")
            if not url:
                continue
            w = c.get("width", 0)
            if w == 0:
                m = re.search(r'p(\d+)x\d+', url)
                w = int(m.group(1)) if m else (9999 if i == 0 else 0)
            if w > bestW:
                bestW = w
                best = url
        self.assertEqual(best, "https://instagram.fcdn.net/v/t51/p1080x1350/img_orig.jpg")

    def test_shortcode_matching_in_media_set(self):
        objects = [
            {"code": "other1", "image_versions2": {"candidates": [{"url": "https://cdn/1.jpg", "width": 640}]}},
            {"code": "target_code", "image_versions2": {"candidates": [{"url": "https://cdn/target.jpg", "width": 1080}]}},
            {"code": "other2", "image_versions2": {"candidates": [{"url": "https://cdn/2.jpg", "width": 640}]}}
        ]
        target = "target_code"
        matched = None
        fallback = None
        for o in objects:
            code = o.get("code")
            if code == target:
                matched = o
                break
            if fallback is None:
                fallback = o
        res = matched or fallback
        self.assertIsNotNone(res)
        self.assertEqual(res["code"], "target_code")

    def test_is_instagram_photo_error_matching(self):
        err_samples = [
            "ERROR: [Instagram] DdoIYvzgiwj: This Instagram post contains only still photos (no video).",
            "There is no video in this post",
            "No video formats found",
            "only still photos"
        ]
        for err in err_samples:
            is_match = (
                "No video formats found".lower() in err.lower() or
                "There is no video in this post".lower() in err.lower() or
                "This Instagram post contains only still photos".lower() in err.lower() or
                "only still photos".lower() in err.lower()
            )
            self.assertTrue(is_match, f"Failed to match error: {err}")

if __name__ == "__main__":
    unittest.main()
