
package com.example.gps_tracker

import android.app.Application
import com.amap.api.maps.MapsInitializer

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
    }
}
