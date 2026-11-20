package com.example.pdfmanager

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private val prefs by lazy { getSharedPreferences(PdfScanService.PREFS, MODE_PRIVATE) }
    private val folderPicker = 4102
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::result.isInitialized) refreshResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 32)
        }
        val title = TextView(this).apply { text = "PDF Manager"; textSize = 28f; gravity = Gravity.CENTER }
        val subtitle = TextView(this).apply {
            text = "Explora documentos PDF de una carpeta, incluidas sus subcarpetas. Cada inicio realiza una exploración nueva."
            textSize = 15f; gravity = Gravity.CENTER; setPadding(0, 12, 0, 24)
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 12, 0, 20) }
        result = TextView(this).apply { textSize = 14f; setPadding(0, 12, 0, 24) }
        val choose = Button(this).apply { text = "Seleccionar carpeta de PDF"; setOnClickListener { chooseFolder() } }
        val start = Button(this).apply { text = "Iniciar exploración"; setOnClickListener { startScan() } }
        val stop = Button(this).apply { text = "Detener exploración"; setOnClickListener { stopScan() } }
        content.addView(title, matchWrap())
        content.addView(subtitle, matchWrap())
        content.addView(status, matchWrap())
        content.addView(choose, matchWrap())
        content.addView(start, matchWrap())
        content.addView(stop, matchWrap())
        content.addView(result, matchWrap())
        setContentView(ScrollView(this).apply { addView(content) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4103)
        }
        refreshStatus()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, folderPicker)
    }

    @Deprecated("Deprecated by platform, retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == folderPicker && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                prefs.edit().putString("folder_uri", uri.toString()).apply()
                refreshStatus()
            } catch (e: SecurityException) {
                status.text = "No se pudo conservar el acceso a esa carpeta: ${e.message}"
            }
        }
    }

    private fun startScan() {
        val uriText = prefs.getString("folder_uri", null)
        if (uriText == null) { status.text = "Primero selecciona una carpeta."; return }
        val intent = Intent(this, PdfScanService::class.java)
            .setAction(PdfScanService.ACTION_START)
            .putExtra(PdfScanService.EXTRA_FOLDER_URI, uriText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            status.text = "Exploración iniciada. Puedes salir de esta pantalla; el progreso seguirá en la notificación."
        } catch (e: Exception) {
            status.text = "No se pudo iniciar la exploración: ${e.message ?: "revisa los permisos"}"
        }
    }

    private fun stopScan() {
        startService(Intent(this, PdfScanService::class.java).setAction(PdfScanService.ACTION_STOP))
        status.text = "Solicitud de detención enviada."
    }

    private fun refreshStatus() {
        val uriText = prefs.getString("folder_uri", null)
        val folder = uriText?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }
        status.text = if (folder != null) "Carpeta seleccionada: ${folder.name ?: "Documentos"}" else "Selecciona la carpeta donde guardas tus PDF."
        refreshResult()
    }

    private fun refreshResult() {
        val count = prefs.getInt(PdfScanService.KEY_LAST_COUNT, -1)
        val total = prefs.getInt(PdfScanService.KEY_LAST_TOTAL, -1)
        val bytes = prefs.getLong(PdfScanService.KEY_LAST_BYTES, 0L)
        val time = prefs.getLong(PdfScanService.KEY_LAST_TIME, 0L)
        val lastStatus = prefs.getString(PdfScanService.KEY_LAST_STATUS, null)
        result.text = if (count >= 0 && time > 0) {
            "Última exploración: ${lastStatus ?: "Sin estado"}\nPDF revisados: $count de ${total.coerceAtLeast(count)}\nTamaño total: ${formatBytes(bytes)}\nFecha: ${DateFormat.getDateTimeInstance().format(Date(time))}"
        } else "Todavía no hay exploraciones registradas."
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = -1
        do { size /= 1024.0; unit++ } while (size >= 1024 && unit < units.lastIndex)
        return String.format(java.util.Locale.getDefault(), "%.2f %s", size, units[unit])
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        if (::status.isInitialized) refreshStatus()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onPause()
    }
}
