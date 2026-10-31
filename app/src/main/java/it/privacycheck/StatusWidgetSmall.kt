package it.privacycheck

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Versione piccola del widget: una tessera 1x1 che si comporta come un'icona. */
class StatusWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, StatusWidget.buildSmall(context))
        }
    }
}
