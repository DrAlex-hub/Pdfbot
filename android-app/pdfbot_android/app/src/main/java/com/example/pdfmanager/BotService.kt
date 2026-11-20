package com.example.pdfmanager

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class BotService : Service() {
 companion object {
  const val PREFS="pdfbot_private"; const val START="BOT_START"; const val STOP="BOT_STOP"
  private const val CH="pdfbot_status"; private const val ID=7310
 }
 private val stopped=AtomicBoolean(false)
 @Volatile private var poller:Thread?=null
 @Volatile private var worker:Thread?=null
 private lateinit var token:String
 private lateinit var channel:String
 private var admin=0L
 private val api get()="https://api.telegram.org/bot$token/"

 override fun onCreate(){ super.onCreate(); createChannel() }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action==STOP){ stopped.set(true); worker?.interrupt(); poller?.interrupt(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
  if(poller?.isAlive==true) return START_NOT_STICKY
  val p=getSharedPreferences(PREFS,MODE_PRIVATE)
  token=p.getString("token","")?: ""; channel=p.getString("channel","")?: ""; admin=p.getString("admin","0")?.toLongOrNull()?:0L
  if(token.isBlank()||channel.isBlank()||admin==0L||p.getString("folder_uri",null).isNullOrBlank()){ stopSelf(); return START_NOT_STICKY }
  stopped.set(false); foreground("Conectando con Telegram…")
  poller=Thread { pollLoop() }.apply { name="TelegramPolling"; start() }
  return START_STICKY
 }

 private fun pollLoop(){
  try {
   val me=callObject("getMe",JSONObject()).getJSONObject("result")
    val botUsername = me.optString("username")
    sendMessage(admin.toString(),"🤖 PDF Bot conectado como @$botUsername. Usa /start.")
   val old=call("getUpdates",JSONObject().put("offset",-1).put("timeout",1))
   var offset=if(old.length()>0) old.getJSONObject(old.length()-1).optLong("update_id")+1 else 0L
   while(!stopped.get()){
    try{
     val updates=call("getUpdates",JSONObject().put("offset",offset).put("timeout",25).put("allowed_updates",JSONArray().put("message").put("channel_post")),35000)
     for(i in 0 until updates.length()){
      val u=updates.getJSONObject(i); offset=u.optLong("update_id")+1
      val post=u.optJSONObject("channel_post")
      if(post!=null){
       val chat=post.optJSONObject("chat")
       val chatId=chat?.optLong("id")?:0L
       val title=chat?.optString("title","Canal")?:"Canal"
       if(chatId!=0L) adminMessage("🔎 Canal detectado: $title\\nID real: $chatId")
       continue
      }
      val m=u.optJSONObject("message")?:continue
      val from=m.optJSONObject("from")?:continue
      val txt=m.optString("text","")
      if(from.optLong("id")==admin && txt.startsWith("/")) command(txt)
     }
    }catch(e:InterruptedException){break}catch(e:Exception){notifyStatus("Error de conexión; reintentando…");Thread.sleep(5000)}
   }
  }catch(e:Exception){ adminMessage("❌ No se pudo conectar con Telegram: ${e.message?: "error"}"); notifyStatus("Error de conexión") }
  finally { stopForeground(STOP_FOREGROUND_DETACH); stopSelf() }
 }

 private fun command(raw:String){
  when(raw.substringBefore(" ").substringBefore("@").lowercase()){
   "/start","/iniciar" -> sendMessage(admin.toString(),"🤖 PDF Bot Miniaturas está listo.\n/procesar - Publicar PDF\n/estado - Consultar estado\n/detener - Detener")
   "/procesar" -> if(worker?.isAlive==true) sendMessage(admin.toString(),"Ya hay un procesamiento en marcha.") else {
    worker=Thread { processPdfs() }.apply { name="PdfPublisher"; start() }
    sendMessage(admin.toString(),"▶️ Procesamiento solicitado.")
   }
   "/detener" -> { worker?.interrupt(); sendMessage(admin.toString(),"⏸ Solicitud de detención recibida.") }
   "/estado" -> sendMessage(admin.toString(),"Estado: ${if(worker?.isAlive==true) "Procesando" else "En espera"}")
  }
 }

 private fun processPdfs(){
  val uri=getSharedPreferences(PREFS,MODE_PRIVATE).getString("folder_uri",null)?.let(Uri::parse)?:return
  val root=DocumentFile.fromTreeUri(this,uri)?:return
  val docs=mutableListOf<DocumentFile>(); val stack=ArrayDeque<DocumentFile>(); stack.add(root)
  try {
   while(stack.isNotEmpty()){
    checkStop(); val d=stack.removeLast()
    for(c in d.listFiles()){ if(c.isDirectory) stack.add(c) else if(c.isFile&&c.name?.endsWith(".pdf",true)==true) docs.add(c) }
   }
   docs.sortBy{it.name?.lowercase()?:""}
   var sent=0;var big=0;var errors=0;var hitomiMatches=0;var hitomiMisses=0
   adminMessage("🚀 Procesamiento iniciado. Encontrados: ${docs.size} PDF.")
   for((idx,doc) in docs.withIndex()){
    try{
     checkStop()
     val name=doc.name?:"documento.pdf";val size=doc.length()
     if(size>50L*1024*1024){big++;continue}
     // Cada activación vuelve a publicar todos los PDF; no se consulta registro anti-duplicados.
     val hitomi=try{HitomiClient.findByPdfName(name)}catch(e:Exception){adminMessage("⚠️ Hitomi.la falló con $name: ${e.message?:"error"}");null}
     if(hitomi==null) hitomiMisses++ else hitomiMatches++
     val tags=hitomi?.let{HitomiClient.hashtags(it.tags)}.orEmpty()
     val caption=buildString {
      append("📖 ");append(name.take(350))
      if(hitomi!=null){
       append("\n🔎 ");append(hitomi.title.take(220))
       append("\n");append(hitomi.url)
      }
      if(tags.isNotBlank()){append("\n");append(tags)}
     }.take(1000)
     val cover=makeCover(doc)
     try{cover.inputStream().use{upload("sendPhoto","photo",it,cover.name,"image/jpeg",caption)}}finally{cover.delete()}
     checkStop()
     val input=contentResolver.openInputStream(doc.uri)?:error("No se pudo abrir el PDF")
     input.use{upload("sendDocument","document",it,name,"application/pdf",caption)}
     sent++;notifyStatus("Publicando $name ($sent/${docs.size})")
     if(idx%5==4) adminMessage("📊 Revisados: ${idx+1}/${docs.size}\nCompletados: $sent\nSuperiores a 50 MB: $big\nErrores: $errors")
    }catch(e:InterruptedException){throw e}catch(e:Exception){errors++;adminMessage("⚠️ Error con ${doc.name}: ${e.message?:"fallo"}")}
   }
   adminMessage("✅ Procesamiento finalizado.\nCompletados: $sent\nCoincidencias Hitomi.la: $hitomiMatches\nSin coincidencia: $hitomiMisses\nSuperiores a 50 MB: $big\nErrores: $errors")
   notifyStatus("Proceso terminado: $sent publicados")
  }catch(e:InterruptedException){adminMessage("⏸ Procesamiento detenido. Puedes reanudar con /procesar.");notifyStatus("Procesamiento detenido")}
 }

 private fun makeCover(doc:DocumentFile):File{
  val fd=contentResolver.openFileDescriptor(doc.uri,"r")?:error("No se pudo abrir el PDF")
  val out=File(cacheDir,"cover_${System.nanoTime()}.jpg")
  fd.use{pfd->PdfRenderer(pfd).use{renderer->
   if(renderer.pageCount<1)error("PDF sin páginas")
   renderer.openPage(0).use{page->
    val scale=minOf(1600f/page.width,1600f/page.height,1.5f)
    val bmp=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
    try{bmp.eraseColor(android.graphics.Color.WHITE);page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);FileOutputStream(out).use{bmp.compress(Bitmap.CompressFormat.JPEG,88,it)}}finally{bmp.recycle()}
   }
  }}
  return out
 }

 private fun upload(method:String,field:String,input:InputStream,filename:String,mime:String,caption:String){
  val boundary="----PdfBot${System.nanoTime()}"
  val conn=(URL(api+method).openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=30000;readTimeout=300000;setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")}
  conn.outputStream.use{out->
   part(out,boundary,"chat_id",channel);part(out,boundary,"caption",caption)
   out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"${filename.replace("\"","_")}\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
   input.copyTo(out);out.write("\r\n--$boundary--\r\n".toByteArray())
  }
  val code=conn.responseCode;val body=(if(code in 200..299)conn.inputStream else conn.errorStream).bufferedReader().use{it.readText()};conn.disconnect()
  val json=JSONObject(body);if(!json.optBoolean("ok"))error(json.optString("description","Telegram HTTP $code"))
 }
 private fun part(out:OutputStream,b:String,n:String,v:String){out.write("--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())}
 private fun sendMessage(chat:String,text:String){call("sendMessage",JSONObject().put("chat_id",chat).put("text",text))}
 private fun adminMessage(text:String){try{sendMessage(admin.toString(),text)}catch(_:Exception){}}
 private fun callObject(method:String,data:JSONObject,timeout:Int=30000):JSONObject{
  val c=(URL(api+method).openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=20000;readTimeout=timeout;setRequestProperty("Content-Type","application/json; charset=utf-8")}
  c.outputStream.use{it.write(data.toString().toByteArray())}
  val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};c.disconnect()
  val j=JSONObject(body);if(!j.optBoolean("ok"))error(j.optString("description","Telegram HTTP $code"))
  return j
 }
 private fun call(method:String,data:JSONObject,timeout:Int=30000):JSONArray{
  val c=(URL(api+method).openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=20000;readTimeout=timeout;setRequestProperty("Content-Type","application/json; charset=utf-8")}
  c.outputStream.use{it.write(data.toString().toByteArray())}
  val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};c.disconnect()
  val j=JSONObject(body);if(!j.optBoolean("ok"))error(j.optString("description","Telegram HTTP $code"))
  return j.opt("result") as? JSONArray ?: JSONArray()
 }
 private fun checkStop(){if(stopped.get()||Thread.currentThread().isInterrupted)throw InterruptedException()}
 private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH,"PDF Bot",NotificationManager.IMPORTANCE_LOW))}
 private fun makeNotification(text:String,ongoing:Boolean):Notification{
  val stop=PendingIntent.getService(this,1,Intent(this,BotService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val open=PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,CH)else Notification.Builder(this)
  b.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("PDF Bot Telegram").setContentText(text).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true)
  if(ongoing)b.addAction(android.R.drawable.ic_media_pause,"Detener",stop)
  return b.build()
 }
 private fun foreground(text:String){val n=makeNotification(text,true);if(Build.VERSION.SDK_INT>=29)startForeground(ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(ID,n)}
 private fun notifyStatus(text:String){getSystemService(NotificationManager::class.java).notify(ID,makeNotification(text,true))}
 override fun onDestroy(){stopped.set(true);worker?.interrupt();poller?.interrupt();super.onDestroy()}
 override fun onBind(intent:Intent?):IBinder?=null
}
