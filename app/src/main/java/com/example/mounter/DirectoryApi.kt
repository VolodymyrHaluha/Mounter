package com.example.mounter

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val DEFAULT_API_URL = "http://192.168.1.76:5001"

data class Worker(val id: String, val name: String, val role: String = "", val imageUrl: String = "")
data class Client(val id: String, val name: String)
enum class DatabaseStatus { CHECKING, CONNECTED, ERROR }

internal fun JSONObject.optionalText(key: String): String = if (isNull(key)) "" else optString(key, "")

private fun parseWorker(value: JSONObject) = Worker(
    value.getString("id"), value.getString("full_name"), value.optionalText("job_title"), value.optionalText("image_url")
)

internal class TeamStore(context: Context) {
    private val preferences = context.getSharedPreferences("team", Context.MODE_PRIVATE)
    fun load(): List<Worker> = runCatching {
        val data = JSONArray(preferences.getString("employees", "[]"))
        (0 until data.length()).map { parseWorker(data.getJSONObject(it)) }.distinctBy { it.id }
    }.getOrDefault(emptyList())
    fun save(workers: List<Worker>) {
        val data = JSONArray()
        workers.distinctBy { it.id }.forEach {
            data.put(JSONObject().put("id", it.id).put("full_name", it.name).put("job_title", it.role).put("image_url", it.imageUrl))
        }
        preferences.edit { putString("employees", data.toString()) }
    }
}

internal class DirectoryApi(val baseUrl: String) {
    fun imageUrl(value: String): String = if (value.isBlank()) "" else runCatching {
        URL(URL(baseUrl.trimEnd('/') + "/"), value).toString()
    }.getOrDefault("")

    private suspend fun request(path: String, parameters: List<Pair<String, String>> = emptyList()): String = withContext(Dispatchers.IO) {
        val builder = Uri.parse(baseUrl.trimEnd('/') + "/api/mounter/" + path).buildUpon()
        parameters.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        val connection = URL(builder.build().toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optionalText("detail") }.getOrDefault("")
                error(detail.ifBlank { "Помилка API: HTTP $status. Перевірте адресу сервера та маршрути /api/mounter." })
            }
            response
        } catch (error: java.io.IOException) {
            throw IllegalStateException("Не вдалося підключитися до API $baseUrl. Перевірте адресу та мережу.", error)
        } finally {
            connection.disconnect()
        }
    }
    suspend fun health() {
        check(JSONObject(request("health")).optBoolean("connected")) { "PostgreSQL не підключено." }
    }
    private fun workers(data: String): List<Worker> {
        val values = JSONArray(data)
        return (0 until values.length()).map { parseWorker(values.getJSONObject(it)) }.distinctBy { it.id }
    }
    suspend fun employees(query: String): List<Worker> = workers(request("employees", listOf("q" to query)))
    suspend fun selectedEmployees(ids: List<String>): List<Worker> {
        val result=mutableListOf<Worker>()
        for(batch in ids.distinct().chunked(50)) {
            result+=workers(request("employees/selected", batch.map { "ids" to it }))
        }
        return result.distinctBy {it.id}
    }
    suspend fun clients(query: String): List<Client> {
        val values = JSONArray(request("clients", listOf("q" to query)))
        return (0 until values.length()).map { values.getJSONObject(it).let { value -> Client(value.getString("id"), value.getString("name")) } }
    }
}
