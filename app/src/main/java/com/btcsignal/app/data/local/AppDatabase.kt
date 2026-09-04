package com.btcsignal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SignalEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "btc_signal.db"
            )
                // v1 -> v2 added the AI Assist columns (aiConsulted/aiConfidence/aiAgreed/
                // aiOverridden). No released installs predate this, so a destructive
                // migration (clears signal history only, never Settings/Strategy config)
                // is simpler and safer than a hand-written ALTER TABLE migration here.
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
