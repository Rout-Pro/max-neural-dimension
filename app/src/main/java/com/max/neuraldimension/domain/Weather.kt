package com.max.neuraldimension.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Real weather via Open-Meteo (free, no API key, HTTPS). Network required.
 * No keys, no account, no tracking identifiers sent - only the city name.
 */
object WeatherClient {

    private val http = BackendClient.defaultClient()

    private val WMO_CODES = mapOf(
        0 to "clear sky", 1 to "mainly clear", 2 to "partly cloudy", 3 to "overcast",
        45 to "fog", 48 to "icy fog", 51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle",
        61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "freezing rain", 67 to "freezing rain",
        71 to "light snow", 73 to "snow", 75 to "heavy snow", 77 to "snow grains",
        80 to "light showers", 81 to "showers", 82 to "heavy showers",
        85 to "snow showers", 86 to "snow showers", 95 to "thunderstorm",
        96 to "thunderstorm with hail", 99 to "thunderstorm with hail"
    )

    /** Returns a one-line forecast or throws with a user-readable message. */
    suspend fun current(city: String): String = withContext(Dispatchers.IO) {
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" +
            URLEncoder.encode(city.trim(), "UTF-8")
        val geo = http.newCall(Request.Builder().url(geoUrl).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw AiException("Geocoding failed (HTTP ${r.code})")
            JSONObject(r.body?.string().orEmpty())
        }
        val results = geo.optJSONArray("results")
            ?: throw AiException("Place not found: $city")
        if (results.length() == 0) throw AiException("Place not found: $city")
        val place = results.getJSONObject(0)
        val lat = place.getDouble("latitude")
        val lon = place.getDouble("longitude")
        val placeName = place.optString("name", city)
        val country = place.optString("country", "")

        val wxUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
        val wx = http.newCall(Request.Builder().url(wxUrl).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw AiException("Weather fetch failed (HTTP ${r.code})")
            JSONObject(r.body?.string().orEmpty())
        }
        val cur = wx.getJSONObject("current")
        val temp = cur.getDouble("temperature_2m")
        val hum = cur.getInt("relative_humidity_2m")
        val code = cur.getInt("weather_code")
        val wind = cur.getDouble("wind_speed_10m")
        val desc = WMO_CODES[code] ?: "conditions unknown"
        "$placeName${if (country.isNotBlank()) ", $country" else ""}: ${temp}C, $desc, humidity $hum%, wind ${wind} km/h"
    }
}
