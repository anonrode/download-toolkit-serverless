"""Offline behavioral models plus source-wiring checks; does not execute Kotlin.

Known gaps are asserted as current behavior, not presented as successful fixes.
Run: python scripts/test_resolver_defect_logic.py
"""
import re
import unittest
from pathlib import Path
from urllib.parse import unquote_plus, urlsplit

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/anonrode/downloader'


def unwrap(url):
    # Model only: Python URL parsing is not java.net.URI validation.
    try:
        wrapper = urlsplit(url)
        if not wrapper.path.lower().startswith('/dl/'):
            return url
        param = next((p for p in wrapper.query.split('&')
                      if unquote_plus(p.partition('=')[0]).lower() == 'redirect'), None)
        if param is None:
            return url
        target = unquote_plus(param.partition('=')[2])
        parsed = urlsplit(target)
        return target if parsed.scheme.lower() in ('http', 'https') and parsed.hostname else url
    except ValueError:
        return url


def finish_descent(direct, original, depth, same_claim, media, deeper):
    if direct != original and depth < 6:
        if not (same_claim and media):
            if deeper[0] in ('success', 'failure'):
                return deeper
    return ('success', direct)


def walk(next_pages, start='page', fixed=True):
    current, requests = start, []
    for _ in range(8):
        before = current
        requests.append(current)
        # Missing token, unsuccessful response or capped-text miss leaves URL unchanged.
        current = next_pages.get(current, current)
        if fixed and current == before:
            break
    return requests


class ResolverDefectLogicTest(unittest.TestCase):
    def test_old_first_parameter_regression(self):
        url = 'https://thenkiri.com/dl/movie/?redirect=https%3A%2F%2Fnkiserv.com%2Fx.mkv'
        self.assertIsNone(re.search(r'/dl/[^?]*\?.*?[?&]redirect=([^&]+)', url, re.I))
        self.assertEqual('https://nkiserv.com/x.mkv', unwrap(url))

    def test_wrapper_branches(self):
        cases = [
            ('https://thenkiri.com/dl/x/?a=1&redirect=https%3A%2F%2Fnkiserv.com%2FS01%2FE19.mkv',
             'https://nkiserv.com/S01/E19.mkv'),
            ('https://thenkiri.com/dl/x/?redirect=https%3A%2F%2Fnkiserv.com%2Fmovie.mp4%3Fa%3D1%26b%3D2',
             'https://nkiserv.com/movie.mp4?a=1&b=2'),
            ('https://thenkiri.com/dl/x/?redirect=https://a.example/1&redirect=https://b.example/2',
             'https://a.example/1'),
        ]
        for url, expected in cases:
            with self.subTest(url=url):
                self.assertEqual(expected, unwrap(url))
        for url in [
            'https://nkiserv.com/direct/movie.mkv',
            'https://thenkiri.com/dl/x/?url=https://a.example/1',
            'https://thenkiri.com/dl/x/?redirect=ftp://a.example/1',
            'https://thenkiri.com/dl/x/?redirect=https://',
            'https://thenkiri.com/dl/x/?redirect=%ZZbad',
            'https://thenkiri.com/post/?redirect=https://a.example/1',
        ]:
            with self.subTest(url=url):
                self.assertEqual(url, unwrap(url))

    def test_recursive_failure_is_not_intermediary_success(self):
        failure = ('failure', 'timeout')
        self.assertEqual(failure, finish_descent('locker', 'gateway', 0, False, False, failure))
        self.assertNotEqual(('success', 'locker'), failure)  # old fallback

    def test_deeper_success_and_terminal_branches_preserved(self):
        self.assertEqual(('success', 'cdn'), finish_descent('locker', 'gateway', 0, False, False, ('success', 'cdn')))
        self.assertEqual(('success', 'movie.mkv'), finish_descent('movie.mkv', 'page', 0, True, True, ('failure', 'unused')))
        self.assertEqual(('success', 'unchanged'), finish_descent('unchanged', 'unchanged', 0, True, False, ('failure', 'unused')))

    def test_known_registry_gaps_remain(self):
        # These are limitations of the accepted patch, NOT desired behavior.
        self.assertEqual(('success', 'locker'), finish_descent('locker', 'gateway', 0, False, False, ('no_match', None)))
        self.assertEqual(('success', 'locker'), finish_descent('locker', 'gateway', 6, False, False, ('failure', 'depth')))

    def test_no_parser_progress_stops_after_one_request(self):
        self.assertEqual(8, len(walk({}, fixed=False)))
        self.assertEqual(['page'], walk({}))
        self.assertEqual(['page'], walk({'page': 'page'}))

    def test_progress_chain_and_budget_preserved(self):
        self.assertEqual(['page', 'pt1', 'pt2'], walk({'page': 'pt1', 'pt1': 'pt2'}))
        self.assertEqual(8, len(walk({'page': 'pt1', 'pt1': 'page'})))

    def test_models_are_wired_to_expected_source_branches(self):
        source = (MAIN / 'resolvers/Resolvers.kt').read_text(encoding='utf-8')
        self.assertIn('if (deeper is ResolverOutcome.Failure) return deeper', source)
        self.assertIn('if (!(sameResolverReclaims && mediaPath))', source)
        chain = source.split('object LoadedfilesResolver : BaseResolver {', 1)[1]
        self.assertIn('val pageBeforeStep = currUrl', chain)
        self.assertRegex(chain, r'if \(currUrl == pageBeforeStep\) \{[^}]*\bbreak\b')
        self.assertIn('lastWorkingHost = null', chain)
        provider = (MAIN / 'providers/NkiriProvider.kt').read_text(encoding='utf-8')
        self.assertIn("wrapper.rawQuery?.split('&')?.firstOrNull", provider)
        self.assertIn('!parsed.host.isNullOrBlank()', provider)


if __name__ == '__main__':
    unittest.main(verbosity=2)
