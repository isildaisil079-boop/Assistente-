package com.assistente.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class DownloadService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var total = 0L
        @Volatile var message = ""
    }

    private var worker: Thread? = null
    private var lastNotify = 0L
    private var url = ""
    private var file = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("download", "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            1,
            notificacao("A preparar…", -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val u = intent?.getStringExtra("url")
        val f = intent?.getStringExtra("file")
        if (u == null || f == null) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        url = u
        file = f
        if (worker == null) {
            running = true
            message = ""
            total = 0L
            worker = thread { trabalhar() }
        }
        return START_REDELIVER_INTENT
    }

    private fun notificacao(txt: String, pct: Int): Notification {
        val b = Notification.Builder(this, "download")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Assistente")
            .setContentText(txt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun tamanhoTmp(): Long {
        val t = File(filesDir, "$file.tmp")
        return if (t.exists()) t.length() else 0L
    }

    private fun trabalhar() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistente:download")
        wl.acquire(3 * 60 * 60 * 1000L)
        var falhas = 0
        try {
            while (falhas < 40) {
                val antes = tamanhoTmp()
                try {
                    baixar()
                    message = "Concluído"
                    return
                } catch (e: Exception) {
                    falhas = if (tamanhoTmp() > antes) 1 else falhas + 1
                    message = "Ligação instável, a retomar… (${e.message})"
                    try { Thread.sleep(5000) } catch (_: InterruptedException) {}
                }
            }
            message = "Não consegui concluir. Toca em Continuar."
        } finally {
            if (wl.isHeld) wl.release()
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun baixar() {
        val fim = File(filesDir, file)
        if (fim.exists()) return
        val tmp = File(filesDir, "$file.tmp")
        val ja = if (tmp.exists()) tmp.length() else 0L

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (ja > 0) c.setRequestProperty("Range", "bytes=$ja-")
        val code = c.responseCode

        if (code == 416) {
            if (ja > 100_000_000L) {
                tmp.renameTo(fim)
                return
            }
            tmp.delete()
            throw Exception("A recomeçar")
        }
        if (code != 200 && code != 206) throw Exception("HTTP $code")

        val append = code == 206
        val inicio = if (append) ja else 0L
        val resto = c.contentLengthLong
        if (resto > 0) total = inicio + resto

        c.inputStream.use { inp ->
            FileOutputStream(tmp, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var feito = inicio
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    feito += n
                    val agora = System.currentTimeMillis()
                    if (agora - lastNotify > 1000) {
                        lastNotify = agora
                        message = ""
                        val pct = if (total > 0) (feito * 100 / total).toInt() else -1
                        val txt = if (pct >= 0) "$pct%" else "${feito / 1_000_000} MB"
                        getSystemService(NotificationManager::class.java)
                            .notify(1, notificacao("A descarregar… $txt", pct))
                    }
                }
            }
        }
        if (total > 0 && tmp.length() < total) throw Exception("Download incompleto")
        if (!tmp.renameTo(fim)) throw Exception("Não consegui guardar o ficheiro")
    }
}
