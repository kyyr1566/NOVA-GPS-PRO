package com.nova.gpspro

import android.app.Application
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.NavigationEngine
import com.nova.gpspro.settings.SettingsRepository
import com.nova.gpspro.license.LicenseManager

class NovaApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var destinations: DestinationRepository; private set
    lateinit var gps: LocationEngine; private set
    lateinit var navigation: NavigationEngine; private set
    lateinit var licenseManager: LicenseManager; private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        licenseManager = LicenseManager(this)
        destinations = DestinationRepository(this)
        gps = LocationEngine(this)
        navigation = NavigationEngine(this, gps, destinations)
    }

    fun clearAllData() {
        navigation.clearAll()
        destinations.clearAll()
    }
}
