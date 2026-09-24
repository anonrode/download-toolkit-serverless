package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicLockerEngineTest {

    @After
    fun tearDown() {
        DynamicRulesManager.parseRulesJson("""{"version":"t.reset"}""")
    }

    @Test
    fun canResolve_returnsFalseWhenNoPipelineDeclared() {
        assertFalse(DynamicLockerEngine.canResolve("https://unregistered-locker.example/file/123"))
    }

    @Test
    fun canResolve_returnsFalseForProviderOnlyPipeline() {
        DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "test.provider-only",
              "pipelines": {
                "nepu.gd": {
                  "schema": 1,
                  "search": {
                    "steps": [{
                      "sources": [{"url": "{base}/search?q={query}"}],
                      "items": {"cardSelector": "article", "title": "self", "url": "self"}
                    }]
                  }
                }
              }
            }
            """.trimIndent()
        )
        assertFalse(DynamicLockerEngine.canResolve("https://nepu.gd/watch/tv/4347"))
    }

    @Test
    fun canResolve_returnsTrueWhenPipelineMatches() {
        val json = """
        {
          "version": "test.1",
          "pipelines": {
            "testlocker.com": {
              "schema": 1,
              "resolve": { "steps": [] },
              "terminal": {
                "source": "entry",
                "regex": "^(https?://\\S+)$",
                "group": 1,
                "hosts": ["testlocker.com"],
                "mode": "handoff"
              }
            }
          }
        }
        """.trimIndent()
        DynamicRulesManager.parseRulesJson(json)

        assertTrue(DynamicLockerEngine.canResolve("https://testlocker.com/v/9988"))
        assertTrue(DynamicLockerEngine.canResolve("https://www.testlocker.com/v/9988"))
        assertFalse(DynamicLockerEngine.canResolve("https://otherlocker.com/v/9988"))
    }
}
