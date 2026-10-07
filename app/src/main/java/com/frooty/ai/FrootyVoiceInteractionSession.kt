package com.frooty.ai

import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionSession

class FrootyVoiceInteractionSession(private val appContext: Context) :
    VoiceInteractionSession(appContext) {

    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startVoiceActivity(
            Intent(appContext, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_START_VOICE_INPUT, true)
            }
        )
        hide()
    }
}
