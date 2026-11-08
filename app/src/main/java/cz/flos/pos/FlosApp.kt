package cz.flos.pos

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import cz.flos.pos.data.FlosDatabase
import cz.flos.pos.eet.EetRetryWorker

class FlosApp : Application() {
    lateinit var container: FlosContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, FlosDatabase::class.java, FlosDatabase.FILE_NAME)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*FlosDatabase.MIGRATIONS)
            .build()
        container = FlosContainer(AndroidPlatform(this), database, cz.flos.pos.security.EncryptedStore(PrefsStore(this), AndroidSecretKey::getOrCreate))
        EetRetryWorker.schedulePeriodic(this)
    }
}
