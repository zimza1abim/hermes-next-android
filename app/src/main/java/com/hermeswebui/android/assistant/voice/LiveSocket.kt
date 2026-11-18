package com.hermeswebui.android.assistant.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The one websocket a call runs on.
 *
 * Gemini sends small JSON frames; a long answer's audio arrives as many of them, and a message-size cap
 * is the classic way to lose the tail of an answer (the iOS lesson). OkHttp has no small default here,
 * but this class never limits size and never drops a frame because it is large.
 */
class LiveSocket(
    private val onOpened: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (String?) -> Unit,
    client: OkHttpClient = defaultClient(),
) {
    private val client = client
    private var socket: WebSocket? = null

    /** onClosing and onClosed both fire for one close; two reports scheduled two reconnects. */
    private val closeReported = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A close this class asked for: its callbacks are not a dropped connection. */
    @Volatile
    private var closingByUs = false

    fun connect(url: String) {
        close()
        closeReported.set(false)
        closingByUs = false
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpened()

            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

            /** The server may answer in binary frames; JSON is UTF-8 either way. */
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = onText(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                report(reason.ifBlank { "closed $code" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                report(t.message ?: "failure ${response?.code ?: 0}")
            }
        })
    }

    fun send(text: String): Boolean = socket?.send(text) == true

    private fun report(reason: String) {
        if (closingByUs) return
        if (!closeReported.compareAndSet(false, true)) return
        onClosed(reason)
    }

    fun close() {
        closingByUs = true
        socket?.let {
            // Graceful close first; cancel() would cut the handshake the server needs to free the
            // session, and a session left open counts against the quota.
            if (!it.close(1000, null)) it.cancel()
        }
        socket = null
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
