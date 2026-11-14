package cz.flos.pos.orders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.flos.pos.MainActivity
import cz.flos.pos.R
import cz.flos.pos.ui.tr

/**
 * The loud ring for a new order: the alarm sound (or the ringtone, or beeps) on the alarm stream, over
 * and over until someone stops it. The alarm stream still sounds when the phone is on silent and, with
 * the usual "Do not disturb" settings, also then (alarms are allowed by default). A notification with
 * "Vypnout zvonění" is shown too, and the screen is woken up.
 */
object OrderAlarm {
    const val CHANNEL_RING = "flos_orders_ring"
    const val CHANNEL_WATCH = "flos_orders_watch"
    private const val NOTIFY_ID = 4201
    const val ACTION_STOP = "cz.flos.pos.STOP_ORDER_ALARM"

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var beeps: Runnable? = null
    private var savedVolume: Int? = null
    private var screen: PowerManager.WakeLock? = null
    private var testEnd: Runnable? = null
    val ringing: Boolean get() = player != null || beeps != null

    /** Called when the ring is stopped from the notification, so the app hides its own "stop" button. */
    @Volatile var onStoppedOutside: (() -> Unit)? = null

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // The app plays the sound itself (on the alarm stream), so the notification is silent but pops up.
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RING, tr("Nové objednávky"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WATCH, tr("Hlídání objednávek"), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); setShowBadge(false)
        })
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 1,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Starts ringing (on the main thread); a second call while it rings does nothing. */
    fun start(ctx: Context, sound: String, volume: Int, notify: Boolean = true) = main.post {
        testEnd?.let { main.removeCallbacks(it); testEnd = null }
        if (ringing) return@post
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // The volume chosen in Flos, whatever the phone's alarm volume is; put back when the ring stops.
        if (savedVolume == null) runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, ((max * volume.coerceIn(10, 100) + 99) / 100).coerceIn(1, max), 0)
        }
        if (sound == "BEEP" || !play(app, sound)) startBeeps()
        wakeScreen(app)
        if (notify) showNotification(app)
    }

    /** Rings for a few seconds, to try the sound in the settings. */
    fun test(ctx: Context, sound: String, volume: Int) {
        start(ctx, sound, volume, notify = false)
        main.post {
            testEnd?.let { main.removeCallbacks(it) }
            testEnd = Runnable { testEnd = null; stop(ctx) }.also { main.postDelayed(it, 4_000) }
        }
    }

    fun stop(ctx: Context) = main.post {
        val app = ctx.applicationContext
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        beeps?.let { main.removeCallbacks(it) }
        beeps = null
        tone?.release()
        tone = null
        savedVolume?.let { v -> runCatching { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedVolume = null
        screen?.let { if (it.isHeld) it.release() }
        screen = null
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFY_ID) }
    }

    private fun play(ctx: Context, sound: String): Boolean = runCatching {
        val type = if (sound == "RINGTONE") RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM
        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, type)
            ?: RingtoneManager.getDefaultUri(type)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(ctx, uri)
            isLooping = true
            prepare()
            start()
        }
        true
    }.getOrElse { player?.release(); player = null; false }

    private fun startBeeps() {
        val t = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() ?: return
        tone = t
        val r = object : Runnable {
            override fun run() {
                runCatching { t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700) }
                main.postDelayed(this, 1_000)
            }
        }
        beeps = r
        main.post(r)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(ctx: Context) = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        screen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "flos:order-alarm")
            .apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }
    }

    private fun showNotification(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channels(ctx)
        val stop = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, StopAlarmReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle(tr("Nová objednávka"))
            .setContentText(tr("Otevřete Flos a objednávku přijměte nebo odmítněte."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, tr("Vypnout zvonění"), stop)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, n) }
    }
}

/** "Vypnout zvonění" on the notification. */
class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OrderAlarm.ACTION_STOP) return
        OrderAlarm.stop(context)
        OrderAlarm.onStoppedOutside?.invoke()
    }
}
