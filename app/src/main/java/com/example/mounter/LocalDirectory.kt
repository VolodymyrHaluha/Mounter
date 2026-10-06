package com.example.mounter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class Worker(val id: String, val name: String, val role: String = "")
data class Client(val id: String, val name: String)

internal fun JSONObject.optionalText(key: String): String = if (isNull(key)) "" else optString(key, "")
internal fun cleanName(value: String): String = value.trim().replace(Regex("\\s+"), " ")
internal fun nameKey(value: String): String = cleanName(value).lowercase(Locale.ROOT)

private fun readWorkers(data: String): List<Worker> {
    val values = JSONArray(data)
    return (0 until values.length()).map {
        val value = values.getJSONObject(it)
        Worker(value.getString("id"), value.getString("full_name"), value.optionalText("job_title"))
    }
}

private fun workerJson(workers: List<Worker>): String {
    val values = JSONArray()
    workers.forEach {
        values.put(JSONObject().put("id", it.id).put("full_name", it.name).put("job_title", it.role))
    }
    return values.toString()
}

internal class TeamStore(context: Context) {
    // Keep the existing key so previously selected employees remain available offline.
    private val preferences = context.getSharedPreferences("team", Context.MODE_PRIVATE)
    fun load(): List<Worker> = readWorkers(preferences.getString("employees", "[]") ?: "[]")
        .distinctBy { it.id }.distinctBy { nameKey(it.name) }

    fun save(workers: List<Worker>) {
        val unique = workers.distinctBy { it.id }.distinctBy { nameKey(it.name) }
        check(preferences.edit().putString("employees", workerJson(unique)).commit()) {
            "Не вдалося зберегти склад бригади на пристрої."
        }
    }
}

internal class LocalDirectoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_directory", Context.MODE_PRIVATE)

    fun loadWorkers(selected: List<Worker>): List<Worker> {
        val saved = readWorkers(preferences.getString("employees", "[]") ?: "[]")
        val workers = (saved + selected).filter { it.name.isNotBlank() }
            .distinctBy { it.id }.distinctBy { nameKey(it.name) }
        saveWorkers(workers)
        return workers
    }

    fun saveWorkers(workers: List<Worker>) {
        check(preferences.edit().putString("employees", workerJson(workers)).commit()) {
            "Не вдалося зберегти співробітника на пристрої."
        }
    }

    fun loadClients(projects: List<Project>): List<Client> {
        val values = JSONArray(preferences.getString("clients", "[]") ?: "[]")
        val saved = (0 until values.length()).map {
            val value = values.getJSONObject(it)
            Client(value.getString("id"), value.getString("name"))
        }
        // Seed the local list from objects already on this device, including legacy objects.
        val clients = (saved + projects.map {
            Client(it.customerId ?: UUID.randomUUID().toString(), it.displayName)
        }).filter { it.name.isNotBlank() }.distinctBy { it.id }.distinctBy { nameKey(it.name) }
        saveClients(clients)
        return clients
    }

    fun saveClients(clients: List<Client>) {
        val values = JSONArray()
        clients.forEach { values.put(JSONObject().put("id", it.id).put("name", it.name)) }
        check(preferences.edit().putString("clients", values.toString()).commit()) {
            "Не вдалося зберегти замовника на пристрої."
        }
    }
}