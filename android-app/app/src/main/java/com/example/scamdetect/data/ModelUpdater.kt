package com.example.scamdetect.data

import android.content.Context
import com.example.scamdetect.ondevice.LinearTextModel
import com.example.scamdetect.ondevice.OnDeviceScorer
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Workstream G.10: keeps the three on-device models up to date from the cloud's model registry.
 *
 * On app start (and from Settings) it asks the model-download service, through the same address as the gateway,
 * which versions exist:   https://<address>:8443/models/manifest
 * For every model with a version this phone doesn't have yet it downloads the file, checks its SHA-256 against the
 * manifest, checks the app can load it, and only then swaps it in (files/models/<signal>_en.json). If anything
 * fails the phone keeps the model it has; the models built into the app are the fallback.
 *
 * Only model files come down. Nothing about calls goes up.
 */
object ModelUpdater {
    /** Registry name -> the file OnDeviceScorer reads. */
    val NAMES = linkedMapOf(
        "urgency_en_phone" to "urgency_en.json",
        "money_en_phone" to "money_en.json",
        "impersonation_en_phone" to "impersonation_en.json",
    )

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }

    fun dir(context: Context) = File(context.filesDir, "models")
    private fun installedFile(context: Context) = File(dir(context), "installed.json")

    /** "https://1.2.3.4:8443/api/" -> "https://1.2.3.4:8443/models/" (the Ingress serves /models next to /api). */
    fun modelsBase(gatewayUrl: String): String {
        val u = GatewayClient.normalize(gatewayUrl)
        return (if (u.endsWith("/api/")) u.removeSuffix("api/") else u) + "models/"
    }

    data class Installed(val version: String, val sha256: String, val installedAt: Long)

    fun installed(context: Context): Map<String, Installed> = runCatching {
        val o = Gson().fromJson(installedFile(context).readText(), JsonObject::class.java)
        o.entrySet().associate { (k, v) ->
            val e = v.asJsonObject
            k to Installed(e["version"].asString, e["sha256"].asString, e["installed_at"].asLong)
        }
    }.getOrDefault(emptyMap())

    /** One line per model for the Settings screen. */
    fun describe(context: Context): String {
        val inst = installed(context)
        val day = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        return NAMES.keys.joinToString("\n") { name ->
            val signal = name.substringBefore("_")
            inst[name]?.let { "$signal: federated ${it.version} (downloaded ${day.format(Date(it.installedAt))})" }
                ?: "$signal: built-in model (no download yet)"
        }
    }

    /** Check the manifest and install anything newer. Returns a short message for the Settings screen. */
    suspend fun update(context: Context, gatewayUrl: String): String = withContext(Dispatchers.IO) {
        val base = try { modelsBase(gatewayUrl) } catch (e: Exception) { return@withContext "Bad server address" }
        val manifest = try {
            get(base + "manifest").let { Gson().fromJson(String(it), JsonObject::class.java)["models"].asJsonObject }
        } catch (e: Exception) {
            return@withContext "No model service at ${base}manifest (${e.javaClass.simpleName}). " +
                "Is demo/tools/federated.py up running?"
        }
        val inst = installed(context).toMutableMap()
        val changed = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for ((name, file) in NAMES) {
            val entry = manifest[name]?.asJsonObject ?: continue
            val version = entry["version"].asString
            val sha = entry["sha256"].asString
            if (inst[name]?.sha256 == sha) continue
            try {
                val bytes = get(base + "$name/$version")
                val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(got == sha) { "checksum mismatch" }
                LinearTextModel.load(bytes.inputStream().reader())          // refuse a file the app can't read
                dir(context).mkdirs()
                val tmp = File(dir(context), "$file.tmp")
                tmp.writeBytes(bytes)
                java.nio.file.Files.move(tmp.toPath(), File(dir(context), file).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                inst[name] = Installed(version, sha, System.currentTimeMillis())
                changed += "${name.substringBefore("_")} $version"
            } catch (e: Exception) {
                problems += "${name.substringBefore("_")}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (changed.isNotEmpty()) {
            val json = JsonObject().apply {
                inst.forEach { (k, v) ->
                    add(k, JsonObject().apply {
                        addProperty("version", v.version); addProperty("sha256", v.sha256); addProperty("installed_at", v.installedAt)
                    })
                }
            }
            installedFile(context).writeText(json.toString())
            OnDeviceScorer.reload()
        }
        when {
            changed.isNotEmpty() -> "Updated: ${changed.joinToString(", ")}" + if (problems.isEmpty()) "" else ". Kept the old model for ${problems.joinToString("; ")}"
            problems.isNotEmpty() -> "Kept the current models: ${problems.joinToString("; ")}"
            manifest.size() == 0 -> "The registry has no phone models yet (run a federated round with --publish)"
            else -> "Up to date"
        }
    }

    /**
     * Real-phone training: send this phone's model change (PhoneTrainer.uploadJson) to the update inbox, next to
     * /models on the same address: https://<address>:8443/fl/updates. gzip-compressed. Returns a short message.
     */
    suspend fun upload(gatewayUrl: String, json: String): String = withContext(Dispatchers.IO) {
        val url = modelsBase(gatewayUrl).removeSuffix("models/") + "fl/updates"
        val body = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(json.toByteArray()) }
        }.toByteArray()
        val req = Request.Builder().url(url).header("Content-Encoding", "gzip")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
        try {
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) "Uploaded ${"%.1f".format(body.size / 1e6)} MB of model changes (no call text). " +
                    "They join the next federated round."
                else "Upload refused: HTTP ${r.code} ${r.body?.string()?.take(120).orEmpty()}"
            }
        } catch (e: Exception) {
            "Upload failed: ${e.javaClass.simpleName} ($url)"
        }
    }

    /** The registry version of each downloaded model ("v3"), by signal; built-in models have none. */
    fun baseVersions(context: Context): Map<String, String> =
        installed(context).mapKeys { it.key.substringBefore("_") }.mapValues { it.value.version }

    private fun get(url: String): ByteArray {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            require(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body!!.bytes()
        }
    }
}
