package com.example.pdfmanager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {
 private val prefs by lazy { getSharedPreferences(BotService.PREFS, MODE_PRIVATE) }
 private lateinit var token: EditText
 private lateinit var channel: EditText
 private lateinit var admin: EditText
 private lateinit var folder: TextView
 private lateinit var status: TextView
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply { orientation=1; setPadding(28,32,28,28) }
  fun label(s:String)=TextView(this).apply { text=s; textSize=16f; setPadding(0,10,0,6) }
  fun field(h:String,v:String,secret:Boolean=false)=EditText(this).apply { hint=h; setText(v); textSize=16f; if(secret) inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
  fun add(v:android.view.View){ root.addView(v,LinearLayout.LayoutParams(-1,-2)) }
  add(label("PDF Bot para Telegram").apply { textSize=26f })
  add(label("Configura el token, el canal, tu ID y la carpeta de PDF. El token se guarda en el espacio privado de la app."))
  token=field("Token de Telegram",prefs.getString("token","") ?: "",true); add(label("Token")); add(token)
  channel=field("Canal (@canal o -100…)",prefs.getString("channel","") ?: ""); add(label("Canal destino")); add(channel)
  admin=field("ID numérico del administrador",prefs.getString("admin","") ?: ""); add(label("Tu ID de Telegram")); add(admin)
  folder=label("Carpeta: "+folderName()); add(folder)
  add(Button(this).apply { text="Seleccionar carpeta PDF"; setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),4102) } })
  add(Button(this).apply { text="Guardar configuración"; setOnClickListener { if(save()) status.text="Configuración guardada." } })
  add(Button(this).apply { text="Iniciar bot"; setOnClickListener { if(save()) { val i=Intent(this@MainActivity,BotService::class.java).setAction(BotService.START); if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i); status.text="Bot iniciado. Comprueba Telegram con /start." } } })
  add(Button(this).apply { text="Detener bot"; setOnClickListener { startService(Intent(this@MainActivity,BotService::class.java).setAction(BotService.STOP)); status.text="Detención solicitada." } })
  add(label("Comandos: /start · /estado · /procesar · /detener"))
  status=label("El bot está detenido."); add(status)
  setContentView(ScrollView(this).apply { addView(root) })
 }
 private fun folderName():String { val u=prefs.getString("folder_uri",null)?.let(Uri::parse) ?: return "No seleccionada"; return DocumentFile.fromTreeUri(this,u)?.name ?: "Seleccionada" }
 private fun save():Boolean {
  val t=token.text.toString().trim(); val c=channel.text.toString().trim(); val a=admin.text.toString().trim()
  if(t.isEmpty()||c.isEmpty()||a.toLongOrNull()==null||prefs.getString("folder_uri",null).isNullOrEmpty()) { status.text="Completa token, canal, ID numérico y selecciona la carpeta."; return false }
  prefs.edit().putString("token",t).putString("channel",c).putString("admin",a).apply(); return true
 }
 @Deprecated("SAF")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==4102&&resultCode==RESULT_OK) { val u=data?.data?:return; val f=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); contentResolver.takePersistableUriPermission(u,f); prefs.edit().putString("folder_uri",u.toString()).apply(); folder.text="Carpeta: "+folderName() } }
}
