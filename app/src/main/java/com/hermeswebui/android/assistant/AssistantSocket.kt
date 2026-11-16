package com.hermeswebui.android.assistant

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import com.hermeswebui.android.webapp.MonitorEndpoint
import com.hermeswebui.android.webapp.SignInGate
import com.hermeswebui.android.webapp.TicketResult
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The assistant's socket and timers. It decides nothing: it runs [AssistantEffect]s and reports each
 * callback, tagged with its attempt, through [deliver] on [handler]'s thread.
 */
internal class AssistantSocket(
    private val handler: Handler,
    private val serverUrl: () -> String?,
    private val deliver: (AssistantInput) -> Unit,
) {
    private val client = SignInGate.socketClient(PING_INTERVAL_S)
    /** When the current attempt started and when its ticket arrived, for the connect-time log line. */
    private var startedAt = 0L
    private var ticketAt = 0L
    private var openedAt = 0L

    private var gen = -1
    private var mint: SignInGate.Handle? = null
    private var socket: WebSocket? = null
    private var endpoint: MonitorEndpoint? = null
    private var url: String? = null
    private val retry = Runnable { deliver(AssistantInput.RetryDue(gen)) }
    private val readyDeadline = Runnable {
        socket?.cancel()
        deliver(AssistantInput.Closed(gen))
    }

    fun mint(gen: Int) {
        close(this.gen)
        this.gen = gen
        val server = serverUrl()?.takeIf { it.isNotBlank() }
        val target = server?.let(MonitorEndpoint::from)
        endpoint = target
        url = server
        if (server == null || target == null) {
            // No server yet: the main app has to connect and sign in first, the same fix as a refused sign-in.
            handler.post { deliver(AssistantInput.Ticket(gen, TicketResult(null, signedOut = true))) }
            return
        }
        // Touch the cookie store on the main thread first; the gate then reads it in order with the monitor.
        val cookie = runCatching { CookieManager.getInstance().getCookie(server) }.getOrNull()
        startedAt = SystemClock.elapsedRealtime()
        mint = SignInGate.mint(target, server, cookie) { result ->
            handler.post {
                ticketAt = SystemClock.elapsedRealtime()
                deliver(AssistantInput.Ticket(gen, result))
            }
        }
    }

    fun dial(gen: Int, ticket: String) {
        val target = endpoint ?: return deliver(AssistantInput.Closed(gen))
        if (gen != this.gen) return
        val cookie = url?.let { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
        val request = Request.Builder()
            .url(target.socketUrl)
            .header("Origin", target.origin)
            .header("Sec-WebSocket-Protocol", "$PROTOCOL, $TICKET_PROTOCOL_PREFIX$ticket")
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (gen != this@AssistantSocket.gen) return@post
                    openedAt = SystemClock.elapsedRealtime()
                    handler.postDelayed(readyDeadline, RESPONSE_TIMEOUT_MS)
                    deliver(AssistantInput.Opened(gen))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = AssistantProtocol.parse(text) ?: return
                handler.post {
                    if (gen != this@AssistantSocket.gen) return@post
                    if (frame is AssistantFrame.Ready) {
                        handler.removeCallbacks(readyDeadline)
                        val now = SystemClock.elapsedRealtime()
                        Log.i(TAG, "connected in ${now - startedAt} ms: ticket ${ticketAt - startedAt}, open ${openedAt - ticketAt}, ready ${now - openedAt}")
                    }
                    deliver(AssistantInput.Received(gen, frame))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(gen)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(gen)
        })
    }

    private fun closed(gen: Int) {
        handler.post { deliver(AssistantInput.Closed(gen)) }
    }

    /** False when attempt [gen] has no open socket or the frame could not be queued. */
    fun send(gen: Int, frame: String): Boolean = gen == this.gen && socket?.send(frame) == true

    fun retryAfter(gen: Int, delayMs: Long) {
        if (gen != this.gen) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
    }

    /** Ends attempt [gen]. Idempotent. */
    fun close(gen: Int) {
        if (gen != this.gen) return
        this.gen = -1
        mint?.cancel()
        mint = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        handler.removeCallbacks(retry)
        handler.removeCallbacks(readyDeadline)
    }

    private companion object {
        const val TAG = "HermesAssistant"
        const val PROTOCOL = "hermes-gateway-v1"
        const val TICKET_PROTOCOL_PREFIX = "hermes-gateway-ticket."
        const val NORMAL_CLOSURE = 1000
        const val PING_INTERVAL_S = 15L
        const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
