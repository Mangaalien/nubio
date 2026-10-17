package com.nuvio.tv.core.setup

import android.app.Application
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playstore-flavor no-op stub. Seeding and the forced-update gate are
 * full-flavor features; MainActivity depends on this signature in both
 * flavors, so the class shape must match.
 */
@Singleton
class DefaultSetupHooks @Inject constructor() {
    fun onApplicationCreate(application: Application) = Unit
}
