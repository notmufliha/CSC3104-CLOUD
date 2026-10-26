package com.example.scamdetect.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Loads every .json file in assets/scripts/.
 * File name convention: <kind>_<lang>_<nn>_<title>.json, e.g. scam_en_01_police.json, normal_zh_02_delivery.json
 */
class ScriptRepository(private val context: Context) {
    private val gson = Gson()
    private val listType = object : TypeToken<List<ScriptLine>>() {}.type

    fun loadAll(): List<CallScript> {
        val files = context.assets.list("scripts")
            ?.filter { it.endsWith(".json") }
            ?.sorted()
            .orEmpty()
        return files.mapNotNull { name ->
            try {
                parse(name)
            } catch (e: Exception) {
                Log.w("ScriptRepository", "Skipping bad script $name: ${e.message}")
                null
            }
        }
    }

    private fun parse(name: String): CallScript {
        val json = context.assets.open("scripts/$name").bufferedReader().use { it.readText() }
        val lines: List<ScriptLine> = gson.fromJson(json, listType)
        val parts = name.removeSuffix(".json").split("_")
        val kind = parts.getOrNull(0)?.lowercase() ?: "unknown"
        val lang = parts.getOrNull(1)?.lowercase()?.takeIf { it == "en" || it == "zh" }
        val title = parts.drop(3).joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { name }
        return CallScript(name, kind, lang, title, lines.filter { !it.text.isNullOrBlank() })
    }
}
