package com.example.securesmsforwarder

import android.content.Context
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.core.domain.RoleManager
import com.example.securesmsforwarder.core.domain.SettingsManager
import com.example.securesmsforwarder.crypto.encryption.MessageEncryption
import com.example.securesmsforwarder.crypto.identity.KeyManager
import com.example.securesmsforwarder.crypto.storage.LocalStorageCipher
import com.example.securesmsforwarder.crypto.storage.StorageCipher
import com.example.securesmsforwarder.pairing.TrustStore
import com.example.securesmsforwarder.storage.AppDatabase

/**
 * Process-scoped dependency container holding strongly-referenced singletons.
 * All members are lazily initialized so test environments do not eagerly touch
 * Android Keystore or Room on application startup.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    val keyManager: KeyManager by lazy { KeyManager(appContext) }
    val messageEncryption: MessageEncryption by lazy { MessageEncryption() }
    val storageCipher: StorageCipher by lazy { LocalStorageCipher(appContext) }
    val trustStore: TrustStore by lazy { TrustStore(appContext) }
    val roleManager: RoleManager by lazy { RoleManager(appContext) }
    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }
    val database: AppDatabase by lazy { AppDatabase.getDatabase(appContext) }
    val messageQueueManager: MessageQueueManager by lazy {
        MessageQueueManager(database.messageDao(), storageCipher)
    }
}
