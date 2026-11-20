package com.example.pdfmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.atomic.AtomicBoolean

class PdfScanService : Service() {
    companion object {
        const val ACTION_START = "com.example.pdfmanager.START_SCAN"
        const val ACTION_STOP = "com.example.pdfmanager.STOP_SCAN"
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val PREFS = "pdfmanager"
        const val KEY_LAST_COUNT = "last_scan_count"
        const val KEY_LAST_TIME = "last_scan_time"
        const val KEY_LAST_STATUS = "last_scan_status"
        const val KEY_LAST_BYTES = "last_scan_bytes"
        const val KEY_LAST_TOTAL = "last_scan_total"
    }

    private val stopRequested = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private val channelId = "pdf_scan_progress"
    private val notificationId = 7301

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested.set(true)
                worker?.interrupt()
                if (worker?.isAlive != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } else {
                    update("Deteniendo exploración…", 0, 0, true)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }

        if (worker?.isAlive == true) return START_NOT_STICKY
        stopRequested.set(false)
        startAsForeground(makeNotification("Preparando exploración…", 0, 0, true))
        val uri = intent.getStringExtra(EXTRA_FOLDER_URI)?.let(Uri::parse)
        if (uri == null) {
            saveResult(0, 0, "Error: no se indicó una carpeta")
            update("No se indicó una carpeta", 0, 0, false)
            stopSelf()
            return START_NOT_STICKY
        }

        worker = Thread {
            var count = 0
            var total = 0
            var bytes = 0L
            var finalStatus = "Exploración terminada"
            try {
                val root = DocumentFile.fromTreeUri(this, uri)
                if (root == null || !root.isDirectory) {
                    throw IllegalStateException("La carpeta ya no está disponible")
                }

                // Primera pasada: contar archivos y calcular el tamaño sin guardar
                // todos los DocumentFile en memoria.
                update("Contando archivos PDF…", 0, 0, true)
                walkPdfs(root) { file ->
                    total++
                    bytes += file.length().coerceAtLeast(0L)
                    if (total % 25 == 0) update("Encontrados $total PDF…", 0, 0, true)
                }

                if (total == 0) {
                    finalStatus = "No se encontraron PDF"
                    update(finalStatus, 0, 0, false)
                } else {
                    // Segunda pasada: revisar los archivos y actualizar el progreso.
                    var lastUpdate = SystemClock.elapsedRealtime()
                    walkPdfs(root) { file ->
                        count++
                        val now = SystemClock.elapsedRealtime()
                        if (count == 1 || count == total || count % 5 == 0 || now - lastUpdate >= 750) {
                            val name = file.name ?: "PDF $count"
                            update("Revisando $name", count, total, true)
                            lastUpdate = now
                        }
                    }
                    finalStatus = if (stopRequested.get()) "Detenido por el usuario" else "Exploración terminada"
                    update("$finalStatus: $count de $total PDF", count, total, false)
                }
            } catch (_: InterruptedException) {
                finalStatus = "Detenido por el usuario"
                update("Exploración detenida ($count de $total PDF)", count, total, false)
            } catch (e: SecurityException) {
                finalStatus = "Error de permisos: vuelve a seleccionar la carpeta"
                update(finalStatus, count, total, false)
            } catch (e: Exception) {
                finalStatus = "Error: ${e.message ?: "fallo de lectura"}"
                update(finalStatus, count, total, false)
            } finally {
                saveResult(count, bytes, finalStatus, total)
                worker = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.apply { name = "PdfScanWorker"; start() }
        return START_NOT_STICKY
    }

    private fun ensureRunning() {
        if (stopRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    /** Iterative traversal avoids deep recursion and does not retain the whole file list. */
    private fun walkPdfs(root: DocumentFile, onPdf: (DocumentFile) -> Unit) {
        val pending = ArrayDeque<DocumentFile>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            ensureRunning()
            val folder = pending.removeLast()
            for (child in folder.listFiles()) {
                ensureRunning()
                when {
                    child.isDirectory -> pending.addLast(child)
                    child.isFile && child.name?.endsWith(".pdf", ignoreCase = true) == true -> onPdf(child)
                }
            }
        }
    }

    private fun saveResult(count: Int, bytes: Long, status: String, total: Int = count) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_LAST_COUNT, count)
            .putInt(KEY_LAST_TOTAL, total)
            .putLong(KEY_LAST_BYTES, bytes)
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_STATUS, status)
            .apply()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Progreso de PDF", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Estado de la exploración de documentos PDF"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun makeNotification(text: String, progress: Int, total: Int, ongoing: Boolean): Notification {
        val stopIntent = Intent(this, PdfScanService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 7302, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 7303, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        builder.setContentTitle("PDF Manager")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
        if (total > 0) builder.setProgress(total, progress.coerceAtMost(total), false)
        else if (ongoing) builder.setProgress(0, 0, true)
        if (ongoing) builder.addAction(android.R.drawable.ic_media_pause, "Detener", stopPending)
        return builder.build()
    }

    private fun update(text: String, progress: Int, total: Int, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(notificationId, makeNotification(text, progress, total, ongoing))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopRequested.set(true)
        worker?.interrupt()
        stopSelf(startId)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
