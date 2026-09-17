"""Tests for R1+R2+R3 Architecture: StrictLinkClassifier, MirrorPool, and SitePipeline/DynamicLockerEngine.
Run: python scripts/test_r1_r2_r3_architecture.py
"""
import unittest
from pathlib import Path
from urllib.parse import urlsplit, unquote_plus

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/anonrode/downloader'

class ArchitectureVerificationTest(unittest.TestCase):

    def test_strict_link_classifier_wiring_in_locker_registry(self):
        source = (MAIN / 'resolvers/LockerRegistry.kt').read_text(encoding='utf-8')
        self.assertIn('StrictLinkClassifier.classify(url)', source)

    def test_strict_link_classifier_wiring_in_naija_vault(self):
        source = (MAIN / 'providers/NaijaVaultProvider.kt').read_text(encoding='utf-8')
        self.assertIn('StrictLinkClassifier.isNavigationJunk', source)

    def test_mirror_pool_cooldown_and_failover_api(self):
        source = (MAIN / 'engine/MirrorPool.kt').read_text(encoding='utf-8')
        self.assertIn('fun nextMirror', source)
        self.assertIn('fun prioritize', source)
        self.assertIn('forceRotate: Boolean', source)

    def test_dynamic_locker_engine_registered_in_resolver_registry(self):
        source = (MAIN / 'resolvers/Resolvers.kt').read_text(encoding='utf-8')
        self.assertIn('DynamicLockerResolver', source)
        self.assertIn('DynamicLockerEngine.canResolve(url)', source)

    def test_providers_wired_to_rules_pipeline_and_guarded(self):
        # AsianC
        asianc = (MAIN / 'providers/AsianCProvider.kt').read_text(encoding='utf-8')
        self.assertIn('DynamicRulesManager.getPipeline(name)?.search', asianc)
        self.assertIn('DynamicRulesManager.getPipeline(name)?.episodes', asianc)
        self.assertIn('RulesPipeline.runResolveForSite(name, episodeUrl, quality)', asianc)
        self.assertNotIn('val target = direct ?: episodeUrl', asianc)

        # DramaRain
        dramarain = (MAIN / 'providers/DramaRainProvider.kt').read_text(encoding='utf-8')
        self.assertIn('DynamicRulesManager.getPipeline(name)?.search', dramarain)
        self.assertIn('DynamicRulesManager.getPipeline(name)?.episodes', dramarain)
        self.assertIn('RulesPipeline.runResolveForSite(name, episodeUrl, quality)', dramarain)

        # Pluto
        pluto = (MAIN / 'providers/PlutoProvider.kt').read_text(encoding='utf-8')
        self.assertIn('DynamicRulesManager.getPipeline(name)?.search', pluto)
        self.assertIn('DynamicRulesManager.getPipeline(name)?.episodes', pluto)
        self.assertIn('RulesPipeline.runResolveForSite(name, episodeUrl, quality)', pluto)

        # NaijaPrey
        naijaprey = (MAIN / 'providers/NaijaPreyProvider.kt').read_text(encoding='utf-8')
        self.assertIn('DynamicRulesManager.getPipeline(name)?.search', naijaprey)
        self.assertIn('DynamicRulesManager.getPipeline(name)?.episodes', naijaprey)
        self.assertIn('RulesPipeline.runResolveForSite(name, episodeUrl, quality)', naijaprey)

        # NaijaVault
        naijavault = (MAIN / 'providers/NaijaVaultProvider.kt').read_text(encoding='utf-8')
        self.assertIn('DynamicRulesManager.getPipeline(name)?.search', naijavault)
        self.assertIn('DynamicRulesManager.getPipeline(name)?.episodes', naijavault)

        # 9jaRocks
        rocks = (MAIN / 'providers/RocksProvider.kt').read_text(encoding='utf-8')
        self.assertIn('DynamicRulesManager.getPipeline(name)?.search', rocks)
        self.assertIn('DynamicRulesManager.getPipeline(name)?.episodes', rocks)

    def test_classifier_logic_model(self):
        direct_exts = {'mp4', 'mkv', 'webm', 'avi', 'm3u8', 'm4v', 'ts'}
        nav_segments = {'tag', 'category', 'dmca', 'menu', 'page', 'how-to-download'}
        lockers = {'vikingfile.com', 'downloadwella.com', 'loadedfiles.net', 'lulacloud.com'}

        def classify_model(url, site_host=None):
            if not url or url.startswith('#') or url.startswith('javascript:'):
                return 'JUNK'
            parts = urlsplit(url)
            if parts.scheme.lower() not in ('http', 'https') or not parts.hostname:
                return 'JUNK'
            host = parts.hostname.lower().removeprefix('www.')
            path = parts.path.lower()

            if site_host and (host == site_host or host.endswith('.' + site_host)):
                if path in ('', '/'):
                    return 'JUNK'
                if not any(marker in path for marker in ('/dl-', '/download/', '.mkv', '.mp4')):
                    return 'JUNK'

            ext = path.split('.')[-1] if '.' in path else ''
            if ext in direct_exts:
                return 'DIRECT'

            if any(l in host for l in lockers):
                return 'LOCKER'

            segments = [s for s in path.split('/') if s]
            if any(s in nav_segments for s in segments):
                return 'JUNK'

            return 'UNKNOWN'

        self.assertEqual('DIRECT', classify_model('https://cdn.example.com/video.mp4'))
        self.assertEqual('DIRECT', classify_model('https://cdn.example.com/stream.m3u8'))
        self.assertEqual('LOCKER', classify_model('https://sub.vikingfile.com/d/1234'))
        self.assertEqual('LOCKER', classify_model('https://downloadwella.com/f/xyz'))
        self.assertEqual('JUNK', classify_model('https://naijavault.com/', site_host='naijavault.com'))
        self.assertEqual('JUNK', classify_model('https://naijavault.com/category/movies/', site_host='naijavault.com'))
        self.assertEqual('JUNK', classify_model('javascript:void(0)'))
        self.assertEqual('UNKNOWN', classify_model('https://brand-new-host.example/d/1234'))

if __name__ == '__main__':
    unittest.main(verbosity=2)
