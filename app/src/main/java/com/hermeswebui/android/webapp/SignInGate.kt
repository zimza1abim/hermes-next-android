package com.hermeswebui.android.webapp

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener

/** What one socket-ticket request returned. [cookie] is the cookie jar for the server after the reply. */
data class TicketResult(
    val ticket: String?,
    val signedOut: Boolean = false,
    /** The server has no gateway ticket endpoint: it is not an official Webapp server. */
    val unsupported: Boolean = false,
    val cookie: String? = null,
) {
    override fun toString() = "TicketResult(ticket=${if (ticket == null) "null" else "…"}, signedOut=$signedOut, unsupported=$unsupported)"
}

/**
 * Every native socket-ticket request in the process, one at a time.
 *
 * Nous sign-in rotates its single-use refresh token on a request that refreshes, and refuses the
 * whole sign-in when a spent token is replayed. Two native clients (the monitor and the assistant)
 * minting at once would both send the same token, so each request reads the cookie jar only after the
 * previous one has stored its rotated cookie.
 */
internal object SignInGate {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "hermes-sign-in").apply { isDaemon = true } }
    /**
     * One connection pool for the ticket request and the sockets it opens. HTTP/1.1 only, so the
     * connection a ticket request just warmed up (TCP and TLS done) can carry the WebSocket upgrade
     * that follows instead of a fresh handshake to the server.
     */
    private val shared = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    private val client = shared.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    /** A socket client on the shared pool. */
    fun socketClient(pingIntervalSeconds: Long): OkHttpClient = shared.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    class Handle internal constructor() {
        @Volatile internal var cancelled = false
        @Volatile internal var call: Call? = null

        fun cancel() {
            cancelled = true
            call?.cancel()
        }
    }

    /**
     * Mints a ticket at [endpoint] with the jar's cookie for [cookieUrl], else [fallbackCookie].
     * [done] runs on the gate's thread, never after [Handle.cancel].
     */
    fun mint(endpoint: MonitorEndpoint, cookieUrl: String, fallbackCookie: String?, done: (TicketResult) -> Unit): Handle =
        exchange(cookieUrl, fallbackCookie, done, failed = { TicketResult(null, cookie = it) }, target = {
            url(endpoint.ticketUrl).post(ByteArray(0).toRequestBody()).header("Origin", endpoint.origin)
        }) { code, body, jar ->
            when {
                code == 401 || code == 403 -> TicketResult(null, signedOut = true, cookie = jar)
                code == 404 || code == 405 -> TicketResult(null, unsupported = true, cookie = jar)
                code !in 200..299 -> TicketResult(null, cookie = jar)
                else -> TicketResult(parseTicket(body), cookie = jar)
            }
        }

    /**
     * Reads the machine's shared UI preferences (where Hermes keeps favourite models), in order with
     * the ticket requests: any signed request may rotate the sign-in cookie. [done] gets null on failure.
     */
    fun preferences(endpoint: MonitorEndpoint, cookieUrl: String, done: (JSONObject?) -> Unit): Handle =
        exchange(cookieUrl, null, done, failed = { null }, target = {
            url(endpoint.preferencesUrl).get().header("Origin", endpoint.origin)
        }) { code, body, _ ->
            if (code !in 200..299) null else runCatching { JSONTokener(body).nextValue() as? JSONObject }.getOrNull()
        }

    /** One request with the jar's cookie, storing any cookie the reply sets before the next request reads it. */
    private fun <T> exchange(
        cookieUrl: String,
        fallbackCookie: String?,
        done: (T) -> Unit,
        failed: (cookie: String?) -> T,
        target: Request.Builder.() -> Request.Builder,
        parse: (code: Int, body: String, jar: String?) -> T,
    ): Handle {
        val handle = Handle()
        executor.execute {
            if (handle.cancelled) return@execute
            val cookie = onMain { CookieManager.getInstance().getCookie(cookieUrl) } ?: fallbackCookie
            val request = Request.Builder()
                .target()
                .header("Accept", "application/json")
                .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                .build()
            val result = runCatching {
                client.newCall(request).also { handle.call = it }.execute().use { reply ->
                    val fresh = reply.headers("Set-Cookie")
                    val jar = if (fresh.isEmpty()) cookie else onMain {
                        val manager = CookieManager.getInstance()
                        fresh.forEach { manager.setCookie(reply.request.url.toString(), it) }
                        manager.flush()
                        manager.getCookie(cookieUrl)
                    } ?: cookie
                    parse(reply.code, if (reply.isSuccessful) reply.body?.string().orEmpty() else "", jar)
                }
            }.getOrElse { failed(cookie) }
            if (!handle.cancelled) done(result)
        }
        return handle
    }

    fun parseTicket(body: String): String? = runCatching {
        (JSONTokener(body).nextValue() as? JSONObject)?.optString("ticket")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** WebView's cookie store is used from the main thread, like the rest of the app does. */
    private fun <T> onMain(block: () -> T?): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        var value: T? = null
        val latch = CountDownLatch(1)
        main.post {
            value = runCatching(block).getOrNull()
            latch.countDown()
        }
        return if (latch.await(5, TimeUnit.SECONDS)) value else null
    }
}
