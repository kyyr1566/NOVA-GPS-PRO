package com.nova.gpspro

import android.app.Application
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.NavigationEngine
import com.nova.gpspro.settings.SettingsRepository
import com.nova.gpspro.ui.C
import com.nova.gpspro.license.LicenseManager
import com.nova.gpspro.ptt.PttHostIdentity
import com.nova.gpspro.ptt.PttRepository

class NovaApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var destinations: DestinationRepository; private set
    lateinit var gps: LocationEngine; private set
    lateinit var navigation: NavigationEngine; private set
    lateinit var licenseManager: LicenseManager; private set
    lateinit var ptt: PttRepository; private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        C.use(settings.darkTheme)          // palette must be selected before any view is created
        licenseManager = LicenseManager(this)
        destinations = DestinationRepository(this)
        gps = LocationEngine(this)
        navigation = NavigationEngine(this, gps, destinations)
        ptt = PttRepository(
            appCtx = this,
            uiText = { resId -> getString(resId) },
            hostPublicKeyProvider = { PttHostIdentity.publicKeyBytes(this) }
        )
    }

    fun clearAllData() {
        ptt.endSessionByUser()
        navigation.clearAll()
        destinations.clearAll()
    }
}
