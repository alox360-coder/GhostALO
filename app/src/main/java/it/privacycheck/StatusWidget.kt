package it.privacycheck

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.util.Date

/** Widget per la schermata Home: mostra lo stato di sicurezza e un tasto [R] per aggiornarlo. */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, build(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            val app = context.applicationContext
            Thread {
                try {
                    Watch.refreshAndCheck(app)
                } catch (e: Exception) {
                    // ignora
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    companion object {
        const val ACTION_REFRESH = "it.privacycheck.REFRESH_WIDGET"

        private val green = Color.parseColor("#00FF41")
        private val grey = Color.parseColor("#00A329")
        private val orange = Color.parseColor("#FFB000")
        private val red = Color.parseColor("#FF3B30")

        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
                for (id in ids) mgr.updateAppWidget(id, build(ctx))
            } catch (e: Exception) {
                // ignora
            }
        }

        fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_status)
            val pr = Watch.prefs(ctx)
            val t = pr.getLong("st_time", 0L)
            val spy = pr.getInt("st_spy", 0)
            val acc = pr.getInt("st_acc", 0)
            val adm = pr.getInt("st_adm", 0)
            val risk = pr.getInt("st_risk", 0)

            val state: String
            val color: Int
            if (t == 0L) {
                state = tr("[??] DA CONTROLLARE")
                color = grey
            } else if (spy > 0) {
                state = tr("[XX] APP SPIA")
                color = red
            } else if (acc + adm + risk > 0) {
                state = tr("[!!] DA VERIFICARE")
                color = orange
            } else {
                state = tr("[OK] TUTTO OK")
                color = green
            }
            rv.setTextViewText(R.id.w_state, state)
            rv.setTextColor(R.id.w_state, color)
            rv.setTextViewText(R.id.w_detail, trf("spia:%s  access.:%s  admin:%s", spy, acc, adm))
            rv.setTextViewText(
                R.id.w_time,
                if (t == 0L) tr("tocca [R] per controllare")
                else tr("controllo: ") + DateFormat.format("dd/MM HH:mm", Date(t))
            )

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.w_root, open)

            val refreshIntent = Intent(ctx, StatusWidget::class.java)
            refreshIntent.action = ACTION_REFRESH
            val refresh = PendingIntent.getBroadcast(
                ctx, 1, refreshIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.w_refresh, refresh)
            return rv
        }
    }
}
