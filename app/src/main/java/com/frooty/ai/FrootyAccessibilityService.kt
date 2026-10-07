package com.frooty.ai

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import java.util.ArrayDeque

class FrootyAccessibilityService : AccessibilityService() {
    private lateinit var screenContextStore: ScreenContextStore

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenContextStore = ScreenContextStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::screenContextStore.isInitialized) return
        if (!screenContextStore.isCaptureEnabled()) return
        if (event.packageName?.toString() == packageName) return

        val root = rootInActiveWindow
        if (root == null) {
            screenContextStore.clear()
            return
        }
        val visibleText = collectVisibleText(root)
        screenContextStore.save(visibleText)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (::screenContextStore.isInitialized && screenContextStore.isCaptureEnabled()) {
            screenContextStore.clear()
        }
        return super.onUnbind(intent)
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val text = LinkedHashSet<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)

        while (pending.isNotEmpty() && text.size < MAX_NODES) {
            val node = pending.removeFirst()
            if (node.isPassword) continue

            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(text::add)
            node.contentDescription?.toString()?.trim()
                ?.takeIf(String::isNotEmpty)?.let(text::add)

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return text.joinToString("\n").take(MAX_SCREEN_CHARS)
    }

    private companion object {
        const val MAX_NODES = 250
        const val MAX_SCREEN_CHARS = 6000
    }
}
