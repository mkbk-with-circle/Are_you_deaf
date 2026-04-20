package com.nierduolong.morningbell.unlock

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** 在允许从后台拉起前台服务的场景下启动 [WakeTrackService]（开机、闹钟、Activity 前台等） */
object WakeTrackStarter {
    private const val TAG = "WakeTrackStarter"

    fun ensureRunning(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, WakeTrackService::class.java)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Android 12+：非豁免场景下禁止从后台启动 FGS，等用户进入界面后会再次调用
            Log.w(TAG, "foreground service start not allowed: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForegroundService failed: ${e.message}")
        }
    }
}
