package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserEntity(
    @PrimaryKey val nodeId: String,
    val username: String,
    val statusMessage: String = "Available on BridgeChat mesh",
    val avatarColorHex: String = "#00A884",
    val masterSecret: String,
    val isRelayEnabled: Boolean = true,
    val maxTtl: Int = 6,
    val isDiscoverable: Boolean = true
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val nodeId: String,
    val username: String,
    val status: String = "Discovered via mesh",
    val isDirectNeighbor: Boolean = false,
    val hopDistance: Int = 1,
    val viaNodeId: String? = null,
    val routePath: String = "",
    val rssi: Int = -70,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,
    val isTrusted: Boolean = true,
    val avatarColorHex: String = "#25D366"
)

enum class MessageStatus {
    QUEUED,     // Waiting for route/peer
    SENDING,    // Currently transmitting over radio
    SENT,       // Left local phone (1 tick)
    DELIVERED,  // Reached destination phone (double tick)
    READ,       // Recipient opened conversation (double blue tick)
    FAILED      // Hop TTL expired or node unreachable
}

enum class MessageType {
    TEXT,
    LOCATION,
    ATTACHMENT,
    STATUS
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String, // Contact nodeId or "broadcast_channel"
    val senderId: String,
    val senderName: String,
    val recipientId: String,
    val content: String,
    val messageType: String = MessageType.TEXT.name,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = MessageStatus.SENT.name,
    val hopCount: Int = 0,
    val isOutgoing: Boolean = true,
    val isEncrypted: Boolean = true,
    val routeUsed: String = ""
)

@Entity(tableName = "queued_packets")
data class QueuedPacketEntity(
    @PrimaryKey val packetId: String,
    val destinationId: String,
    val packetJson: String,
    val queuedTimestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val expiresAt: Long = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // 24 hours
)

@Entity(tableName = "mesh_stats")
data class MeshStatsEntity(
    @PrimaryKey val id: Int = 1,
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val packetsRelayed: Int = 0,
    val packetsDeduplicated: Int = 0,
    val packetsTtlExpired: Int = 0
)
