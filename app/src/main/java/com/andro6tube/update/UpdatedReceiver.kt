package com.andro6tube.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.andro6tube.BuildConfig
import com.andro6tube.ui.MainActivity

/** После обновления приложения (root или через окно установки) — снова открываем его. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Toast.makeText(context, "Andro6Tube обновлён до ${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
