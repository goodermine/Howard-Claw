package au.howardagent

import android.app.Application

class HowardApplication : Application() {

    companion object {
        lateinit var instance: HowardApplication
            private set
    }

    lateinit var securePrefs: au.howardagent.data.SecurePrefs
        private set
    lateinit var database: au.howardagent.data.HowardDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        securePrefs = au.howardagent.data.SecurePrefs(this)
        database = au.howardagent.data.HowardDatabase.getInstance(this)
    }
}
