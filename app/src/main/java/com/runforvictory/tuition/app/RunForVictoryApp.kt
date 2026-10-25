package com.runforvictory.tuition.app

import android.app.Application
import com.runforvictory.tuition.core.di.AppContainer
import com.runforvictory.tuition.core.di.DefaultAppContainer
import com.runforvictory.tuition.notifications.NotificationChannelManager

class RunForVictoryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer()
        NotificationChannelManager.setupNotificationChannels(this)
    }
}
