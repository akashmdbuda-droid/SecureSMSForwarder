package com.example.securesmsforwarder

import android.app.Application
import android.content.Context

class SecureSmsApplication : Application() {

    val appContainer: AppContainer by lazy { AppContainer(this) }

    companion object {
        @Volatile
        private var fallbackContainer: AppContainer? = null

        fun container(context: Context): AppContainer {
            val app = context.applicationContext ?: context
            return if (app is SecureSmsApplication) {
                app.appContainer
            } else {
                fallbackContainer ?: synchronized(this) {
                    fallbackContainer ?: AppContainer(app).also { fallbackContainer = it }
                }
            }
        }
    }
}
