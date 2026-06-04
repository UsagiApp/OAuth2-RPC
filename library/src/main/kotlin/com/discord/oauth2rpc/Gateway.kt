package com.discord.oauth2rpc

import com.discord.oauth2rpc.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

private sealed class GatewayFrame {
    data class Text(val text: String) : GatewayFrame()
    data class Close(val code: Int, val reason: String) : GatewayFrame()
}

class GatewayClient {

    private var httpClient: OkHttpClient? = null
    private var wsSession: WebSocket? = null
    private var processingJob: Job? = null
    private var heartbeatJob: Job? = null
    private var helloTimerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val incomingChannel = Channel<GatewayFrame>(Channel.UNLIMITED)

    private var lastAck = true
    private var lastHeartbeatAt = 0L
    private var ping = -1

    private var sessionState: SessionState? = null
    private var liveSeq = 0
    private var token = ""
    private var closed = false

    var onReady: ((ReadyEvent) -> Unit)? = null
    var onClose: ((GatewayCloseInfo) -> Unit)? = null
    var onDispatch: ((eventName: String, data: Any?, seq: Int?) -> Unit)? = null
    var onSent: ((Any?) -> Unit)? = null
    var onSession: ((SessionUpdateEvent) -> Unit)? = null
    var onInvalidSession: ((Boolean) -> Unit)? = null
    var onOpen: (() -> Unit)? = null
    var onHello: ((heartbeatInterval: Int) -> Unit)? = null
    var onIdentify: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onResumed: ((Any?) -> Unit)? = null
    var onPacket: ((GatewayPacket) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onDebug: ((String) -> Unit)? = null

    val latency: Int get() = ping

    fun getSession(): SessionState? = sessionState?.copy()

    suspend fun connect(opts: GatewayConnectOptions) {
        if (wsSession != null) throw IllegalStateException("GatewayClient already connected")

        token = opts.token
        sessionState = opts.session?.copy()
        liveSeq = opts.session?.seq ?: 0
        closed = false
        lastAck = true

        val base = opts.session?.resumeGatewayUrl ?: opts.gatewayUrl ?: DEFAULTS.GATEWAY_URL
        val version = opts.version ?: DEFAULTS.GATEWAY_VERSION
        val url = "$base/?v=$version&encoding=json"
        val ready = CompletableDeferred<Unit>()

        debug("[gateway] connecting $url")

        httpClient = OkHttpClient.Builder()
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        wsSession = httpClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.close()
                onOpen?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { incomingChannel.send(GatewayFrame.Text(text)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { incomingChannel.send(GatewayFrame.Close(code, reason)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { incomingChannel.send(GatewayFrame.Close(code, reason)) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                onError?.invoke(t)
            }
        })

        val helloTimeout = opts.helloTimeoutMs ?: DEFAULTS.HELLO_TIMEOUT_MS
        helloTimerJob = scope.launch {
            delay(helloTimeout)
            debug("[gateway] HELLO timeout")
            wsSession?.close(4009, "HELLO timeout")
            ready.completeExceptionally(Exception("HELLO timeout"))
        }

        processingJob = scope.launch {
            try {
                for (frame in incomingChannel) {
                    when (frame) {
                        is GatewayFrame.Text -> handleMessage(frame.text, opts, ready)
                        is GatewayFrame.Close -> {
                            handleClose(frame.reason, frame.code)
                            if (!ready.isCompleted) ready.completeExceptionally(Exception("Gateway closed before ready"))
                        }
                    }
                }
            } catch (e: Exception) {
                if (!ready.isCompleted) ready.completeExceptionally(e)
                onError?.invoke(e)
            }
        }

        ready.await()
    }

    fun send(op: Int, d: Any?): Boolean {
        val session = wsSession ?: return false
        scope.launch {
            try {
                val jsonStr = buildJsonString(op, d)
                session.send(jsonStr)
                onSent?.invoke(mapOf("op" to op, "d" to d))
            } catch (e: Exception) { onError?.invoke(e) }
        }
        return true
    }

    private fun buildJsonString(op: Int, d: Any?): String {
        val json = JSONObject()
        json.put("op", op)
        when (d) {
            is JSONObject -> json.put("d", d)
            is JSONArray -> json.put("d", d)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                json.put("d", JSONObject(JsonObjectMapper.mapToJson(d as Map<String, Any?>)))
            }
            is Number -> json.put("d", d.toInt())
            is String -> json.put("d", d)
            is Boolean -> json.put("d", d)
            null -> json.put("d", JSONObject.NULL)
            else -> json.put("d", d.toString())
        }
        return json.toString()
    }

    fun close(code: Int = 1000, reason: String? = null) {
        scope.launch { wsSession?.close(code, reason ?: "") }
    }

    fun disconnect() {
        closed = true
        stopHeartbeat()
        helloTimerJob?.cancel()
        processingJob?.cancel()
        scope.launch {
            wsSession?.close(1000, "Client disconnect")
            wsSession = null
            httpClient?.dispatcher?.executorService?.shutdown()
            httpClient = null
        }
    }

    private fun handleMessage(raw: String, opts: GatewayConnectOptions, ready: CompletableDeferred<Unit>) {
        try {
            val json = JSONObject(raw)
            val op = json.getInt("op")
            val d = json.opt("d")
            val s = if (json.has("s") && !json.isNull("s")) json.getInt("s") else null
            val t = json.optString("t", null)

            if (s != null && s > liveSeq) { liveSeq = s; touchSession(seq = s) }

            onPacket?.invoke(GatewayPacket(op, d, s, t))

            when (op) {
                GatewayOp.HELLO -> {
                    helloTimerJob?.cancel()
                    val dObj = d as JSONObject
                    val interval = dObj.getInt("heartbeat_interval")
                    startHeartbeat(interval.toLong())
                    debug("[gateway] HELLO received, heartbeat_interval=${interval}ms")
                    onHello?.invoke(interval)
                    if (sessionState != null) sendResume() else sendIdentify(opts)
                }
                GatewayOp.HEARTBEAT_ACK -> {
                    lastAck = true
                    ping = (System.currentTimeMillis() - lastHeartbeatAt).toInt()
                    debug("[gateway] heartbeat ack (${ping}ms)")
                }
                GatewayOp.HEARTBEAT -> {
                    debug("[gateway] received server heartbeat, sending forced heartbeat")
                    sendHeartbeat(force = true)
                }
                GatewayOp.RECONNECT -> {
                    debug("[gateway] server requested RECONNECT")
                    forceClose(4000, "server reconnect")
                }
                GatewayOp.INVALID_SESSION -> {
                    val resumable = if (d is Boolean) d else false
                    debug("[gateway] INVALID_SESSION resumable=$resumable")
                    if (!resumable) { sessionState = null; touchSession(sessionId = null, resumeGatewayUrl = null, seq = 0) }
                    onInvalidSession?.invoke(resumable)
                    forceClose(if (resumable) 4000 else 1000, "invalid session")
                }
                GatewayOp.DISPATCH -> handleDispatch(t ?: "", d, s)
            }
        } catch (e: Exception) { onError?.invoke(e) }
    }

    private fun handleDispatch(t: String, d: Any?, s: Int?) {
        when (t) {
            "READY" -> {
                val obj = d as JSONObject
                val re = ReadyEvent.fromJson(obj)
                debug("[gateway] READY: user=${re.user.username} (${re.user.id}) session=${re.sessionId}")
                sessionState = SessionState(re.sessionId, liveSeq, re.resumeGatewayUrl)
                touchSession(re.sessionId, liveSeq, re.resumeGatewayUrl)
                onReady?.invoke(re)
            }
            "RESUMED" -> {
                debug("[gateway] RESUMED: session restored, seq=$liveSeq")
                touchSession(sessionState?.sessionId, liveSeq, sessionState?.resumeGatewayUrl)
                onResumed?.invoke(d)
            }
            else -> debug("[gateway] dispatch $t seq=${s ?: liveSeq}")
        }
        onDispatch?.invoke(t, d, s)
    }

    private fun sendIdentify(opts: GatewayConnectOptions) {
        val id = opts.identify ?: IdentifyPayload()
        val caps = GatewayCapabilities(id.capabilities ?: 0).apply {
            if (id.capabilities == null) {
                add(GatewayCapabilities.FLAGS["DEDUPE_USER_OBJECTS"]!!)
                add(GatewayCapabilities.FLAGS["PRIORITIZED_READY_PAYLOAD"]!!)
                add(GatewayCapabilities.FLAGS["AUTO_CALL_CONNECT"]!!)
                add(GatewayCapabilities.FLAGS["AUTO_LOBBY_CONNECT"]!!)
            }
            freeze()
        }
        val ints = Intents(id.intents ?: 0).apply {
            if (id.intents == null) {
                add(Intents.FLAGS["DIRECT_MESSAGES"]!!); add(Intents.FLAGS["PRIVATE_CHANNELS"]!!)
                add(Intents.FLAGS["CALLS"]!!); add(Intents.FLAGS["USER_RELATIONSHIPS"]!!)
                add(Intents.FLAGS["USER_PRESENCE"]!!); add(Intents.FLAGS["LOBBIES"]!!)
                add(Intents.FLAGS["LOBBY_DELETE"]!!); add(Intents.FLAGS["UNKNOWN_29"]!!)
            }
            freeze()
        }
        val d = JSONObject()
        d.put("capabilities", caps.bitfield)
        d.put("intents", ints.bitfield)
        d.put("token", token)
        val properties = JSONObject()
        DEFAULT_SUPER_PROPERTIES.forEach { (k, v) ->
            when (v) { is String -> properties.put(k, v); is Int -> properties.put(k, v); is Boolean -> properties.put(k, v) }
        }
        d.put("properties", properties)
        onIdentify?.invoke(); debug("[gateway] sending IDENTIFY")
        send(GatewayOp.IDENTIFY, d)
    }

    private fun sendResume() {
        val s = sessionState ?: return
        onResume?.invoke(); debug("[gateway] sending RESUME")
        val d = JSONObject()
        d.put("token", token); d.put("session_id", s.sessionId); d.put("seq", s.seq)
        send(GatewayOp.RESUME, d)
    }

    private fun startHeartbeat(intervalMs: Long) {
        stopHeartbeat()
        debug("[gateway] heartbeat every ${intervalMs}ms")
        val firstDelay = (intervalMs * Random.nextDouble()).toLong()
        heartbeatJob = scope.launch {
            delay(firstDelay)
            if (wsSession != null) {
                sendHeartbeat()
                while (isActive) { delay(intervalMs); sendHeartbeat() }
            }
        }
    }

    private fun sendHeartbeat(force: Boolean = false) {
        if (!force && !lastAck) { debug("[gateway] zombie connection; closing 4009"); forceClose(4009, "heartbeat ack missed"); return }
        lastAck = false; lastHeartbeatAt = System.currentTimeMillis()
        val seq = if (liveSeq > 0) liveSeq else null
        send(GatewayOp.HEARTBEAT, seq)
        debug("[gateway] heartbeat dispatched seq=$seq")
    }

    private fun stopHeartbeat() { heartbeatJob?.cancel(); heartbeatJob = null }

    private fun touchSession(sessionId: String? = null, seq: Int = liveSeq, resumeGatewayUrl: String? = null) {
        onSession?.invoke(SessionUpdateEvent(sessionId ?: sessionState?.sessionId, seq, resumeGatewayUrl ?: sessionState?.resumeGatewayUrl))
    }

    private fun forceClose(code: Int, reason: String) {
        scope.launch { wsSession?.close(code, reason) }
    }

    private fun handleClose(reason: String, code: Int) {
        if (closed) return
        closed = true; stopHeartbeat(); helloTimerJob?.cancel()
        val fatal = NON_RESUMABLE_CLOSE_CODES.contains(code)
        val snapshot = sessionState?.copy(seq = liveSeq)
        if (fatal) sessionState = null
        wsSession = null
        onClose?.invoke(GatewayCloseInfo(code, reason, !fatal && snapshot != null, snapshot))
        debug("[gateway] close code=$code reason=$reason resumable=${!fatal && snapshot != null}")
    }

    private fun debug(msg: String) { onDebug?.invoke(msg) }
}
