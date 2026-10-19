package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BridgeChatDatabase
import com.example.data.BridgeChatRepository
import com.example.data.entity.ContactEntity
import com.example.data.entity.MeshStatsEntity
import com.example.data.entity.MessageEntity
import com.example.data.entity.QueuedPacketEntity
import com.example.data.entity.UserEntity
import com.example.mesh.bluetooth.BluetoothMeshManager
import com.example.mesh.bluetooth.BluetoothStatus
import com.example.mesh.bluetooth.DiscoveredBlePeer
import com.example.mesh.routing.LiveNetworkNode
import com.example.mesh.routing.MeshRouter
import com.example.mesh.service.MeshForegroundService
import com.example.mesh.simulator.MeshVirtualTestbed
import com.example.mesh.simulator.SimulationLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val database = BridgeChatDatabase.getInstance(application)
    val repository = BridgeChatRepository(database)
    val router = MeshRouter(repository, viewModelScope)
    val bluetoothManager = BluetoothMeshManager(application, router, viewModelScope)
    val testbed = MeshVirtualTestbed(router, viewModelScope)

    // User Profile
    val userProfile: StateFlow<UserEntity?> = repository.userProfile
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Contacts
    val unblockedContacts: StateFlow<List<ContactEntity>> = repository.unblockedContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allContacts: StateFlow<List<ContactEntity>> = repository.allContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All messages (for recent chats list preview)
    val allMessages: StateFlow<List<MessageEntity>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search query for chats
    val searchQuery = MutableStateFlow("")

    // Selected Contact for Chat Conversation screen
    val selectedContact = MutableStateFlow<ContactEntity?>(null)

    // Filtered / Active conversation messages
    val activeConversationMessages: StateFlow<List<MessageEntity>> = selectedContact
        .flatMapLatest { contact ->
            if (contact != null) {
                repository.getMessagesForConversation(contact.nodeId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live Topology & Discovered BLE Peers
    val meshTopology: StateFlow<List<LiveNetworkNode>> = router.networkTopology
    val discoveredPeers: StateFlow<List<DiscoveredBlePeer>> = bluetoothManager.discoveredPeers
    val bluetoothStatus: StateFlow<BluetoothStatus> = bluetoothManager.status

    // Mesh Stats & Queued store-and-forward packets
    val meshStats: StateFlow<MeshStatsEntity?> = repository.meshStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val queuedPackets: StateFlow<List<QueuedPacketEntity>> = repository.queuedPackets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Testbed simulation state
    val simLogs: StateFlow<List<SimulationLog>> = testbed.logs
    val isSimActive: StateFlow<Boolean> = testbed.isSimActive

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = repository.getOrCreateUserProfile()
            bluetoothManager.setLocalIdentity(profile.nodeId, profile.username)

            // Setup incoming message notification listener
            router.onIncomingMessageAlert = { msg ->
                MeshForegroundService.showIncomingMessageNotification(
                    application,
                    senderName = msg.senderName,
                    text = msg.content
                )
            }

            // Wire packet send to simulator when active
            val originalSend = router.packetSendListener
            router.packetSendListener = { packet, targetHop ->
                // Physical Bluetooth
                originalSend?.invoke(packet, targetHop)
                // Virtual Simulation
                testbed.simulateTransmitPacket(packet, profile.nodeId, profile.username)
            }
        }
    }

    fun startMeshServices() {
        bluetoothManager.startMeshServices()
        MeshForegroundService.startService(getApplication())
    }

    fun openChat(contact: ContactEntity) {
        selectedContact.value = contact
    }

    fun closeChat() {
        selectedContact.value = null
    }

    fun sendMessage(destinationId: String, content: String, type: String = "TEXT") {
        if (content.isBlank()) return
        val currentProfile = userProfile.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            router.sendOutgoingMessage(
                destinationNodeId = destinationId,
                content = content.trim(),
                messageType = type,
                localNodeId = currentProfile.nodeId,
                localUsername = currentProfile.username
            )
        }
    }

    fun shareLocation(destinationId: String, latitude: Double, longitude: Double, placeLabel: String) {
        val locationText = "$placeLabel (${"%.4f".format(latitude)}, ${"%.4f".format(longitude)})"
        sendMessage(destinationId, locationText, type = "LOCATION")
    }

    fun setRelayEnabled(enabled: Boolean) {
        val currentProfile = userProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.setRelayEnabled(currentProfile.nodeId, enabled)
        }
    }

    fun updateProfile(name: String, statusMsg: String) {
        val currentProfile = userProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProfile(currentProfile.nodeId, name, statusMsg)
            bluetoothManager.setLocalIdentity(currentProfile.nodeId, name)
        }
    }

    fun blockContact(nodeId: String, isBlocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setContactBlocked(nodeId, isBlocked)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteMessage(messageId)
        }
    }

    fun clearConversation(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearConversation(conversationId)
        }
    }

    fun connectToDiscoveredPeer(peer: DiscoveredBlePeer) {
        bluetoothManager.connectToDevice(peer.deviceAddress, peer.nodeId)
    }

    fun refreshBluetoothScan() {
        bluetoothManager.startScanning()
    }

    // Simulation Testbed actions
    fun runTestScenario3Node() {
        val profile = userProfile.value ?: return
        testbed.setup3NodeRelayScenario(profile.nodeId, profile.username)
    }

    fun runTestScenario5Node() {
        val profile = userProfile.value ?: return
        testbed.setup5NodeMultiHopScenario(profile.nodeId, profile.username)
    }

    fun toggleTestRelayAli() {
        val profile = userProfile.value ?: return
        testbed.toggleRelayAli(profile.nodeId, profile.username)
    }

    fun runDuplicateTest() {
        val profile = userProfile.value ?: return
        testbed.testDuplicateAndLoopDetection(profile.nodeId, profile.username)
    }

    fun clearSimulationLogs() {
        testbed.clearLogs()
    }
}
