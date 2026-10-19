package com.example.data

import com.example.crypto.CryptoEngine
import com.example.data.entity.ContactEntity
import com.example.data.entity.MeshStatsEntity
import com.example.data.entity.MessageEntity
import com.example.data.entity.QueuedPacketEntity
import com.example.data.entity.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

class BridgeChatRepository(private val database: BridgeChatDatabase) {
    val userDao = database.userDao()
    val contactDao = database.contactDao()
    val messageDao = database.messageDao()
    val queueDao = database.queueDao()
    val statsDao = database.meshStatsDao()

    val userProfile: Flow<UserEntity?> = userDao.getUserProfile()
    val allContacts: Flow<List<ContactEntity>> = contactDao.getAllContacts()
    val unblockedContacts: Flow<List<ContactEntity>> = contactDao.getUnblockedContacts()
    val allMessages: Flow<List<MessageEntity>> = messageDao.getAllMessages()
    val meshStats: Flow<MeshStatsEntity?> = statsDao.getStats()
    val queuedPackets: Flow<List<QueuedPacketEntity>> = queueDao.getAllQueuedPacketsFlow()

    suspend fun getOrCreateUserProfile(): UserEntity {
        val existing = userDao.getUserProfileOnce()
        if (existing != null) return existing

        val newId = CryptoEngine.generateDeviceId()
        val masterKey = CryptoEngine.generateMasterKey()
        val defaultProfile = UserEntity(
            nodeId = newId,
            username = "Node-${newId.removePrefix("BC-")}",
            statusMessage = "Online via BridgeChat Mesh",
            avatarColorHex = "#00A884",
            masterSecret = masterKey,
            isRelayEnabled = true,
            maxTtl = 6,
            isDiscoverable = true
        )
        userDao.saveUserProfile(defaultProfile)

        // Initialize default stats row
        val initialStats = statsDao.getStatsOnce()
        if (initialStats == null) {
            statsDao.saveStats(MeshStatsEntity())
        }

        // Add standard "Nearby Mesh Broadcast" public channel contact
        contactDao.upsertContact(
            ContactEntity(
                nodeId = "*",
                username = "Nearby Mesh Broadcast",
                status = "Public channel (reaches all connected mesh nodes)",
                isDirectNeighbor = true,
                hopDistance = 0,
                viaNodeId = null,
                routePath = "broadcast",
                rssi = 0,
                lastSeenTimestamp = System.currentTimeMillis(),
                isBlocked = false,
                isTrusted = true,
                avatarColorHex = "#25D366"
            )
        )

        return defaultProfile
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    suspend fun saveMessage(message: MessageEntity) =
        messageDao.insertMessage(message)

    suspend fun updateMessageStatus(messageId: String, status: String) =
        messageDao.updateStatus(messageId, status)

    suspend fun deleteMessage(messageId: String) =
        messageDao.deleteMessage(messageId)

    suspend fun clearConversation(conversationId: String) =
        messageDao.clearConversation(conversationId)

    suspend fun upsertContact(contact: ContactEntity) =
        contactDao.upsertContact(contact)

    suspend fun setContactBlocked(nodeId: String, blocked: Boolean) =
        contactDao.setBlocked(nodeId, blocked)

    suspend fun setRelayEnabled(nodeId: String, enabled: Boolean) =
        userDao.setRelayEnabled(nodeId, enabled)

    suspend fun updateProfile(nodeId: String, username: String, status: String) =
        userDao.updateNameAndStatus(nodeId, username, status)

    suspend fun enqueuePacket(packet: QueuedPacketEntity) =
        queueDao.enqueue(packet)

    suspend fun dequeuePacket(packetId: String) =
        queueDao.dequeue(packetId)

    suspend fun getQueuedPacketsForDestination(destId: String): List<QueuedPacketEntity> =
        queueDao.getPacketsForDestination(destId)

    suspend fun purgeExpiredQueue() =
        queueDao.purgeExpired(System.currentTimeMillis())

    suspend fun incrementSent() = statsDao.incrementSent()
    suspend fun incrementReceived() = statsDao.incrementReceived()
    suspend fun incrementRelayed() = statsDao.incrementRelayed()
    suspend fun incrementDeduplicated() = statsDao.incrementDeduplicated()
    suspend fun incrementTtlExpired() = statsDao.incrementTtlExpired()
}
