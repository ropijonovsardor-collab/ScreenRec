package uz.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class RecorderService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "uz.screenrec.START"
        const val ACTION_STOP = "uz.screenrec.STOP"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
        private const val SHAKE_G = 3.0f      // silkitish kuchi (kattaroq = qattiqroq silkitish kerak)
        private const val SHAKE_COUNT = 3     // necha marta silkitish kerak

        @Volatile var running = false
        @Volatile var startTime = 0L
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var lastShake = 0L
    private var shakeCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (running) return

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ekran yozish", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Ekran yozilmoqda")
            .setContentText("To'xtatish uchun bosing")
            .setContentIntent(stopPi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra("data")
        val w = intent.getIntExtra("w", 1280)
        val h = intent.getIntExtra("h", 720)
        val dpi = intent.getIntExtra("dpi", 320)
        val fps = intent.getIntExtra("fps", 30)
        val bitrate = intent.getIntExtra("bitrate", 6_000_000)
        val mic = intent.getBooleanExtra("mic", false)

        try {
            if (data == null) throw IllegalStateException("Ruxsat ma'lumoti yo'q")

            // Fayl: Movies/ScreenRec (galereyada ko'rinadi)
            val name = "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            pfd = contentResolver.openFileDescriptor(uri!!, "w")

            // Apparat (hardware) H.264 kodlash: protsessorni yuklamaydi
            val r = MediaRecorder(this)
            if (mic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
            }
            r.setVideoSize(w, h)
            r.setVideoFrameRate(fps)
            r.setVideoEncodingBitRate(bitrate)
            r.setOutputFile(pfd!!.fileDescriptor)
            r.prepare()
            recorder = r

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopRecording() }
            }, null)
            projection = p

            display = p.createVirtualDisplay(
                "rec", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            r.start()

            running = true
            startTime = SystemClock.elapsedRealtime()

            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
            cleanup(deleteFile = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        cleanup(deleteFile = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        Toast.makeText(this, "Saqlandi: Movies/ScreenRec", Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun cleanup(deleteFile: Boolean) {
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        try { recorder?.stop() } catch (_: RuntimeException) { }
        try { recorder?.reset() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        display?.release()
        display = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { pfd?.close() } catch (_: Exception) { }
        pfd = null
        uri?.let {
            if (deleteFile) {
                contentResolver.delete(it, null, null)
            } else {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(it, v, null, null)
            }
        }
        uri = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
        if (g < SHAKE_G) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShake < 150) return          // bitta silkitishni ikki marta sanamaslik
        if (now - lastShake > 1200) shakeCount = 0 // uzoq tanaffus bo'lsa qaytadan
        lastShake = now
        shakeCount++
        // yozish boshlangach dastlabki 3 soniya e'tiborsiz (telefonni qo'lga olish paytida)
        if (shakeCount >= SHAKE_COUNT && now - startTime > 3000) {
            shakeCount = 0
            stopRecording()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        if (running) { running = false; cleanup(deleteFile = false) }
        super.onDestroy()
    }
}
