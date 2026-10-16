package sg.jit.neapm25

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class PmWidget : AppWidgetProvider() {
 override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { schedule(context); render(context); refresh(context) }
 override fun onReceive(context: Context, intent: Intent) {
  super.onReceive(context,intent)
  if (intent.action == ACTION_REFRESH) refresh(context)
 }
 companion object {
  const val ACTION_REFRESH = "sg.jit.neapm25.REFRESH"
  private val regions = listOf("north","south","east","west","central")
  fun schedule(context: Context) {
   val request=PeriodicWorkRequestBuilder<PmWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
   WorkManager.getInstance(context).enqueueUniquePeriodicWork("pm25_periodic",ExistingPeriodicWorkPolicy.KEEP,request)
  }
  fun refresh(context: Context) {
   WorkManager.getInstance(context).enqueueUniqueWork("pm25_manual",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<PmWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
  }
  fun render(context: Context) {
   val prefs=context.getSharedPreferences("pm",Context.MODE_PRIVATE)
   val manager=AppWidgetManager.getInstance(context)
   for ((receiver, layout) in listOf(
    PmWidget::class.java to R.layout.widget_pm,
    CompactPmWidget::class.java to R.layout.widget_compact
   )) {
    val component=ComponentName(context,receiver)
    if(manager.getAppWidgetIds(component).isEmpty()) continue
    val rv=RemoteViews(context.packageName,layout)
    val ids=listOf(R.id.north,R.id.south,R.id.east,R.id.west,R.id.central)
    regions.forEachIndexed { i,r -> rv.setTextViewText(ids[i],if(prefs.contains(r)) prefs.getInt(r,0).toString() else "--") }
    val stamp=prefs.getLong("stamp",0)
    val fmt=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Singapore"))
    val stale=stamp==0L || System.currentTimeMillis()-stamp>90*60*1000L
    val label=if(stamp==0L) "No readings yet" else "${fmt.format(Instant.ofEpochMilli(stamp))} SGT" + if(stale) " · STALE" else ""
    val err=prefs.getString("error",null)
    rv.setTextViewText(R.id.status,"$label · µg/m³" + if(err!=null) " · Update failed" else "")
    val intent=Intent(context,receiver).setAction(ACTION_REFRESH)
    val pi=PendingIntent.getBroadcast(context,if(layout==R.layout.widget_pm) 1 else 2,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    rv.setOnClickPendingIntent(R.id.refresh,pi)
    manager.updateAppWidget(component,rv)
   }
  }
 }
}

class CompactPmWidget : AppWidgetProvider() {
 override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { PmWidget.schedule(context); PmWidget.render(context); PmWidget.refresh(context) }
 override fun onReceive(context: Context, intent: Intent) {
  super.onReceive(context,intent)
  if(intent.action==PmWidget.ACTION_REFRESH) PmWidget.refresh(context)
 }
}

class PmWorker(context: Context, params: WorkerParameters): Worker(context,params) {
 override fun doWork(): Result {
  val prefs=applicationContext.getSharedPreferences("pm",Context.MODE_PRIVATE)
  return try {
   val connection=URL("https://api-open.data.gov.sg/v2/real-time/api/pm25").openConnection() as HttpURLConnection
   connection.connectTimeout=12000; connection.readTimeout=12000; connection.setRequestProperty("Accept","application/json")
   try {
    if(connection.responseCode!=200) throw IllegalStateException("HTTP ${connection.responseCode}")
    val json=connection.inputStream.bufferedReader().use { it.readText() }
    val root=JSONObject(json)
    val items=root.getJSONObject("data").getJSONArray("items")
    if(items.length()==0) throw IllegalStateException("Empty response")
    var selected:JSONObject?=null; var newest=0L
    for(i in 0 until items.length()) {
     val item=items.getJSONObject(i)
     val time=Instant.parse(item.getString("timestamp")).toEpochMilli()
     if(time>=newest) { newest=time; selected=item }
    }
    if(newest>System.currentTimeMillis()+5*60*1000L) throw IllegalStateException("Future timestamp")
    val readings=selected!!.getJSONObject("readings").getJSONObject("pm25_one_hourly")
    val edit=prefs.edit()
    listOf("north","south","east","west","central").forEach { region ->
     val v=readings.getInt(region); require(v>=0) { "Invalid PM2.5" }; edit.putInt(region,v)
    }
    edit.putLong("stamp",newest).remove("error").apply()
    PmWidget.render(applicationContext)
    Result.success()
   } finally { connection.disconnect() }
  } catch(e:Exception) {
   prefs.edit().putString("error",e.javaClass.simpleName).apply()
   PmWidget.render(applicationContext)
   Result.retry()
  }
 }
}