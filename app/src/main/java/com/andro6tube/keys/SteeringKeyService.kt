package com.andro6tube.keys

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andro6tube.ui.KeyLog

/**
 * Спецвозможность «Кнопки руля»: получает клавиши на уровне системы ввода —
 * даже если ГУ не передаёт их в окна приложений. Медиаклавиши перехватываются
 * только когда играет наш плеер, остальные клавиши проходят дальше без изменений.
 */
class SteeringKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        KeyLog.add("[A11y] сервис кнопок руля включён")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            KeyLog.add("[A11y] ${MediaKeys.name(event.keyCode)} scan=${event.scanCode} rep=${event.repeatCount} dev=${event.device?.name ?: "?"}")
        }
        // В тесте кнопок только смотрим — пусть клавиша дойдёт и до окна
        if (KeyLog.testScreenOpen) return false
        val action = MediaKeys.actionFor(event.keyCode) ?: return false
        if (!MediaKeys.active) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) MediaKeys.perform(action)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, SteeringKeyService::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        const val ADB_ENABLE =
            "adb shell settings put secure enabled_accessibility_services com.andro6tube/com.andro6tube.keys.SteeringKeyService\n" +
                "adb shell settings put secure accessibility_enabled 1"
    }
}
