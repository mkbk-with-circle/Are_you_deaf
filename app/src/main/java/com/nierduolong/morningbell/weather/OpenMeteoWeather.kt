package com.nierduolong.morningbell.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Open-Meteo 公共接口，无需 API Key：https://open-meteo.com/
 * 需「大致位置」权限；若暂无 GPS 缓存则用语义上的北京坐标作后备。
 */
object OpenMeteoWeather {
    private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

    suspend fun fetchTodayLine(context: Context): String =
        withContext(Dispatchers.IO) {
            try {
                val hasCoarse =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED

                if (!hasCoarse) {
                    return@withContext context.getString(R.string.weather_need_location)
                }

                val fromDevice = LocationHelper.lastLatLngOrNull(context)
                val latLng =
                    fromDevice
                        ?: geocodeFirstLatLng("北京")
                        ?: return@withContext context.getString(R.string.weather_error_geocode)

                val (lat, lon) = latLng
                val usedFallback = fromDevice == null
                val placeName =
                    reverseGeocodeLocality(context, lat, lon)
                        ?: if (usedFallback) {
                            "北京（定位信号弱，仅供参考）"
                        } else {
                            "当前区域"
                        }

                val json = fetchForecastJson(lat, lon) ?: return@withContext context.getString(R.string.weather_error_network)

                val current = json.optJSONObject("current") ?: return@withContext context.getString(R.string.weather_error_parse)

                val temp = current.optDouble("temperature_2m", Double.NaN)
                val humidity = current.optInt("relative_humidity_2m", -1)
                val code = current.optInt("weather_code", -1)
                val wind = current.optDouble("wind_speed_10m", Double.NaN)

                val sky = WmoWeatherZh.describe(code)
                val tempStr =
                    if (!temp.isNaN()) "${temp.roundToInt()}°C" else ""
                val humStr =
                    if (humidity >= 0) "湿度 ${humidity}%" else ""
                val windStr =
                    when {
                        wind.isNaN() -> ""
                        wind < 12 -> "微风"
                        wind < 28 -> "和风"
                        else -> "阵风偏大"
                    }

                val parts =
                    listOfNotNull(
                        "$placeName：$sky",
                        tempStr.takeIf { it.isNotBlank() },
                        humStr.takeIf { it.isNotBlank() },
                        windStr.takeIf { it.isNotBlank() },
                    )
                "今日天气：" + parts.joinToString("，") + "。"
            } catch (_: Exception) {
                context.getString(R.string.weather_error_network)
            }
        }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeLocality(
        context: Context,
        lat: Double,
        lon: Double,
    ): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val gc = Geocoder(context, Locale.CHINA)
            val list = gc.getFromLocation(lat, lon, 1)
            list?.firstOrNull()?.locality
                ?: list?.firstOrNull()?.subAdminArea
        } catch (_: Exception) {
            null
        }
    }

    private fun geocodeFirstLatLng(cityName: String): Pair<Double, Double>? =
        try {
            val q = URLEncoder.encode(cityName, Charsets.UTF_8.name())
            val url = "$GEOCODE_URL?name=$q&count=1&language=zh"
            val conn =
                (java.net.URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                }
            conn.inputStream.bufferedReader().use { it.readText() }.let { body ->
                val root = JSONObject(body)
                val arr = root.optJSONArray("results") ?: return null
                if (arr.length() == 0) return null
                val o = arr.getJSONObject(0)
                o.getDouble("latitude") to o.getDouble("longitude")
            }
        } catch (_: Exception) {
            null
        }

    private fun fetchForecastJson(
        lat: Double,
        lon: Double,
    ): JSONObject? =
        try {
            val url =
                "$FORECAST_URL?" +
                    "latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&timezone=auto"
            val conn =
                (java.net.URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                }
            conn.inputStream.bufferedReader().use { it.readText() }.let { JSONObject(it) }
        } catch (_: Exception) {
            null
        }
}

private object WmoWeatherZh {
    fun describe(code: Int): String =
        when (code) {
            0 -> "晴"
            1 -> "大部晴朗"
            2 -> "多云"
            3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻毛毛雨"
            61, 63, 65 -> "雨"
            66, 67 -> "冻雨"
            71, 73, 75 -> "降雪"
            77 -> "雪粒"
            80, 81, 82 -> "阵雨"
            85, 86 -> "阵雪"
            95 -> "雷阵雨"
            96, 99 -> "雷阵雨伴冰雹"
            else -> "多云"
        }
}
