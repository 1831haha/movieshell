package com.movieshell.data.sharelink

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.Assert.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject

/** v1.3.22：Set-Cookie 解析只测键与值形态，不涉及真实账号或外网。 */
class ShareHttpTest {

    private val url = "https://drive-pc.quark.cn/1/clouddrive/file/download".toHttpUrl()

    private fun pugs(vararg setCookies: String): String =
        ShareHttp.pugsFromSetCookie(url, Headers.Builder().also { b ->
            setCookies.forEach { b.add("Set-Cookie", it) }
        }.build())

    @Test
    fun pugsFromSetCookie_standardAttributes() {
        assertEquals(
            "abc123",
            pugs("__pugs=abc123; Path=/; Domain=.drive.quark.cn; HttpOnly; Secure"),
        )
    }

    @Test
    fun pugsFromSetCookie_ignoresOtherCookies() {
        assertEquals(
            "only-me",
            pugs(
                "__puus=aaa; Path=/",
                "__pugs=only-me; Path=/",
                "_UP_A=bbb; Path=/",
            ),
        )
    }

    @Test
    fun pugsFromSetCookie_caseInsensitiveName() {
        assertEquals("AbC", pugs("__PUGS=AbC; Path=/"))
    }

    @Test
    fun pugsFromSetCookie_leadingSpaceAndQuotedValue() {
        assertEquals("xyz", pugs(" __pugs=\"xyz\"; Path=/"))
    }

    @Test
    fun pugsFromSetCookie_emptyWhenMissing() {
        assertEquals("", pugs("__puus=aaa; Path=/", "other=1; Path=/"))
        assertEquals("", ShareHttp.pugsFromSetCookie(url, Headers.Builder().build()))
    }

    private fun get(server: MockWebServer, cookie: String = "", client: okhttp3.OkHttpClient = ShareHttp.defaultClient()) = ShareHttp.getJson(
        client, server.url("/1/clouddrive/file/download?token=PRIVATE").toString(),
        cookie, "test", "https://pan.quark.cn/",
    )

    private fun download(server: MockWebServer, sleeps: MutableList<Long>) = ShareHttp.postDownloadJson(
        ShareHttp.defaultClient(), server.url("/1/clouddrive/file/download?token=PRIVATE").toString(),
        "session=PRIVATE", "test", "https://pan.quark.cn/", JSONObject(), sleeps::add,
    )

    @Test fun redirectCookieSurvivesAndIsSentToSameOrigin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next")
                .addHeader("Set-Cookie", "__pugs=middle; Path=/"))
            server.enqueue(MockResponse().setBody("{}"))
            assertEquals("middle", get(server, "session=PRIVATE").pugs)
            server.takeRequest()
            val cookie = server.takeRequest().getHeader("Cookie").orEmpty()
            assertTrue(cookie.contains("__pugs=middle"))
            assertTrue(cookie.contains("session=PRIVATE"))
        }
    }

    @Test fun finalCookieWinsOverAllIntermediateCookies() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next")
                .addHeader("Set-Cookie", "__pugs=first; Path=/"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/last")
                .addHeader("Set-Cookie", "__pugs=second; Path=/"))
            server.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie", "__pugs=final; Path=/"))
            assertEquals("final", get(server).pugs)
        }
    }

    @Test fun redirectDoesNotLeakCredentialsOrAcceptForeignPugs() {
        MockWebServer().use { source ->
            MockWebServer().use { foreign ->
                source.enqueue(MockResponse().setResponseCode(302)
                    .setHeader("Location", foreign.url("/target"))
                    .addHeader("Set-Cookie", "__pugs=source; Path=/"))
                foreign.enqueue(MockResponse().setBody("{}")
                    .addHeader("Set-Cookie", "__pugs=foreign; Path=/"))
                assertEquals("source", get(source, "session=PRIVATE").pugs)
                val request = foreign.takeRequest()
                assertNull(request.getHeader("Cookie"))
                assertNull(request.getHeader("Referer"))
            }
        }
    }

    @Test fun credentialsDoNotEscapeIntoAnotherCall() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie", "__pugs=first; Path=/"))
            server.enqueue(MockResponse().setBody("{}"))
            val sharedClient = ShareHttp.defaultClient()
            assertEquals("first", get(server, client = sharedClient).pugs)
            assertEquals("", get(server, client = sharedClient).pugs)
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun downloadRetries500ThenSucceedsAndRetainsEarlyPugs() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("not json")
                .addHeader("Set-Cookie", "__pugs=early; Path=/"))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":[]}"""))
            val sleeps = mutableListOf<Long>()
            val resp = download(server, sleeps)
            assertEquals(200, resp.httpCode)
            assertEquals("early", resp.pugs)
            assertEquals(listOf(200L), sleeps)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("__pugs=early"))
        }
    }

    @Test fun persistent500IsBoundedAndLatestPugsWins() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).addHeader("Set-Cookie", "__pugs=first; Path=/"))
            server.enqueue(MockResponse().setResponseCode(500).addHeader("Set-Cookie", "__pugs=latest; Path=/"))
            server.enqueue(MockResponse().setResponseCode(500))
            val sleeps = mutableListOf<Long>()
            val resp = download(server, sleeps)
            assertEquals(500, resp.httpCode)
            assertEquals("latest", resp.pugs)
            assertEquals(3, server.requestCount)
            assertEquals(listOf(200L, 400L), sleeps)
        }
    }

    @Test fun businessErrorAndOtherHttpErrorsAreNotRetried() {
        for (status in listOf(200, 400, 403, 429, 502, 503)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":23018}"""))
                val sleeps = mutableListOf<Long>()
                assertEquals(status, download(server, sleeps).httpCode)
                assertEquals(1, server.requestCount)
                assertTrue(sleeps.isEmpty())
            }
        }
    }

    @Test fun ordinaryPostsIncludingMutationsAreNeverRetried() {
        for (path in listOf("file/download", "file/delete", "share/sharepage/save", "share/sharepage/token", "file")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(500))
                val resp = ShareHttp.postJson(ShareHttp.defaultClient(),
                    server.url("/1/clouddrive/$path").toString(), "", "test", "", JSONObject())
                assertEquals(500, resp.httpCode)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun retryHelperRejectsMutationPathBeforeSending() {
        MockWebServer().use { server ->
            assertThrows(IllegalArgumentException::class.java) {
                ShareHttp.postDownloadJson(ShareHttp.defaultClient(),
                    server.url("/1/clouddrive/file/delete").toString(), "", "test", "", JSONObject()) {}
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun interruptionDuringBackoffPropagatesWithoutAnotherRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            assertThrows(InterruptedException::class.java) {
                ShareHttp.postDownloadJson(ShareHttp.defaultClient(),
                    server.url("/1/clouddrive/file/download").toString(), "", "test", "", JSONObject()) {
                    throw InterruptedException("stop")
                }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationDuringRequestPropagates() {
        MockWebServer().use { server ->
            val client = ShareHttp.defaultClient().newBuilder().addInterceptor {
                throw java.util.concurrent.CancellationException("stop")
            }.build()
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ShareHttp.postDownloadJson(client, server.url("/1/clouddrive/file/download").toString(),
                    "", "test", "", JSONObject()) {}
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun diagnosticContainsOnlyKnownPathStatusAndNumericCode() {
        val message = ShareHttp.diagnostic("POST", (url.toString() + "?token=PRIVATE").toHttpUrl(),
            500, JSONObject("""{"code":23018,"message":"PRIVATE"}"""), "PRIVATE")
        assertEquals("网盘接口 POST /1/clouddrive/file/download HTTP 500 code=23018 __pugs键=有", message)
        for (code in listOf("PRIVATE", "123\nPRIVATE", "123456789012345", "ERROR_PRIVATE")) {
            val redacted = ShareHttp.diagnostic("SECRET", "https://host/private/PRIVATE?signature=PRIVATE".toHttpUrl(),
                200, JSONObject().put("code", code), "")
            assertEquals("网盘接口 OTHER [other] HTTP 200 code=- __pugs键=无", redacted)
        }
    }

    @Test fun crossOrigin307CannotForwardPostBodyOrCredentials() {
        MockWebServer().use { source ->
            MockWebServer().use { foreign ->
                source.enqueue(MockResponse().setResponseCode(307)
                    .setHeader("Location", foreign.url("/target"))
                    .addHeader("Set-Cookie", "__pugs=source; Path=/"))
                val resp = ShareHttp.postJson(ShareHttp.defaultClient(),
                    source.url("/1/clouddrive/file/download").toString(), "session=PRIVATE", "test", "",
                    JSONObject().put("stoken", "PRIVATE"))
                assertEquals(0, resp.httpCode)
                assertEquals("source", resp.pugs)
                assertEquals(0, foreign.requestCount)
            }
        }
    }
}
