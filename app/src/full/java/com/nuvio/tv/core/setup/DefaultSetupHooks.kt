package com.nuvio.tv.core.setup

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application.onCreate entry point for first-launch seeding and the
 * forced-update gate. Mirrors the PluginRuntimeHooks full/playstore
 * dual-file pattern so NuvioApplication keeps a single conflict-free hook
 * call; the playstore flavor gets a no-op stub with the same signature.
 */
@Singleton
class DefaultSetupHooks @Inject constructor(
    private val defaultSetup: DefaultSetup,
    private val updateGate: dagger.Lazy<UpdateGate>
) {
    fun onApplicationCreate(application: Application) {
        val appScope = CoroutineScope(HooksSupervisor.job + Dispatchers.IO)
        appScope.launch {
            try {
                updateGate.get().evaluate()
            } catch (e: Exception) {
                Log.w("DefaultSetupHooks", "Update gate evaluation failed: ${e.message}")
            }
        }
        appScope.launch {
            defaultSetup.run()
        }
    }
}

/** Holder so the launch scope's Job outlives the hook call. */
private object HooksSupervisor {
    val job = SupervisorJob()
}
