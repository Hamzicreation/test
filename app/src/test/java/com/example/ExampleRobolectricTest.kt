package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoEngine
import com.example.mesh.protocol.MeshPacket
import com.example.mesh.protocol.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("BridgeChat", appName)
    }

    @Test
    fun `test crypto engine generates valid device id and encrypts decrypts successfully`() {
        val deviceId = CryptoEngine.generateDeviceId()
        assertTrue(deviceId.startsWith("BC-"))
        assertEquals(7, deviceId.length)

        val masterSecret = CryptoEngine.generateMasterKey()
        assertNotNull(masterSecret)

        val sharedKey = CryptoEngine.deriveSharedKey("BC-AA01", "BC-BB02", masterSecret)
        assertEquals(32, sharedKey.size)

        val originalMessage = "Secret offline coordinates: 33.6844, 73.0479"
        val encrypted = CryptoEngine.encrypt(originalMessage, sharedKey)
        assertNotNull(encrypted.cipherTextBase64)
        assertNotNull(encrypted.ivBase64)

        val decrypted = CryptoEngine.decrypt(encrypted.cipherTextBase64, encrypted.ivBase64, sharedKey)
        assertEquals(originalMessage, decrypted)
    }

    @Test
    fun `test mesh packet serialization and multi-hop forward copy`() {
        val packet = MeshPacket(
            type = PacketType.CHAT_MESSAGE,
            sourceNodeId = "BC-NODE-A",
            sourceUsername = "Alice",
            destinationNodeId = "BC-NODE-C",
            ttl = 5,
            hopCount = 0,
            plainPayload = "Hello from mesh origin",
            pathTraveled = listOf("BC-NODE-A")
        )

        // Serialize to JSON and parse back
        val json = packet.toJson()
        val parsed = MeshPacket.fromJson(json)
        assertNotNull(parsed)
        assertEquals(packet.packetId, parsed?.packetId)
        assertEquals("BC-NODE-A", parsed?.sourceNodeId)
        assertEquals("BC-NODE-C", parsed?.destinationNodeId)
        assertEquals(5, parsed?.ttl)
        assertEquals(0, parsed?.hopCount)

        // Forward through relay node B
        val forwarded = packet.createForwardCopy("BC-NODE-B")
        assertEquals(4, forwarded.ttl)
        assertEquals(1, forwarded.hopCount)
        assertTrue(forwarded.pathTraveled.contains("BC-NODE-B"))
        assertEquals(listOf("BC-NODE-A", "BC-NODE-B"), forwarded.pathTraveled)
    }
}
