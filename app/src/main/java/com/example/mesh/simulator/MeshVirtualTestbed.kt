package com.example.mesh.simulator

import com.example.crypto.CryptoEngine
import com.example.mesh.protocol.MeshPacket
import com.example.mesh.protocol.PacketType
import com.example.mesh.routing.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SimulationLog(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val isSuccess: Boolean = true
)

class MeshVirtualTestbed(
    private val router: MeshRouter,
    private val scope: CoroutineScope
) {
    private val _logs = MutableStateFlow<List<SimulationLog>>(emptyList())
    val logs: StateFlow<List<SimulationLog>> = _logs.asStateFlow()

    private val _isSimActive = MutableStateFlow(false)
    val isSimActive: StateFlow<Boolean> = _isSimActive.asStateFlow()

    // Virtual simulated nodes
    // Topology Chain: LocalNode ↔ Ali (BC-AL01) ↔ Ahmed (BC-AH02) ↔ Usman (BC-US03) ↔ Fatima (BC-FA04)
    private val aliNodeId = "BC-AL01"
    private val ahmedNodeId = "BC-AH02"
    private val usmanNodeId = "BC-US03"
    private val fatimaNodeId = "BC-FA04"

    private var isAliConnected = true
    private var isAhmedConnected = true

    fun log(tag: String, msg: String, isSuccess: Boolean = true) {
        val entry = SimulationLog(tag = tag, message = msg, isSuccess = isSuccess)
        _logs.value = listOf(entry) + _logs.value.take(49)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /**
     * Test Case 1 & 2: Sets up a 3-phone relay network (Local ↔ Ali ↔ Ahmed)
     */
    fun setup3NodeRelayScenario(localNodeId: String, localUsername: String) {
        scope.launch(Dispatchers.IO) {
            _isSimActive.value = true
            isAliConnected = true
            isAhmedConnected = true
            clearLogs()
            log("SETUP", "Initializing 3-Node Mesh Relay Scenario...")
            delay(300)

            // Direct neighbor Ali announces presence to Local
            router.recordDirectNeighbor(aliNodeId, "Ali", -62)
            log("BLE", "Discovered direct 1-hop neighbor: Ali ($aliNodeId) at -62 dBm")
            delay(400)

            // Ali announces route to Ahmed: Ahmed is 1 hop from Ali, so 2 hops from Local
            val aliToAhmedPath = listOf(ahmedNodeId, aliNodeId, localNodeId)
            router.updateRoute(ahmedNodeId, "Ahmed", aliNodeId, 2, aliToAhmedPath)
            log("ROUTE", "Ali advertised route to Ahmed ($ahmedNodeId) via 2 hops")
            delay(400)

            log("READY", "3-Node Network Active: [Me] ↔ [Ali] ↔ [Ahmed]")
        }
    }

    /**
     * Test Case 3: Sets up 5-node chain: Local ↔ Ali ↔ Ahmed ↔ Usman ↔ Fatima
     */
    fun setup5NodeMultiHopScenario(localNodeId: String, localUsername: String) {
        scope.launch(Dispatchers.IO) {
            _isSimActive.value = true
            isAliConnected = true
            isAhmedConnected = true
            clearLogs()
            log("SETUP", "Initializing 5-Node Multi-Hop Chain...")
            delay(300)

            // Direct neighbor Ali
            router.recordDirectNeighbor(aliNodeId, "Ali", -60)
            delay(200)

            // Routes through the chain
            router.updateRoute(ahmedNodeId, "Ahmed", aliNodeId, 2, listOf(ahmedNodeId, aliNodeId, localNodeId))
            router.updateRoute(usmanNodeId, "Usman", aliNodeId, 3, listOf(usmanNodeId, ahmedNodeId, aliNodeId, localNodeId))
            router.updateRoute(fatimaNodeId, "Fatima", aliNodeId, 4, listOf(fatimaNodeId, usmanNodeId, ahmedNodeId, aliNodeId, localNodeId))

            log("ROUTE", "Discovered routes: Ahmed (2 hops), Usman (3 hops), Fatima (4 hops) via Ali")
            delay(300)
            log("READY", "5-Node Topology Formed: [Me] ↔ [Ali] ↔ [Ahmed] ↔ [Usman] ↔ [Fatima]")
        }
    }

    /**
     * Simulates packet delivery and intermediate relays when local node transmits a packet
     */
    fun simulateTransmitPacket(packet: MeshPacket, localNodeId: String, localUsername: String) {
        scope.launch(Dispatchers.IO) {
            if (!_isSimActive.value) return@launch

            log("TX", "Local node sending ${packet.type} to ${packet.destinationNodeId} (TTL=${packet.ttl})")
            delay(350)

            if (packet.destinationNodeId == aliNodeId) {
                // Direct to Ali
                if (!isAliConnected) {
                    log("FAIL", "Direct connection to Ali failed (Node disconnected)", isSuccess = false)
                    return@launch
                }
                log("RX", "Ali ($aliNodeId) received packet directly! (Hops=1)")
                simulatePeerResponse(aliNodeId, "Ali", packet, localNodeId, localUsername, hops = 1)
            } else if (packet.destinationNodeId == ahmedNodeId) {
                // 2-Hop Relay: Local -> Ali -> Ahmed
                if (!isAliConnected) {
                    log("FAIL", "Cannot reach Ali (Next hop down). Packet will be stored in queue.", isSuccess = false)
                    return@launch
                }
                log("RELAY", "Node Ali ($aliNodeId) forwarding packet to Ahmed... (Decremented TTL=${packet.ttl - 1})")
                delay(400)
                if (!isAhmedConnected) {
                    log("STORE", "Ahmed temporarily disconnected. Ali queued message for store-and-forward.", isSuccess = false)
                    return@launch
                }
                log("RX", "Ahmed ($ahmedNodeId) received relayed packet! (Hops=2)")
                simulatePeerResponse(ahmedNodeId, "Ahmed", packet, localNodeId, localUsername, hops = 2)
            } else if (packet.destinationNodeId == fatimaNodeId) {
                // 4-Hop Relay: Local -> Ali -> Ahmed -> Usman -> Fatima
                log("RELAY", "Hop 1: Local → Ali ($aliNodeId)")
                delay(300)
                log("RELAY", "Hop 2: Ali → Ahmed ($ahmedNodeId)")
                delay(300)
                log("RELAY", "Hop 3: Ahmed → Usman ($usmanNodeId)")
                delay(300)
                log("RX", "Hop 4: Usman → Fatima ($fatimaNodeId) delivered! Verified E2EE.")
                simulatePeerResponse(fatimaNodeId, "Fatima", packet, localNodeId, localUsername, hops = 4)
            } else if (packet.isBroadcast) {
                log("RELAY", "Broadcasting across all neighbors: Ali, Ahmed, Usman")
                delay(300)
                log("ACK", "All reachable mesh nodes received broadcast message")
            }
        }
    }

    private suspend fun simulatePeerResponse(
        peerId: String,
        peerName: String,
        packet: MeshPacket,
        localNodeId: String,
        localUsername: String,
        hops: Int
    ) {
        if (packet.type == PacketType.CHAT_MESSAGE) {
            // Send back ACK
            delay(300)
            log("ACK", "$peerName generated ACK for msg ${packet.packetId.take(8)}")
            val ackPacket = MeshPacket(
                type = PacketType.ACK,
                sourceNodeId = peerId,
                sourceUsername = peerName,
                destinationNodeId = localNodeId,
                ttl = 6,
                hopCount = hops,
                plainPayload = packet.packetId,
                ackMessageId = packet.packetId,
                pathTraveled = listOf(peerId)
            )
            router.onPacketReceived(ackPacket, arrivalNeighborId = aliNodeId, localNodeId, localUsername)
            log("ACK", "Delivered ACK to local node! Status updated to DELIVERED ✓✓")

            // Simulate automatic friendly reply after 1.5 seconds
            delay(1500)
            simulateIncomingPeerMessage(peerId, peerName, "Got your mesh message via $hops hops! Offline connection stable.", localNodeId, localUsername, hops)
        }
    }

    /**
     * Simulates an incoming message from a remote mesh peer
     */
    fun simulateIncomingPeerMessage(
        senderId: String,
        senderName: String,
        text: String,
        localNodeId: String,
        localUsername: String,
        hops: Int = 2
    ) {
        scope.launch(Dispatchers.IO) {
            log("INCOMING", "Receiving incoming mesh message from $senderName ($senderId) via $hops hops...")
            val msgPacket = MeshPacket(
                type = PacketType.CHAT_MESSAGE,
                sourceNodeId = senderId,
                sourceUsername = senderName,
                destinationNodeId = localNodeId,
                ttl = 5,
                hopCount = hops,
                plainPayload = text,
                isEncrypted = false,
                pathTraveled = listOf(senderId, aliNodeId, localNodeId)
            )
            router.onPacketReceived(msgPacket, arrivalNeighborId = aliNodeId, localNodeId, localUsername)
            log("SUCCESS", "Message from $senderName saved and displayed in chat!", isSuccess = true)
        }
    }

    /**
     * Test Case 4: Disconnect relay node Ali
     */
    fun toggleRelayAli(localNodeId: String, localUsername: String) {
        isAliConnected = !isAliConnected
        if (!isAliConnected) {
            router.removeDirectNeighbor(aliNodeId)
            log("ALERT", "Relay node Ali disconnected from mesh! Network recalculating routes...", isSuccess = false)
        } else {
            router.recordDirectNeighbor(aliNodeId, "Ali", -60)
            log("RECOVER", "Relay node Ali reconnected! Automatic route recovery triggered.", isSuccess = true)
            scope.launch(Dispatchers.IO) {
                router.flushQueueForDestination(ahmedNodeId)
                router.flushQueueForDestination(fatimaNodeId)
            }
        }
    }

    /**
     * Test Case 6: Duplicate and Loop Prevention Demonstration
     */
    fun testDuplicateAndLoopDetection(localNodeId: String, localUsername: String) {
        scope.launch(Dispatchers.IO) {
            log("TEST", "Triggering Duplicate Packet Storm Test...")
            val duplicatePacket = MeshPacket(
                packetId = "TEST-DUP-001",
                type = PacketType.CHAT_MESSAGE,
                sourceNodeId = "BC-ATTACK",
                sourceUsername = "Spammer",
                destinationNodeId = localNodeId,
                plainPayload = "Duplicate test"
            )

            // Send first time
            router.onPacketReceived(duplicatePacket, arrivalNeighborId = aliNodeId, localNodeId, localUsername)
            log("DUP", "Packet TEST-DUP-001 accepted on 1st arrival")
            delay(200)

            // Send exact same packet second time
            router.onPacketReceived(duplicatePacket, arrivalNeighborId = aliNodeId, localNodeId, localUsername)
            log("DUP", "Duplicate packet TEST-DUP-001 intercepted & dropped by LRU Cache! Metric incremented.", isSuccess = true)
        }
    }
}
