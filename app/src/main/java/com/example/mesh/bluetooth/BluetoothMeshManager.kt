package com.example.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.mesh.protocol.MeshPacket
import com.example.mesh.routing.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class BluetoothStatus {
    READY,
    SCANNING,
    PERMISSIONS_MISSING,
    BLUETOOTH_DISABLED,
    HARDWARE_UNAVAILABLE
}

data class DiscoveredBlePeer(
    val deviceAddress: String,
    val deviceName: String,
    val nodeId: String,
    val rssi: Int,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

class BluetoothMeshManager(
    private val context: Context,
    private val router: MeshRouter,
    private val scope: CoroutineScope
) {
    private val TAG = "BluetoothMeshManager"

    companion object {
        // BridgeChat BLE Service UUID
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000BC01-0000-1000-8000-00805F9B34FB")
        // BridgeChat Classic RFCOMM Server UUID
        val MESH_RFCOMM_UUID: UUID = UUID.fromString("0000BC02-0000-1000-8000-00805F9B34FB")
        const val SOCKET_NAME = "BridgeChatMeshRfcomm"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var leAdvertiser: BluetoothLeAdvertiser? = null
    private var leScanner: BluetoothLeScanner? = null

    private var serverSocket: BluetoothServerSocket? = null
    private val activeSockets = ConcurrentHashMap<String, BluetoothSocket>()

    private val _status = MutableStateFlow(BluetoothStatus.READY)
    val status: StateFlow<BluetoothStatus> = _status.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredBlePeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredBlePeer>> = _discoveredPeers.asStateFlow()

    private var scanJob: Job? = null
    private var serverJob: Job? = null

    private var localNodeId: String = ""
    private var localUsername: String = ""

    init {
        // Wire router's send listener to physical Bluetooth sockets
        router.packetSendListener = { packet, targetNeighborId ->
            sendPacketOverBluetooth(packet, targetNeighborId)
        }
    }

    fun setLocalIdentity(nodeId: String, username: String) {
        this.localNodeId = nodeId
        this.localUsername = username
    }

    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val adv = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val conn = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return scan && adv && conn
        } else {
            val bt = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val loc = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            return bt && loc
        }
    }

    @SuppressLint("MissingPermission")
    fun startMeshServices() {
        if (bluetoothAdapter == null) {
            _status.value = BluetoothStatus.HARDWARE_UNAVAILABLE
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _status.value = BluetoothStatus.BLUETOOTH_DISABLED
            return
        }

        if (!hasPermissions()) {
            _status.value = BluetoothStatus.PERMISSIONS_MISSING
            return
        }

        _status.value = BluetoothStatus.READY
        startAdvertising()
        startScanning()
        startRfcommServer()
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (!hasPermissions() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        try {
            leAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (leAdvertiser == null) return

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            // Put local node ID in service data
            val serviceData = localNodeId.toByteArray(Charsets.UTF_8)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                .addServiceData(ParcelUuid(MESH_SERVICE_UUID), serviceData)
                .build()

            leAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Started BLE Advertising for node $localNodeId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE advertising", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertising failed with code $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasPermissions() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        try {
            leScanner = bluetoothAdapter.bluetoothLeScanner
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            leScanner?.startScan(listOf(filter), settings, scanCallback)
            _status.value = BluetoothStatus.SCANNING
            Log.d(TAG, "Started BLE Scanning for BridgeChat mesh nodes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE Scan failed with code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
        val discoveredNodeId = if (serviceData != null) String(serviceData, Charsets.UTF_8) else "BC-PEER"

        if (discoveredNodeId == localNodeId) return // Ignore own beacon

        val peer = DiscoveredBlePeer(
            deviceAddress = result.device.address,
            deviceName = result.device.name ?: "Bridge Node ($discoveredNodeId)",
            nodeId = discoveredNodeId,
            rssi = result.rssi
        )

        val currentList = _discoveredPeers.value.toMutableList()
        val index = currentList.indexOfFirst { it.deviceAddress == peer.deviceAddress }
        if (index >= 0) {
            currentList[index] = peer
        } else {
            currentList.add(peer)
        }
        _discoveredPeers.value = currentList

        // Notify router of direct neighbor
        router.recordDirectNeighbor(peer.nodeId, peer.deviceName, peer.rssi)
    }

    @SuppressLint("MissingPermission")
    private fun startRfcommServer() {
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            if (!hasPermissions() || bluetoothAdapter == null) return@launch
            try {
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(SOCKET_NAME, MESH_RFCOMM_UUID)
                Log.d(TAG, "Listening for RFCOMM mesh connections...")

                while (isActive) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        null
                    }

                    if (socket != null) {
                        handleConnectedSocket(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RFCOMM server error", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String, targetNodeId: String) {
        if (!hasPermissions() || bluetoothAdapter == null) return

        scope.launch(Dispatchers.IO) {
            try {
                val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
                val socket = device.createInsecureRfcommSocketToServiceRecord(MESH_RFCOMM_UUID)
                socket.connect()
                handleConnectedSocket(socket, targetNodeId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to RFCOMM peer $deviceAddress", e)
            }
        }
    }

    private fun handleConnectedSocket(socket: BluetoothSocket, explicitNodeId: String? = null) {
        scope.launch(Dispatchers.IO) {
            var peerNodeId = explicitNodeId ?: "BC-REMOTE"
            activeSockets[peerNodeId] = socket
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (isActive && socket.isConnected) {
                    val line = reader.readLine() ?: break
                    val packet = MeshPacket.fromJson(line)
                    if (packet != null) {
                        peerNodeId = packet.sourceNodeId
                        activeSockets[peerNodeId] = socket
                        router.onPacketReceived(packet, arrivalNeighborId = peerNodeId, localNodeId, localUsername)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Socket read closed for $peerNodeId")
            } finally {
                activeSockets.remove(peerNodeId)
                router.removeDirectNeighbor(peerNodeId)
                try { socket.close() } catch (ignored: Exception) {}
            }
        }
    }

    fun sendPacketOverBluetooth(packet: MeshPacket, targetNeighborId: String?) {
        scope.launch(Dispatchers.IO) {
            val jsonLine = packet.toJson() + "\n"

            if (targetNeighborId != null && activeSockets.containsKey(targetNeighborId)) {
                // Unicast to target socket
                try {
                    val socket = activeSockets[targetNeighborId]
                    if (socket != null && socket.isConnected) {
                        val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
                        writer.write(jsonLine)
                        writer.flush()
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed sending to $targetNeighborId", e)
                }
            }

            // Otherwise flood/broadcast to all active connected neighbors
            for ((peerId, socket) in activeSockets) {
                try {
                    if (socket.isConnected) {
                        val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
                        writer.write(jsonLine)
                        writer.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting to $peerId", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopMeshServices() {
        try {
            if (hasPermissions()) {
                leAdvertiser?.stopAdvertising(advertiseCallback)
                leScanner?.stopScan(scanCallback)
            }
            serverSocket?.close()
            activeSockets.values.forEach { it.close() }
            activeSockets.clear()
        } catch (ignored: Exception) {}
        _status.value = BluetoothStatus.READY
    }
}
