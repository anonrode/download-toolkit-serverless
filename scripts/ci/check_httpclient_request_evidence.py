"""Offline source contracts + independent logic model; NOT Kotlin execution."""
from pathlib import Path
import unittest
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / 'app/src/main/java/com/anonrode/downloader/data/net/HttpClient.kt').read_text(encoding='utf-8')


def section(start, end):
    return SOURCE.split(start, 1)[1].split(end, 1)[0]


class SourceContracts(unittest.TestCase):
    def test_admission_precedes_post_dispatch(self):
        post = section('internal fun postFormWithClient(', ' * Reachability probe:')
        self.assertLess(post.index('admitRequest(url)'), post.index('client.newCall('))
        self.assertLess(post.index('admitRequest(url)'), post.index('call.execute()'))
        self.assertIn('recordCooldown(res)', post)
        self.assertIn('throw OriginCooldownException(res.request.url.toString())', post)

    def test_get_consumption_inside_registered_scope(self):
        get = section('private fun <T> executeGet(', ' * In-flight resolver/probe calls.')
        self.assertLess(get.index('inFlightCalls.add(call)'), get.index('consume(res)'))
        self.assertLess(get.index('consume(res)'), get.index('finally {'))
        self.assertLess(get.index('finally {'), get.index('inFlightCalls.remove(call)'))
        text = section('internal fun getTextWithClient(', ' * POST with a form-urlencoded body')
        self.assertIn('executeGet(url, referer, headers, tag, false, client) { res ->', text)
        self.assertIn('res.use { r ->', text)
        self.assertNotIn('get(url, referer, headers, tag, permissive).use', text)

    def test_failure_callbacks_and_cancellation_order(self):
        for start, end in [('internal fun getTextWithClient(', ' * POST with a form-urlencoded body'),
                           ('internal fun postFormWithClient(', ' * Reachability probe:')]:
            text = section(start, end)
            self.assertEqual(1, text.count('onFailure?.onFailure(url, e)'))
            self.assertLess(text.index('catch (e: kotlinx.coroutines.CancellationException)'),
                            text.index('catch (e: Exception)'))
            self.assertIn('propagateFailure = onFailure != null', text)
            self.assertNotIn('lastFailure!!', text)

    def test_original_api_and_accepted_status_preserved(self):
        self.assertIn('maxBytes: Long = MAX_TEXT_BYTES): String? =\n        getText(', SOURCE)
        self.assertIn('maxBytes: Long = MAX_TEXT_BYTES): String? =\n        postFormWithClient(', SOURCE)
        self.assertIn('r.isSuccessful || r.code in acceptStatus', SOURCE)
        self.assertIn('propagateFailure = false', SOURCE)
        drain = section('private fun drainCapped(', '/** Read at most [maxBytes]')
        self.assertIn('if (propagateFailure) throw e', drain)
        self.assertIn('catch (e: kotlinx.coroutines.CancellationException)', drain)


class Cooldown(Exception):
    def __init__(self, url):
        self.url = url


class Cancellation(Exception):
    pass


class Model:
    def __init__(self):
        self.now = 0
        self.until = {}
        self.dispatches = 0
        self.registered = False

    @staticmethod
    def origin(url):
        p = urlsplit(url)
        return p.scheme, p.hostname, p.port or (443 if p.scheme == 'https' else 80)

    def request(self, url, status=200, response_url=None, observe=None, consume=lambda: 'page'):
        try:
            if self.until.get(self.origin(url), 0) > self.now:
                raise Cooldown(url)
            self.dispatches += 1
            self.registered = True
            try:
                actual = response_url or url
                if status in (429, 503):
                    self.until[self.origin(actual)] = self.now + 120
                    raise Cooldown(actual)
                return consume()
            finally:
                self.registered = False
        except Cancellation:
            raise
        except Exception as error:
            if observe:
                observe(error)
            return None


class LogicModel(unittest.TestCase):
    def test_cooldown_isolation_expiry_and_no_second_dispatch(self):
        for code in (429, 503):
            model = Model()
            errors = []
            url = 'https://limited.example/form'
            self.assertIsNone(model.request(url, code, observe=errors.append))
            self.assertIsNone(model.request(url, observe=errors.append))
            self.assertEqual(1, model.dispatches)
            self.assertEqual([url, url], [e.url for e in errors])
            self.assertEqual('page', model.request('http://limited.example/form'))
            self.assertEqual('page', model.request('https://other.example/form'))
            model.now = 120
            self.assertEqual('page', model.request(url))

    def test_response_origin_and_request_local_evidence(self):
        model = Model()
        errors = []
        model.request('https://first.example/', 503, 'https://final.example/', errors.append)
        self.assertEqual('https://final.example/', errors[0].url)
        self.assertEqual('page', model.request('https://first.example/'))
        self.assertIsNone(model.request('https://final.example/'))
        self.assertEqual('https://final.example/', errors[0].url)

    def test_body_lifetime_cleanup_and_cancellation(self):
        model = Model()
        def consume():
            self.assertTrue(model.registered)
            raise Cancellation()
        with self.assertRaises(Cancellation):
            model.request('https://body.example/', consume=consume,
                          observe=lambda _: self.fail('Cancellation must escape'))
        self.assertFalse(model.registered)


if __name__ == '__main__':
    unittest.main(verbosity=2)
