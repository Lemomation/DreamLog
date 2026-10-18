package com.lemomation.dreamlog

import android.app.Application
import com.lemomation.dreamlog.data.DreamLogDatabase
import com.lemomation.dreamlog.data.DreamRepository

class DreamLogApplication : Application() {
    lateinit var database: DreamLogDatabase
        private set

    lateinit var repository: DreamRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = DreamLogDatabase.get(this)
        repository = DreamRepository(database)
    }
}
