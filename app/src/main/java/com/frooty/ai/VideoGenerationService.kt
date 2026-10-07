package com.frooty.ai

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.FirebaseAuth
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

class VideoGenerationService(context: Context) {
    private val functions = FirebaseFunctions.getInstance("us-central1")
    private val auth = FirebaseAuth.getInstance()
    private val preferences =
        context.getSharedPreferences("frooty_video_generation", Context.MODE_PRIVATE)

    data class PendingGeneration(val operationName: String, val prompt: String)

    fun savePending(operationName: String, prompt: String) {
        check(preferences.edit()
            .putString(KEY_OPERATION, operationName)
            .putString(KEY_PROMPT, prompt)
            .commit()) { "Could not save video operation" }
    }

    fun pending(): PendingGeneration? {
        val operation = preferences.getString(KEY_OPERATION, null) ?: return null
        val prompt = preferences.getString(KEY_PROMPT, null) ?: return null
        return PendingGeneration(operation, prompt)
    }

    fun clearPending() {
        check(preferences.edit().remove(KEY_OPERATION).remove(KEY_PROMPT).commit()) {
            "Could not clear video operation"
        }
    }

    suspend fun start(prompt: String): String {
        ensureAuthenticated()
        val result = functions
            .getHttpsCallable("startVideoGeneration")
            .call(mapOf("prompt" to prompt))
            .await()
        return result.data.asStringMap()["operationName"]
            ?: error("Video service did not return an operation ID")
    }

    suspend fun waitForVideo(operationName: String, onWaiting: () -> Unit): String {
        ensureAuthenticated()
        repeat(MAX_STATUS_CHECKS) {
            delay(STATUS_POLL_INTERVAL_MS)
            val result = functions
                .getHttpsCallable("getVideoGenerationStatus")
                .call(mapOf("operationName" to operationName))
                .await()
                .data
                .asStringMap()

            when (result["status"]) {
                "processing" -> onWaiting()
                "complete" -> return result["videoUrl"]
                    ?: error("Video service did not return a download URL")
                else -> error("Video generation failed. Check the prompt and try again.")
            }
        }
        error("Video is still processing. Reopen the video tool to check again.")
    }

    private suspend fun ensureAuthenticated() {
        if (auth.currentUser == null) auth.signInAnonymously().await()
    }

    private fun Any?.asStringMap(): Map<String, String> {
        val map = this as? Map<*, *> ?: error("Unexpected video service response")
        return map.mapNotNull { (key, value) ->
            if (key is String && value is String) key to value else null
        }.toMap()
    }

    private companion object {
        const val KEY_OPERATION = "operation_name"
        const val KEY_PROMPT = "prompt"
        const val STATUS_POLL_INTERVAL_MS = 10_000L
        const val MAX_STATUS_CHECKS = 60
    }
}
