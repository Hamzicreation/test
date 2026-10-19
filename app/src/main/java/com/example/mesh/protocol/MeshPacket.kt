package com.example.mesh.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PacketType {
    HELLO,
    ROUTE_ANNOUNCE,
    CHAT_MESSAGE,
    ACK,
    PING,
    PONG
}

data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val type: PacketType,
    val sourceNodeId: String,
    val sourceUsername: String,
    val destinationNodeId: String, // Specific ID or BROADCAST ("*")
    val ttl: Int = 5,
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val cipherText: String = "",
    val iv: String = "",
    val isEncrypted: Boolean = true,
    val plainPayload: String = "", // Used for HELLO, ROUTE announcements, public channel
    val pathTraveled: List<String> = emptyList(), // Records hops e.g. ["BC-A1", "BC-B2"]
    val ackMessageId: String? = null // For ACK packets, references the messageId being acknowledged
) {
    companion object {
        const val BROADCAST_DESTINATION = "*"
        const val DEFAULT_MAX_TTL = 7

        fun fromJson(jsonStr: String): MeshPacket? {
            return try {
                val json = JSONObject(jsonStr)
                val pathList = mutableListOf<String>()
                val pathArray = json.optJSONArray("path")
                if (pathArray != null) {
                    for (i in 0 until pathArray.length()) {
                        pathList.add(pathArray.getString(i))
                    }
                }

                MeshPacket(
                    packetId = json.getString("id"),
                    type = PacketType.valueOf(json.getString("type")),
                    sourceNodeId = json.getString("src"),
                    sourceUsername = json.optString("srcName", "Peer"),
                    destinationNodeId = json.getString("dst"),
                    ttl = json.optInt("ttl", 5),
                    hopCount = json.optInt("hops", 0),
                    timestamp = json.optLong("ts", System.currentTimeMillis()),
                    cipherText = json.optString("cipher", ""),
                    iv = json.optString("iv", ""),
                    isEncrypted = json.optBoolean("enc", false),
                    plainPayload = json.optString("payload", ""),
                    pathTraveled = pathList,
                    ackMessageId = if (json.has("ackId")) json.getString("ackId") else null
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("id", packetId)
        json.put("type", type.name)
        json.put("src", sourceNodeId)
        json.put("srcName", sourceUsername)
        json.put("dst", destinationNodeId)
        json.put("ttl", ttl)
        json.put("hops", hopCount)
        json.put("ts", timestamp)
        json.put("cipher", cipherText)
        json.put("iv", iv)
        json.put("enc", isEncrypted)
        json.put("payload", plainPayload)
        json.put("path", JSONArray(pathTraveled))
        if (ackMessageId != null) {
            json.put("ackId", ackMessageId)
        }
        return json.toString()
    }

    /**
     * Creates a forwarded copy of this packet for the next hop:
     * - ttl is decremented
     * - hopCount is incremented
     * - relayNodeId is appended to pathTraveled
     */
    fun createForwardCopy(relayNodeId: String): MeshPacket {
        val updatedPath = pathTraveled.toMutableList()
        if (!updatedPath.contains(relayNodeId)) {
            updatedPath.add(relayNodeId)
        }
        return copy(
            ttl = ttl - 1,
            hopCount = hopCount + 1,
            pathTraveled = updatedPath
        )
    }

    val isBroadcast: Boolean
        get() = destinationNodeId == BROADCAST_DESTINATION
}
