package com.nierduolong.morningbell

import android.app.Application
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MorningBellApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.build(this)
        repository = AppRepository(this, database)
        CoroutineScope(Dispatchers.IO).launch {
            repository.seedIfEmpty()
        }
        // 起床解锁监听由 WakeTrackService（前台）注册；勿在 Application 仅动态注册（进程被杀即失效）
    }
}
