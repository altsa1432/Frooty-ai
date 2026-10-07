package com.frooty.ai

import android.app.Application
import com.google.firebase.appcheck.FirebaseAppCheck

class FrootyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(FrootyAppCheckProvider.factory())
    }
}
