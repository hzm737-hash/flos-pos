package cz.flos.pos.orders

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.flos.pos.R
import cz.flos.pos.ui.tr

/**
 * Keeps Flos running while the screen is off, so the till keeps fetching orders from the tables and the
 * website and rings for them (Pokročilé nastavení → Objednávky → "Zvonit i se zhasnutou obrazovkou").
 * Android shows its notification all the time; nothing else is done here, the till itself fetches.
 */
class OrderWatchService : Service() {
    private var cpu: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OrderAlarm.channels(this)
        val n = NotificationCompat.Builder(this, OrderAlarm.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle(tr("Flos hlídá nové objednávky"))
            .setContentText(tr("Zazvoní i se zhasnutou obrazovkou."))
            .setOngoing(true)
            .setContentIntent(OrderAlarm.openApp(this))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (runCatching { ServiceCompat.startForeground(this, 4200, n, type) }.isFailure) { stopSelf(); return START_NOT_STICKY }
        if (cpu == null) cpu = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flos:order-watch").apply { setReferenceCounted(false); acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cpu?.let { if (it.isHeld) it.release() }
        cpu = null
        super.onDestroy()
    }

    companion object {
        /** Starts or stops watching; start only while the app is on screen (Android allows it then). */
        fun update(ctx: Context, on: Boolean) {
            val i = Intent(ctx, OrderWatchService::class.java)
            if (on) runCatching { ContextCompat.startForegroundService(ctx, i) } else ctx.stopService(i)
        }
    }
}
