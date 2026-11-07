package com.fucksh

import android.app.Application
import com.fucksh.di.AppContainer

class FuckshApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: FuckshApplication
            private set
    }
}
