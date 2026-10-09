package com.example.mounter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class Client(val id: String, val name: String)

internal fun JSONObject.optionalText(key: String): String = if (isNull(key)) "" else optString(key, "")
internal fun cleanName(value: String): String = value.trim().replace(Regex("\\s+"), " ")
internal fun nameKey(value: String): String = cleanName(value).lowercase(Locale.ROOT)

internal class LocalDirectoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_directory", Context.MODE_PRIVATE)

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