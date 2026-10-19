package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.components.MeshStatusBadge
import com.example.ui.theme.SignalGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val contacts by viewModel.unblockedContacts.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessages.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeConversationMessages.collectAsStateWithLifecycle()
    val selectedContact by viewModel.selectedContact.collectAsStateWithLifecycle()
    val topologyNodes by viewModel.meshTopology.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val bluetoothStatus by viewModel.bluetoothStatus.collectAsStateWithLifecycle()
    val meshStats by viewModel.meshStats.collectAsStateWithLifecycle()
    val queuedPackets by viewModel.queuedPackets.collectAsStateWithLifecycle()
    val simLogs by viewModel.simLogs.collectAsStateWithLifecycle()
    val isSimActive by viewModel.isSimActive.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }

    // Modern Android permissions launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.startMeshServices()
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        viewModel.startMeshServices()
    }

    // If a conversation is active, show the full-screen chat screen
    val currentChatContact = selectedContact
    if (currentChatContact != null) {
        ChatConversationScreen(
            contact = currentChatContact,
            messages = activeMessages,
            onBack = { viewModel.closeChat() },
            onSendMessage = { destId, text, type ->
                viewModel.sendMessage(destId, text, type)
            },
            onShareLocation = { destId, lat, lng, label ->
                viewModel.shareLocation(destId, lat, lng, label)
            },
            onDeleteMessage = { msgId -> viewModel.deleteMessage(msgId) },
            onClearChat = { convId -> viewModel.clearConversation(convId) },
            modifier = modifier
        )
        return
    }

    // Otherwise show WhatsApp-style main navigation scaffold
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiTethering,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "BridgeChat",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(SignalGreen)
                                )
                            }
                            Text(
                                text = "Offline Bluetooth Mesh • E2EE",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    MeshStatusBadge(
                        activePeersCount = topologyNodes.size,
                        isRelayOn = userProfile?.isRelayEnabled ?: true,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Chats") },
                    label = { Text("Chats", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_tab_chats")
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.WifiTethering, contentDescription = "Nearby") },
                    label = { Text("Nearby Radar", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_tab_radar")
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Mesh Map") },
                    label = { Text("Mesh Map", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_tab_map")
                )

                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings & Testbed") },
                    label = { Text("Settings", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_tab_settings")
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> ChatsTab(
                    contacts = contacts,
                    messages = allMessages,
                    onOpenChat = { contact -> viewModel.openChat(contact) },
                    onNavigateToRadar = { selectedTab = 1 },
                    onNavigateToTestbed = { selectedTab = 3 }
                )
                1 -> NearbyRadarTab(
                    status = bluetoothStatus,
                    discoveredPeers = discoveredPeers,
                    onConnectPeer = { peer -> viewModel.connectToDiscoveredPeer(peer) },
                    onStartChat = { contact -> viewModel.openChat(contact) },
                    onRefreshScan = { viewModel.refreshBluetoothScan() },
                    onRequestPermissions = {
                        val permissionsToRequest = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
                        }
                        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        permissionsLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                )
                2 -> MeshMapTab(
                    userProfile = userProfile,
                    topologyNodes = topologyNodes,
                    queuedPackets = queuedPackets
                )
                3 -> SettingsAndTestbedTab(
                    userProfile = userProfile,
                    meshStats = meshStats,
                    simLogs = simLogs,
                    isSimActive = isSimActive,
                    onUpdateProfile = { name, status -> viewModel.updateProfile(name, status) },
                    onToggleRelay = { enabled -> viewModel.setRelayEnabled(enabled) },
                    onRun3NodeRelay = { viewModel.runTestScenario3Node() },
                    onRun5NodeMultiHop = { viewModel.runTestScenario5Node() },
                    onToggleRelayAli = { viewModel.toggleTestRelayAli() },
                    onRunDuplicateTest = { viewModel.runDuplicateTest() },
                    onClearLogs = { viewModel.clearSimulationLogs() }
                )
            }
        }
    }
}
