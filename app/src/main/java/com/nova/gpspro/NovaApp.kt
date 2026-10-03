package com.nova.gpspro

import android.app.Application
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.NavigationEngine
import com.nova.gpspro.settings.SettingsRepository
import com.nova.gpspro.ui.C
import com.nova.gpspro.trips.TripManager

class NovaApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var destinations: DestinationRepository; private set
    lateinit var gps: LocationEngine; private set
    lateinit var navigation: NavigationEngine; private set
    lateinit var trips: TripManager; private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        C.use(settings.darkTheme)          // palette must be selected before any view is created
        destinations = DestinationRepository(this)
        gps = LocationEngine(this)
        navigation = NavigationEngine(this, gps, destinations)
        trips = TripManager(this, gps, destinations, navigation, settings)
    }

    fun clearAllData() {
        navigation.clearAll()
        destinations.clearAll()
        trips.clearAll()
    }
}
