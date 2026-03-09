package org.cygnusx1.openbu.data

import android.content.Context
import org.json.JSONArray

object FilamentRepository {
    private var filaments: List<FilamentProfile>? = null

    fun getFilaments(context: Context): List<FilamentProfile> {
        filaments?.let { return it }
        val json = context.assets.open("filament_catalog.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val list = mutableListOf<FilamentProfile>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                FilamentProfile(
                    filamentId = obj.getString("filament_id"),
                    settingId = obj.getString("setting_id"),
                    name = obj.getString("name"),
                    type = obj.getString("type"),
                    nozzleTempMin = obj.getInt("nozzle_temp_min"),
                    nozzleTempMax = obj.getInt("nozzle_temp_max"),
                )
            )
        }
        filaments = list
        return list
    }

    fun getTypes(context: Context): List<String> =
        getFilaments(context).map { it.type }.distinct().sorted()
}
