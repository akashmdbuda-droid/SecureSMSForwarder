package com.example.securesmsforwarder.pairing

import android.content.Context
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrustStoreTest {

    private lateinit var context: Context
    private lateinit var trustStore: TrustStore

    companion object {
        @JvmStatic
        @BeforeClass
        fun registerTink() {
            HybridConfig.register()
            AeadConfig.register()
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("secure_sms_trust_store", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        trustStore = TrustStore(context)
    }

    @Test
    fun testFirstPairing_defaultsNameToPairedDevice() {
        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v1\"}")

        assertEquals("DEVICE_A", trustStore.getTrustedDeviceId())
        assertTrue(trustStore.isDeviceTrusted("DEVICE_A"))
        assertEquals("Paired Device", trustStore.getTrustedDeviceName())
    }

    @Test
    fun testResavingSameDevice_withoutCustomNamePreservesExistingCustomName() {
        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v1\"}", "Work Phone")
        assertEquals("Work Phone", trustStore.getTrustedDeviceName())

        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v2\"}")
        assertEquals("Work Phone", trustStore.getTrustedDeviceName())

        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v3\"}", "Paired Device")
        assertEquals("Work Phone", trustStore.getTrustedDeviceName())

        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v4\"}", "   ")
        assertEquals("Work Phone", trustStore.getTrustedDeviceName())
    }

    @Test
    fun testResavingSameDevice_withNewExplicitCustomNameUpdatesName() {
        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v1\"}", "Old Name")
        assertEquals("Old Name", trustStore.getTrustedDeviceName())

        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v2\"}", "Personal Pixel")
        assertEquals("Personal Pixel", trustStore.getTrustedDeviceName())
    }

    @Test
    fun testPairingDifferentDevice_resetsOrUsesSuppliedName() {
        trustStore.addTrustedPeer("DEVICE_A", "{\"key\":\"v1\"}", "Work Phone")

        trustStore.addTrustedPeer("DEVICE_B", "{\"key\":\"v2\"}")
        assertEquals("DEVICE_B", trustStore.getTrustedDeviceId())
        assertFalse(trustStore.isDeviceTrusted("DEVICE_A"))
        assertTrue(trustStore.isDeviceTrusted("DEVICE_B"))
        assertEquals("Paired Device", trustStore.getTrustedDeviceName())

        trustStore.addTrustedPeer("DEVICE_C", "{\"key\":\"v3\"}", "Backup Tablet")
        assertEquals("DEVICE_C", trustStore.getTrustedDeviceId())
        assertEquals("Backup Tablet", trustStore.getTrustedDeviceName())
    }

    @Test
    fun testBackwardCompatibility_readsPreExistingV1023PrefsAndHpkePublicKeyset() {
        val privateKeyset = KeysetHandle.generateNew(
            KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305")
        )
        val publicKeyset = privateKeyset.publicKeysetHandle

        val stream = ByteArrayOutputStream()
        CleartextKeysetHandle.write(publicKeyset, JsonKeysetWriter.withOutputStream(stream))
        val serializedPublicKeyset = stream.toString("UTF-8")

        context.getSharedPreferences("secure_sms_trust_store", Context.MODE_PRIVATE)
            .edit()
            .putString("peer_device_id", "A1B2C3D4E5F6")
            .putString("peer_public_keyset", serializedPublicKeyset)
            .putString("peer_name", "Legacy Sender")
            .putLong("peer_last_synced", 1700000123456L)
            .commit()

        val freshTrustStore = TrustStore(context)
        assertTrue(freshTrustStore.isDeviceTrusted("A1B2C3D4E5F6"))
        assertEquals("A1B2C3D4E5F6", freshTrustStore.getTrustedDeviceId())
        assertEquals("Legacy Sender", freshTrustStore.getTrustedDeviceName())
        assertEquals(1700000123456L, freshTrustStore.getLastSyncedTimestamp())

        val parsedKeyset = freshTrustStore.getTrustedPeerPublicKeyset()
        assertNotNull(parsedKeyset)

        val contextInfo = "SecureSmsProtocol_V1".toByteArray(Charsets.UTF_8)
        val plaintext = "Backward compatible HPKE check".toByteArray(Charsets.UTF_8)
        val ciphertext = parsedKeyset!!.getPrimitive(HybridEncrypt::class.java)
            .encrypt(plaintext, contextInfo)
        val decrypted = privateKeyset.getPrimitive(HybridDecrypt::class.java)
            .decrypt(ciphertext, contextInfo)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testRevokePeer_clearsAllStoredTrustState() {
        val privateKeyset = KeysetHandle.generateNew(
            KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305")
        )
        val serialized = trustStore.serializePublicKeyset(privateKeyset.publicKeysetHandle)
        trustStore.addTrustedPeer("DEVICE_X", serialized, "Paired Sender")
        trustStore.setLastSyncedTimestamp(1700000999999L)

        trustStore.revokePeer()

        assertNull(trustStore.getTrustedDeviceId())
        assertFalse(trustStore.isDeviceTrusted("DEVICE_X"))
        assertNull(trustStore.getTrustedPeerPublicKeyset())
        assertEquals("Paired Device", trustStore.getTrustedDeviceName())
        assertEquals(0L, trustStore.getLastSyncedTimestamp())
    }
}
