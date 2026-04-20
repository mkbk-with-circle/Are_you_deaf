package com.nierduolong.morningbell.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/** 读取最近一次网络/GPS 定位（需已授予 ACCESS_COARSE_LOCATION） */
object LocationHelper {
    fun lastLatLngOrNull(context: Context): Pair<Double, Double>? {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val candidates =
            listOfNotNull(
                try {
                    lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: SecurityException) {
                    null
                },
                try {
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } catch (_: SecurityException) {
                    null
                },
                try {
                    lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                } catch (_: SecurityException) {
                    null
                },
            )
        val best = candidates.maxByOrNull { it.time ?: 0L } ?: return null
        return best.latitude to best.longitude
    }
}
