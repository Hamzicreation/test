package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.ContactEntity
import com.example.data.entity.MeshStatsEntity
import com.example.data.entity.MessageEntity
import com.example.data.entity.QueuedPacketEntity
import com.example.data.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM user_profile LIMIT 1")
    fun getUserProfile(): Flow<UserEntity?>

    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun getUserProfileOnce(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserProfile(user: UserEntity)

    @Query("UPDATE user_profile SET isRelayEnabled = :enabled WHERE nodeId = :nodeId")
    suspend fun setRelayEnabled(nodeId: String, enabled: Boolean)

    @Query("UPDATE user_profile SET username = :username, statusMessage = :status WHERE nodeId = :nodeId")
    suspend fun updateNameAndStatus(nodeId: String, username: String, status: String)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY isDirectNeighbor DESC, lastSeenTimestamp DESC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isBlocked = 0 ORDER BY lastSeenTimestamp DESC")
    fun getUnblockedContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getContactById(nodeId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContacts(contacts: List<ContactEntity>)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE nodeId = :nodeId")
    suspend fun setBlocked(nodeId: String, blocked: Boolean)

    @Query("UPDATE contacts SET isDirectNeighbor = :isDirect, rssi = :rssi, lastSeenTimestamp = :timestamp WHERE nodeId = :nodeId")
    suspend fun updateDirectNeighbor(nodeId: String, isDirect: Boolean, rssi: Int, timestamp: Long)

    @Query("DELETE FROM contacts WHERE nodeId = :nodeId")
    suspend fun deleteContact(nodeId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessages(query: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE messageId IN (:messageIds)")
    suspend fun updateStatuses(messageIds: List<String>, status: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: String)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queued_packets WHERE destinationId = :destinationId OR destinationId = '*' ORDER BY queuedTimestamp ASC")
    suspend fun getPacketsForDestination(destinationId: String): List<QueuedPacketEntity>

    @Query("SELECT * FROM queued_packets ORDER BY queuedTimestamp ASC")
    fun getAllQueuedPacketsFlow(): Flow<List<QueuedPacketEntity>>

    @Query("SELECT * FROM queued_packets ORDER BY queuedTimestamp ASC")
    suspend fun getAllQueuedPackets(): List<QueuedPacketEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(packet: QueuedPacketEntity)

    @Query("DELETE FROM queued_packets WHERE packetId = :packetId")
    suspend fun dequeue(packetId: String)

    @Query("DELETE FROM queued_packets WHERE expiresAt < :currentTime")
    suspend fun purgeExpired(currentTime: Long)
}

@Dao
interface MeshStatsDao {
    @Query("SELECT * FROM mesh_stats WHERE id = 1 LIMIT 1")
    fun getStats(): Flow<MeshStatsEntity?>

    @Query("SELECT * FROM mesh_stats WHERE id = 1 LIMIT 1")
    suspend fun getStatsOnce(): MeshStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStats(stats: MeshStatsEntity)

    @Query("UPDATE mesh_stats SET packetsSent = packetsSent + 1 WHERE id = 1")
    suspend fun incrementSent()

    @Query("UPDATE mesh_stats SET packetsReceived = packetsReceived + 1 WHERE id = 1")
    suspend fun incrementReceived()

    @Query("UPDATE mesh_stats SET packetsRelayed = packetsRelayed + 1 WHERE id = 1")
    suspend fun incrementRelayed()

    @Query("UPDATE mesh_stats SET packetsDeduplicated = packetsDeduplicated + 1 WHERE id = 1")
    suspend fun incrementDeduplicated()

    @Query("UPDATE mesh_stats SET packetsTtlExpired = packetsTtlExpired + 1 WHERE id = 1")
    suspend fun incrementTtlExpired()
}
