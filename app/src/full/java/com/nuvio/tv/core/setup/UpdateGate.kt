package com.nuvio.tv.core.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forced-update gate for the Nubio fork.
 *
 * Fetches https://raw.githubusercontent.com/mangaalien/nubio/main/min_version.json
 * once per app run (cached in DataStore with a timestamp). Policy:
 *  - fetch success and current versionCode < minVersionCode -> BLOCKED
 *  - fetch failure (offline / HTTP error / bad JSON) -> ALLOWED unless a
 *    still-fresh cached minimum says otherwise (never brick offline users
 *    beyond the last known minimum)
 *  - fetch success and version >= minimum -> ALLOWED
 *
 * Results are posted to [UpdateGateBus] which MainActivity observes.
 */
@Singleton
class UpdateGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClientProvider: dagger.Lazy<okhttp3.OkHttpClient>
) {
    companion object {
        private const val MIN_VERSION_URL =
            "https://raw.githubusercontent.com/mangaalien/nubio/main/min_version.json"
        private val minVersionCodeKey = longPreferencesKey("min_version_code")
        private val fetchedAtKey = longPreferencesKey("min_version_fetched_at_ms")

        /**
         * How long a cached minimum stays authoritative when subsequent
         * fetches fail (short offline spells don't defeat the gate; long
         * ones don't brick the app forever).
         */
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    private val dataStore = context.updateGateDataStore

    suspend fun evaluate() {
        val fetch = fetchMinVersion()
        if (fetch == null) {
            val cached = readCache()
            val cachedIsFresh =
                cached != null && System.currentTimeMillis() - cached.second <= CACHE_TTL_MS
            val blocked = cachedIsFresh && BuildConfig.VERSION_CODE < cached.first
            UpdateGateBus.post(UpdateGateState(checked = true, blocked = blocked))
            return
        }

        writeCache(fetch.first)
        val blocked = BuildConfig.VERSION_CODE < fetch.first
        UpdateGateBus.post(UpdateGateState(checked = true, blocked = blocked, message = fetch.second))
    }

    /** Returns (minVersionCode, message) or null on any failure. */
    private fun fetchMinVersion(): Pair<Long, String>? {
        return try {
            val client = okHttpClientProvider.get()
            val request = okhttp3.Request.Builder()
                .url(MIN_VERSION_URL)
                .header("User-Agent", "NubioTV/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parse(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(body: String): Pair<Long, String>? {
        return try {
            val json = org.json.JSONObject(body)
            val code = json.getLong("minVersionCode")
            val message = json.optString("message", "Nubio requires an update to continue.")
            if (code < 0) return null
            code to message
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun readCache(): Pair<Long, Long>? {
        return try {
            val prefs = dataStore.data.first()
            val code = prefs[minVersionCodeKey] ?: return null
            val at = prefs[fetchedAtKey] ?: return null
            code to at
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun writeCache(code: Long) {
        try {
            dataStore.edit { prefs ->
                prefs[minVersionCodeKey] = code
                prefs[fetchedAtKey] = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            // Cache write failure is non-fatal.
        }
    }
}

private val Context.updateGateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "update_gate_settings"
)
