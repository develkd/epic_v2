package de.kd.epic2.app

import android.app.Application
import de.kd.epic2.infrastructure.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class EpicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@EpicApplication)
            modules(appModule)
        }
    }
}
