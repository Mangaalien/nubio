package com.nuvio.tv.core.setup

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.SettingsUiStyle
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-launch seeding of default addons, plugin repositories, scraper
 * enable states and appearance settings.
 *
 * Purely additive: guarded by a one-time DataStore flag, runs at most once
 * per install, and touches only existing public APIs of the stores it seeds.
 *
 * Seeded scraper enable state: everything the All-in-One repo manifest marks
 * enabled stays enabled EXCEPT the ids in DISABLED_SCRAPER_IDS. Because
 * scraper ids in storage are prefixed with the generated repository UUID
 * ("<repoId>:<scraperId>"), per-id state can only be applied after the repo
 * is fetched and parsed — see [applyScraperDisableList] and [run].
 */
@Singleton
class DefaultSetup @Inject constructor(
    private val addonPreferences: AddonPreferences,
    private val pluginDataStore: PluginDataStore,
    private val themeDataStore: ThemeDataStore,
    private val profileDataStoreFactory: ProfileDataStoreFactory,
    private val pluginManagerProvider: dagger.Lazy<com.nuvio.tv.core.plugin.PluginManager>
) {
    companion object {
        private const val TAG = "DefaultSetup"
        private const val FEATURE = "default_setup"
        private val seededKey = booleanPreferencesKey("default_setup_done")

        /** Ordered, enabled addons seeded on first launch. */
        private val DEFAULT_ADDON_URLS = listOf(
            "https://izlemeli.github.io/nuvio/en",
            "https://catalog.nuvio.tv",
            "https://v3-cinemeta.strem.io",
            "https://opensubtitles-v3.strem.io",
            "https://anime-kitsu.strem.fun",
            "https://nuvio-anime.vercel.app",
            "https://torrentio.strem.fun",
            "https://comet.feels.legal"
        )

        /** Plugin repositories seeded on first launch (Nuvio-native + CloudStream). */
        private val PLUGIN_REPO_URLS = listOf(
            "https://raw.githubusercontent.com/NuvioPlugin/All-in-One-Nuvio/main/manifest.json",
            "https://raw.githubusercontent.com/carokannxyz/pencurimovie-cloudstream/main/repo.json"
        )

        /** Scraper ids inside the All-in-One repo that must start disabled. */
        private val DISABLED_SCRAPER_IDS = setOf("castle", "hdghartv", "hindmoviez")

        /** Repository URL whose scrapers the disable-list applies to. */
        private const val DISABLE_LIST_REPO_URL =
            "https://raw.githubusercontent.com/NuvioPlugin/All-in-One-Nuvio/main/manifest.json"
    }

    private val store = profileDataStoreFactory.get(1, FEATURE)

    /**
     * Runs the one-time first-launch setup. Safe to call from any scope;
     * never throws.
     */
    suspend fun run() {
        try {
            if (store.data.first()[seededKey] == true) return
        } catch (e: Exception) {
            Log.w(TAG, "Could not read seed flag, skipping: ${e.message}")
            return
        }

        seedAddons()
        seedTheme()

        // Mark seeded BEFORE the slow network work so a crash mid-seed doesn't
        // retry addons/theme forever; repo seeding has its own URL dedup.
        try {
            store.edit { it[seededKey] = true }
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist seed flag: ${e.message}")
        }

        seedPluginRepositories()
    }

    private suspend fun seedAddons() {
        try {
            val existing = addonPreferences.installedAddonUrls.first()
            // Only seed into a clean install; never overwrite user state.
            val defaults = listOf("https://v3-cinemeta.strem.io", "https://opensubtitles-v3.strem.io")
            if (existing.map { it.trimEnd('/') }.sorted() != defaults.map { it.trimEnd('/') }.sorted()) return
            for (url in DEFAULT_ADDON_URLS) {
                addonPreferences.addAddon(url)
            }
            Log.i(TAG, "Seeded ${DEFAULT_ADDON_URLS.size} default addons")
        } catch (e: Exception) {
            Log.w(TAG, "Addon seed failed: ${e.message}")
        }
    }

    private suspend fun seedTheme() {
        try {
            // Only apply when the user has no explicit theme choice yet.
            if (themeDataStore.getThemeForProfile(1) != null) return
            themeDataStore.setTheme(AppTheme.VIOLET)
            themeDataStore.setAmoledMode(true)
            themeDataStore.setSettingsUiStyle(SettingsUiStyle.ZEN)
        } catch (e: Exception) {
            Log.w(TAG, "Theme seed failed: ${e.message}")
        }
    }

    private suspend fun seedPluginRepositories() {
        for (url in PLUGIN_REPO_URLS) {
            try {
                val result = pluginManagerProvider.get().addRepository(url)
                if (result.isSuccess) {
                    Log.i(TAG, "Seeded plugin repo: $url")
                    if (url == DISABLE_LIST_REPO_URL) {
                        applyScraperDisableList(result.getOrNull())
                    }
                } else {
                    Log.w(TAG, "Plugin repo seed failed for $url: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Plugin repo seed error for $url: ${e.message}")
            }
        }
    }

    /**
     * Applies the one-time scraper disable-list after the All-in-One repo has
     * been parsed. The repo id is a generated UUID, so the per-scraper ids in
     * storage are "<repoId>:<manifestScraperId>" — we must match on the
     * manifest id suffix. Guarded: only flips scrapers whose current enabled
     * state still equals the manifest default (true), so a re-run after a
     * user toggles a scraper is a no-op.
     */
    private suspend fun applyScraperDisableList(repo: com.nuvio.tv.domain.model.PluginRepository?) {
        if (repo == null) return
        try {
            val scrapers = pluginDataStore.scrapers.first()
            val target = scrapers.filter {
                it.repositoryId == repo.id &&
                    it.id.substringAfterLast(":") in DISABLED_SCRAPER_IDS &&
                    it.enabled
            }
            if (target.isEmpty()) return
            for (scraper in target) {
                pluginDataStore.setScraperEnabled(scraper.id, false)
            }
            Log.i(TAG, "Disabled ${target.size} seeded scrapers: ${target.map { it.id }}")
        } catch (e: Exception) {
            Log.w(TAG, "Scraper disable-list failed: ${e.message}")
        }
    }
}
