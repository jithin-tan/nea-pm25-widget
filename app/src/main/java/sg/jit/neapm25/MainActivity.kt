package sg.jit.neapm25
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val t = TextView(this); t.text = "NEA PM2.5 Widget\n\nLong-press your home screen → Widgets → NEA PM2.5.\n\nThe widget displays five regional 1-hour PM2.5 concentrations (µg/m³)."; t.textSize=18f; t.setPadding(36,60,36,36); setContentView(t)
  PmWidget.schedule(this); PmWidget.refresh(this)
 }
}