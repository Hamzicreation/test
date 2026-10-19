package com.example.mesh.routing

import android.util.Log
import com.example.crypto.CryptoEngine
import com.example.data.BridgeChatRepository
import com.example.data.entity.ContactEntity
import com.example.data.entity.MessageEntity
import com.example.data.entity.MessageStatus
import com.example.data.entity.QueuedPacketEntity
import com.example.mesh.protocol.MeshPacket
import com.example.mesh.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap

data class RouteEntry(
    val destinationId: String,
    val destinationUsername: String,
    val nextHopId: String,
    val hopCount: Int,
    val path: List<String>,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class LiveNetworkNode(
    val nodeId: String,
    val username: String,
    val isDirect: Boolean,
    val hopDistance: Int,
    val nextHop: String?,
    val fullPath: List<String>,
    val rssi: Int = -70,
    val status: String = "Active"
)

class MeshRouter(
    private val repository: BridgeChatRepository,
    private val scope: CoroutineScope
) {
    private val TAG = "MeshRouter"

    // Bounded LRU cache of recently seen packet IDs (keeps last 500 packets)
    private val seenPackets: MutableMap<String, Long> =
        Collections.synchronizedMap(object : LinkedHashMap<String, Long>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 500
            }
        })

    // Routing table: destinationNodeId -> RouteEntry
    private val routingTable = Collections.synchronizedMap(mutableMapOf<String, RouteEntry>())

    // Active direct peers: nodeId -> peer details
    private val directPeers = Collections.synchronizedMap(mutableMapOf<String, LiveNetworkNode>())

    // Observable Live Network Topology for UI
    private val _networkTopology = MutableStateFlow<List<LiveNetworkNode>>(emptyList())
    val networkTopology: StateFlow<List<LiveNetworkNode>> = _networkTopology.asStateFlow()

    // Callback to transport layer to send packets over physical or simulated radio
    var packetSendListener: ((packet: MeshPacket, targetNeighborId: String?) -> Unit)? = null

    // Callback for incoming message notification alerts
    var onIncomingMessageAlert: ((message: MessageEntity) -> Unit)? = null

    init {
        // Periodic route expiration cleanup
        scope.launch(Dispatchers.IO) {
            val user = repository.getOrCreateUserProfile()
            broadcastHello(user.nodeId, user.username)
        }
    }

    /**
     * Broadcasts HELLO beacon announcing presence to all 1-hop direct neighbors
     */
    fun broadcastHello(localNodeId: String, localUsername: String) {
        val helloPacket = MeshPacket(
            type = PacketType.HELLO,
            sourceNodeId = localNodeId,
            sourceUsername = localUsername,
            destinationNodeId = MeshPacket.BROADCAST_DESTINATION,
            ttl = 1, // Only 1 hop for direct presence
            hopCount = 0,
            isEncrypted = false,
            plainPayload = localUsername,
            pathTraveled = listOf(localNodeId)
        )
        sendPacket(helloPacket, null)
    }

    /**
     * Announces known routes to direct neighbors (Distance Vector / DSDV style)
     */
    fun broadcastRouteAnnouncements(localNodeId: String, localUsername: String) {
        val routesJson = JSONArray()
        synchronized(routingTable) {
            for ((destId, route) in routingTable) {
                val obj = JSONObject()
                obj.put("dest", destId)
                obj.put("name", route.destinationUsername)
                obj.put("hops", route.hopCount + 1) // Advertise cost to reach via us
                obj.put("path", JSONArray(route.path))
                routesJson.put(obj)
            }
        }

        val announcePacket = MeshPacket(
            type = PacketType.ROUTE_ANNOUNCE,
            sourceNodeId = localNodeId,
            sourceUsername = localUsername,
            destinationNodeId = MeshPacket.BROADCAST_DESTINATION,
            ttl = 2,
            hopCount = 0,
            isEncrypted = false,
            plainPayload = routesJson.toString(),
            pathTraveled = listOf(localNodeId)
        )
        sendPacket(announcePacket, null)
    }

    /**
     * Handles receiving a raw packet from any radio transport (BLE GATT, RFCOMM, or Simulator)
     */
    fun onPacketReceived(packet: MeshPacket, arrivalNeighborId: String?, localNodeId: String, localUsername: String) {
        scope.launch(Dispatchers.IO) {
            // 1. Deduplication check: drop packet if seen before
            if (seenPackets.containsKey(packet.packetId)) {
                repository.incrementDeduplicated()
                Log.d(TAG, "Duplicate packet ${packet.packetId} dropped")
                return@launch
            }
            seenPackets[packet.packetId] = System.currentTimeMillis()

            // 2. Loop prevention check: if packet already contains local node ID in its traveled path, drop it!
            if (packet.pathTraveled.contains(localNodeId)) {
                repository.incrementDeduplicated()
                Log.d(TAG, "Loop detected for packet ${packet.packetId}, dropped")
                return@launch
            }

            // Update route to packet source
            val neighborId = arrivalNeighborId ?: packet.sourceNodeId
            updateRoute(
                destId = packet.sourceNodeId,
                destUsername = packet.sourceUsername,
                nextHopId = neighborId,
                hops = packet.hopCount + 1,
                path = packet.pathTraveled + localNodeId
            )

            // If packet is from a direct neighbor, record as direct peer
            if (packet.hopCount == 0 || neighborId == packet.sourceNodeId) {
                recordDirectNeighbor(packet.sourceNodeId, packet.sourceUsername, -65)
            }

            repository.incrementReceived()

            // 3. Process based on destination
            val isForUs = (packet.destinationNodeId == localNodeId)
            val isBroadcast = packet.isBroadcast

            if (isForUs) {
                handlePacketForLocalNode(packet, localNodeId, localUsername)
            } else if (isBroadcast) {
                handleBroadcastPacket(packet, localNodeId, localUsername)
            } else {
                // Transit packet: Forward to next hop
                handleTransitPacket(packet, localNodeId)
            }
        }
    }

    /**
     * Processes a packet addressed specifically to this phone
     */
    private suspend fun handlePacketForLocalNode(packet: MeshPacket, localNodeId: String, localUsername: String) {
        val user = repository.getOrCreateUserProfile()

        when (packet.type) {
            PacketType.CHAT_MESSAGE -> {
                var content = packet.plainPayload
                if (packet.isEncrypted && packet.cipherText.isNotEmpty()) {
                    val sharedKey = CryptoEngine.deriveSharedKey(
                        localId = localNodeId,
                        peerId = packet.sourceNodeId,
                        localSecret = user.masterSecret
                    )
                    val decrypted = CryptoEngine.decrypt(
                        cipherTextBase64 = packet.cipherText,
                        ivBase64 = packet.iv,
                        keyBytes = sharedKey
                    )
                    content = decrypted ?: "[Encrypted message - Decryption key mismatch]"
                }

                // Check message type (text or location)
                val msgType = if (content.startsWith("LOC:")) "LOCATION" else "TEXT"
                val displayContent = if (content.startsWith("LOC:")) content.removePrefix("LOC:") else content

                val incomingMsg = MessageEntity(
                    messageId = packet.packetId,
                    conversationId = packet.sourceNodeId,
                    senderId = packet.sourceNodeId,
                    senderName = packet.sourceUsername,
                    recipientId = localNodeId,
                    content = displayContent,
                    messageType = msgType,
                    timestamp = packet.timestamp,
                    status = MessageStatus.DELIVERED.name,
                    hopCount = packet.hopCount,
                    isOutgoing = false,
                    isEncrypted = packet.isEncrypted,
                    routeUsed = packet.pathTraveled.joinToString(" → ")
                )
                repository.saveMessage(incomingMsg)
                onIncomingMessageAlert?.invoke(incomingMsg)

                // Send ACK packet back to sender
                sendAck(
                    targetNodeId = packet.sourceNodeId,
                    originalMessageId = packet.packetId,
                    localNodeId = localNodeId,
                    localUsername = localUsername
                )
            }

            PacketType.ACK -> {
                val ackedMsgId = packet.ackMessageId ?: packet.plainPayload
                if (ackedMsgId.isNotEmpty()) {
                    repository.updateMessageStatus(ackedMsgId, MessageStatus.DELIVERED.name)
                    Log.d(TAG, "Message $ackedMsgId acknowledged as delivered!")
                }
            }

            PacketType.PING -> {
                val pongPacket = MeshPacket(
                    type = PacketType.PONG,
                    sourceNodeId = localNodeId,
                    sourceUsername = localUsername,
                    destinationNodeId = packet.sourceNodeId,
                    ttl = 5,
                    hopCount = 0,
                    isEncrypted = false,
                    plainPayload = "PONG_OK",
                    pathTraveled = listOf(localNodeId)
                )
                sendPacket(pongPacket, null)
            }

            PacketType.PONG -> {
                Log.d(TAG, "Received PONG from ${packet.sourceNodeId}")
            }

            PacketType.HELLO, PacketType.ROUTE_ANNOUNCE -> {
                // Already updated route table above
            }
        }
    }

    /**
     * Processes a broadcast message (e.g. Nearby Mesh Broadcast Channel)
     */
    private suspend fun handleBroadcastPacket(packet: MeshPacket, localNodeId: String, localUsername: String) {
        val user = repository.getOrCreateUserProfile()

        when (packet.type) {
            PacketType.CHAT_MESSAGE -> {
                val msgType = if (packet.plainPayload.startsWith("LOC:")) "LOCATION" else "TEXT"
                val displayContent = if (packet.plainPayload.startsWith("LOC:")) packet.plainPayload.removePrefix("LOC:") else packet.plainPayload

                val broadcastMsg = MessageEntity(
                    messageId = packet.packetId,
                    conversationId = "*",
                    senderId = packet.sourceNodeId,
                    senderName = packet.sourceUsername,
                    recipientId = "*",
                    content = displayContent,
                    messageType = msgType,
                    timestamp = packet.timestamp,
                    status = MessageStatus.DELIVERED.name,
                    hopCount = packet.hopCount,
                    isOutgoing = (packet.sourceNodeId == localNodeId),
                    isEncrypted = false,
                    routeUsed = packet.pathTraveled.joinToString(" → ")
                )
                repository.saveMessage(broadcastMsg)
                if (packet.sourceNodeId != localNodeId) {
                    onIncomingMessageAlert?.invoke(broadcastMsg)
                }

                // If relay enabled and TTL allows, forward to other peers
                if (user.isRelayEnabled && packet.ttl > 1) {
                    forwardPacket(packet, localNodeId)
                }
            }

            PacketType.ROUTE_ANNOUNCE -> {
                parseAndApplyRouteAnnouncements(packet.plainPayload, arrivalNeighborId = packet.sourceNodeId, localNodeId)
            }

            PacketType.HELLO -> {
                // Handled in general route update
            }

            else -> {}
        }
    }

    /**
     * Forwards a transit packet along the mesh to the next hop
     */
    private suspend fun handleTransitPacket(packet: MeshPacket, localNodeId: String) {
        val user = repository.getOrCreateUserProfile()

        // Respect user setting: "Do not act as a relay node"
        if (!user.isRelayEnabled) {
            Log.d(TAG, "Relaying disabled on this device. Dropping transit packet ${packet.packetId}")
            return
        }

        // Hop limit check (TTL)
        if (packet.ttl <= 1) {
            repository.incrementTtlExpired()
            Log.d(TAG, "TTL expired for packet ${packet.packetId}. Dropping.")
            return
        }

        forwardPacket(packet, localNodeId)
    }

    private suspend fun forwardPacket(packet: MeshPacket, localNodeId: String) {
        val forwardedCopy = packet.createForwardCopy(localNodeId)
        repository.incrementRelayed()

        // Lookup next hop in routing table
        val targetHop = routingTable[packet.destinationNodeId]?.nextHopId
        sendPacket(forwardedCopy, targetHop)
    }

    /**
     * Sends an ACK packet back to the sender
     */
    private fun sendAck(targetNodeId: String, originalMessageId: String, localNodeId: String, localUsername: String) {
        val ackPacket = MeshPacket(
            type = PacketType.ACK,
            sourceNodeId = localNodeId,
            sourceUsername = localUsername,
            destinationNodeId = targetNodeId,
            ttl = 6,
            hopCount = 0,
            isEncrypted = false,
            plainPayload = originalMessageId,
            ackMessageId = originalMessageId,
            pathTraveled = listOf(localNodeId)
        )
        val nextHop = routingTable[targetNodeId]?.nextHopId
        sendPacket(ackPacket, nextHop)
    }

    /**
     * Originates an outgoing message created by the local user
     */
    suspend fun sendOutgoingMessage(
        destinationNodeId: String,
        content: String,
        messageType: String = "TEXT",
        localNodeId: String,
        localUsername: String
    ): String {
        val user = repository.getOrCreateUserProfile()
        val messageId = java.util.UUID.randomUUID().toString()
        val isBroadcast = (destinationNodeId == "*")

        var cipherText = ""
        var iv = ""
        var isEncrypted = false
        var plainPayload = ""

        if (isBroadcast) {
            plainPayload = if (messageType == "LOCATION") "LOC:$content" else content
            isEncrypted = false
        } else {
            isEncrypted = true
            val sharedKey = CryptoEngine.deriveSharedKey(
                localId = localNodeId,
                peerId = destinationNodeId,
                localSecret = user.masterSecret
            )
            val toEncrypt = if (messageType == "LOCATION") "LOC:$content" else content
            val enc = CryptoEngine.encrypt(toEncrypt, sharedKey)
            cipherText = enc.cipherTextBase64
            iv = enc.ivBase64
        }

        // Save to Room
        val localMessage = MessageEntity(
            messageId = messageId,
            conversationId = destinationNodeId,
            senderId = localNodeId,
            senderName = localUsername,
            recipientId = destinationNodeId,
            content = content,
            messageType = messageType,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT.name,
            hopCount = 0,
            isOutgoing = true,
            isEncrypted = isEncrypted,
            routeUsed = "Direct / Mesh"
        )
        repository.saveMessage(localMessage)
        repository.incrementSent()

        val packet = MeshPacket(
            packetId = messageId,
            type = PacketType.CHAT_MESSAGE,
            sourceNodeId = localNodeId,
            sourceUsername = localUsername,
            destinationNodeId = destinationNodeId,
            ttl = user.maxTtl,
            hopCount = 0,
            timestamp = System.currentTimeMillis(),
            cipherText = cipherText,
            iv = iv,
            isEncrypted = isEncrypted,
            plainPayload = plainPayload,
            pathTraveled = listOf(localNodeId)
        )

        // Mark as seen locally to prevent reflection
        seenPackets[packet.packetId] = System.currentTimeMillis()

        // Check if destination or route is known
        val targetHop = routingTable[destinationNodeId]?.nextHopId

        if (!isBroadcast && targetHop == null && !directPeers.containsKey(destinationNodeId)) {
            // Queue for store-and-forward when route is discovered
            repository.enqueuePacket(
                QueuedPacketEntity(
                    packetId = packet.packetId,
                    destinationId = destinationNodeId,
                    packetJson = packet.toJson()
                )
            )
            repository.updateMessageStatus(messageId, MessageStatus.QUEUED.name)
            Log.d(TAG, "No route to $destinationNodeId yet, queued in store-and-forward")
        } else {
            sendPacket(packet, targetHop)
        }

        return messageId
    }

    /**
     * Flushes stored messages for a newly discovered peer
     */
    suspend fun flushQueueForDestination(destinationId: String) {
        val queued = repository.getQueuedPacketsForDestination(destinationId)
        for (item in queued) {
            val packet = MeshPacket.fromJson(item.packetJson)
            if (packet != null) {
                val targetHop = routingTable[destinationId]?.nextHopId
                sendPacket(packet, targetHop)
                repository.updateMessageStatus(packet.packetId, MessageStatus.SENT.name)
                repository.dequeuePacket(item.packetId)
            }
        }
    }

    private fun sendPacket(packet: MeshPacket, targetNeighborId: String?) {
        packetSendListener?.invoke(packet, targetNeighborId)
    }

    fun updateRoute(destId: String, destUsername: String, nextHopId: String, hops: Int, path: List<String>) {
        val existing = routingTable[destId]
        if (existing == null || hops < existing.hopCount || (System.currentTimeMillis() - existing.lastUpdated > 30000)) {
            routingTable[destId] = RouteEntry(
                destinationId = destId,
                destinationUsername = destUsername,
                nextHopId = nextHopId,
                hopCount = hops,
                path = path,
                lastUpdated = System.currentTimeMillis()
            )
            rebuildTopology()

            scope.launch(Dispatchers.IO) {
                // Update contact entity
                val contact = ContactEntity(
                    nodeId = destId,
                    username = destUsername,
                    status = if (hops == 1) "Direct (1 hop)" else "Via $nextHopId ($hops hops)",
                    isDirectNeighbor = (hops == 1),
                    hopDistance = hops,
                    viaNodeId = nextHopId,
                    routePath = path.joinToString(","),
                    rssi = if (hops == 1) -65 else -85,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    isBlocked = false,
                    isTrusted = true,
                    avatarColorHex = getDeterministicColor(destId)
                )
                repository.upsertContact(contact)

                // Flush any queued packets for this destination
                flushQueueForDestination(destId)
            }
        }
    }

    fun recordDirectNeighbor(nodeId: String, username: String, rssi: Int) {
        directPeers[nodeId] = LiveNetworkNode(
            nodeId = nodeId,
            username = username,
            isDirect = true,
            hopDistance = 1,
            nextHop = null,
            fullPath = listOf(nodeId),
            rssi = rssi,
            status = "Direct Bluetooth"
        )
        updateRoute(nodeId, username, nodeId, 1, listOf(nodeId))
    }

    fun removeDirectNeighbor(nodeId: String) {
        directPeers.remove(nodeId)
        // Invalidate routes where nextHop was this disconnected node
        synchronized(routingTable) {
            val toRemove = routingTable.filter { it.value.nextHopId == nodeId }.keys
            for (key in toRemove) {
                routingTable.remove(key)
            }
        }
        rebuildTopology()
    }

    private fun parseAndApplyRouteAnnouncements(payload: String, arrivalNeighborId: String, localNodeId: String) {
        try {
            val array = JSONArray(payload)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val destId = obj.getString("dest")
                if (destId == localNodeId) continue // Don't add ourselves

                val name = obj.optString("name", "Peer")
                val hops = obj.getInt("hops")
                val pathArray = obj.optJSONArray("path")
                val path = mutableListOf<String>()
                if (pathArray != null) {
                    for (j in 0 until pathArray.length()) {
                        path.add(pathArray.getString(j))
                    }
                }
                path.add(localNodeId)

                updateRoute(destId, name, arrivalNeighborId, hops, path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing route announcements", e)
        }
    }

    private fun rebuildTopology() {
        val list = mutableListOf<LiveNetworkNode>()
        synchronized(directPeers) {
            list.addAll(directPeers.values)
        }
        synchronized(routingTable) {
            for ((id, route) in routingTable) {
                if (route.hopCount > 1 && !list.any { it.nodeId == id }) {
                    list.add(
                        LiveNetworkNode(
                            nodeId = id,
                            username = route.destinationUsername,
                            isDirect = false,
                            hopDistance = route.hopCount,
                            nextHop = route.nextHopId,
                            fullPath = route.path,
                            rssi = -85,
                            status = "Reachable via ${route.nextHopId}"
                        )
                    )
                }
            }
        }
        _networkTopology.value = list
    }

    private fun getDeterministicColor(nodeId: String): String {
        val colors = listOf("#00A884", "#25D366", "#38BDF8", "#F59E0B", "#EC4899", "#8B5CF6")
        val index = (nodeId.hashCode() and 0x7FFFFFFF) % colors.size
        return colors[index]
    }
}
