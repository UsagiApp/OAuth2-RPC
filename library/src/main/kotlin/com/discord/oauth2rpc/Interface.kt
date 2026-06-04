package com.discord.oauth2rpc

import org.json.JSONObject

data class TokenResponse(
    val tokenType: String,
    val accessToken: String,
    val expiresIn: Int,
    val refreshToken: String,
    val scope: String,
    val idToken: String? = null
) {
    companion object {
        fun fromJson(json: String): TokenResponse {
            val obj = JSONObject(json)
            return TokenResponse(
                tokenType = obj.getString("token_type"),
                accessToken = obj.getString("access_token"),
                expiresIn = obj.getInt("expires_in"),
                refreshToken = obj.getString("refresh_token"),
                scope = obj.optString("scope", ""),
                idToken = obj.optString("id_token", null)
            )
        }
    }
}

data class SessionState(
    val sessionId: String,
    val seq: Int,
    val resumeGatewayUrl: String
)

data class IdentifyPayload(
    val capabilities: Int? = null,
    val intents: Int? = null,
    val properties: Map<String, Any>? = null,
    val extra: Map<String, Any> = emptyMap()
)

data class GatewayConnectOptions(
    val token: String,
    val identify: IdentifyPayload? = null,
    val session: SessionState? = null,
    val gatewayUrl: String? = null,
    val version: Int? = null,
    val wsHeaders: Map<String, String>? = null,
    val helloTimeoutMs: Long? = null
)

data class GatewayPacket(
    val op: Int,
    val d: Any?,
    val s: Int?,
    val t: String?
)

data class GatewayCloseInfo(
    val code: Int,
    val reason: String,
    val resumable: Boolean,
    val session: SessionState?
)

data class SessionUpdateEvent(
    val sessionId: String?,
    val seq: Int,
    val resumeGatewayUrl: String?
)

data class ReadyEvent(
    val user: ReadyUser,
    val sessionId: String,
    val resumeGatewayUrl: String
) {
    companion object {
        fun fromJson(obj: JSONObject): ReadyEvent {
            val userObj = obj.getJSONObject("user")
            return ReadyEvent(
                user = ReadyUser(
                    id = userObj.getString("id"),
                    username = userObj.getString("username"),
                    globalName = userObj.optString("global_name", null)
                ),
                sessionId = obj.getString("session_id"),
                resumeGatewayUrl = obj.getString("resume_gateway_url")
            )
        }
    }
}

data class ReadyUser(
    val id: String,
    val username: String,
    val globalName: String? = null
)
