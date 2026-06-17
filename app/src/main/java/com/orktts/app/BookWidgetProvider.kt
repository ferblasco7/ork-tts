package com.orktts.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking

/** Home-screen widget showing the covers of the most recently opened books. */
class BookWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val entries = runBlocking { LibraryStore.all(context) }.take(IMAGE_IDS.size)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_books)
            IMAGE_IDS.forEachIndexed { index, viewId ->
                val entry = entries.getOrNull(index)
                if (entry?.coverPath != null) {
                    views.setImageViewBitmap(viewId, BitmapFactory.decodeFile(entry.coverPath))
                    views.setViewVisibility(viewId, View.VISIBLE)
                    views.setOnClickPendingIntent(viewId, openBookIntent(context, entry.bookUri, viewId))
                } else {
                    views.setViewVisibility(viewId, View.GONE)
                }
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun openBookIntent(context: Context, uri: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_OPEN_URI, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private val IMAGE_IDS = listOf(R.id.imageBook1, R.id.imageBook2, R.id.imageBook3, R.id.imageBook4)

        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BookWidgetProvider::class.java))
            if (ids.isNotEmpty()) BookWidgetProvider().onUpdate(context, manager, ids)
        }
    }
}
