package com.foster.kitchencalendar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders and pushes the month widget. All heavy work runs on one background thread. */
object WidgetUpdater {
    private const val TAG = "KitchenCalendar"
    private const val MAX_SIDE_PX = 2560f
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val executor = Executors.newSingleThreadExecutor()

    fun allIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MonthWidgetProvider::class.java))

    /** Queue a redraw; [onDone] always runs (use it to finish goAsync / jobs). */
    fun request(context: Context, ids: IntArray? = null, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            try {
                updateNow(app, ids ?: allIds(app))
            } catch (t: Throwable) {
                Log.e(TAG, "Widget update failed", t)
            } finally {
                onDone?.invoke()
            }
        }
    }

    private fun updateNow(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        // Re-arm tomorrow's redraw. (The calendar-change job re-arms itself; scheduling it
        // from here could cancel it mid-run.)
        Scheduler.scheduleMidnight(context)
        val mgr = AppWidgetManager.getInstance(context)

        if (!CalendarRepository.hasPermission(context)) {
            ids.forEach { mgr.updateAppWidget(it, messageViews(context, context.getString(R.string.widget_needs_permission))) }
            return
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val mondays = Prefs.weekStartsMonday(context)
        val selected = Prefs.selectedCalendars(context)
        val density = context.resources.displayMetrics.density

        for (id in ids) {
            val offset = Prefs.monthOffset(context, id)
            val month = YearMonth.from(today).plusMonths(offset.toLong())
            val gridStart = MonthLayout.gridStart(month, mondays)
            val rows = MonthLayout.rowsFor(month, mondays)
            val gridEnd = gridStart.plusDays((rows * MonthLayout.COLS - 1).toLong())
            val compact = Prefs.compactHeader(context)

            // Pad a day each side so all-day events (stored at UTC midnight) aren't clipped.
            val startMs = gridStart.atStartOfDay(zone).toInstant().toEpochMilli() - DAY_MS
            val endMs = gridEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + DAY_MS
            val events = CalendarRepository.loadEvents(context, startMs, endMs, selected)

            val spec = MonthSpec(
                month = month,
                today = today,
                weekStartsMonday = mondays,
                showTimes = Prefs.showTimes(context),
                use24h = DateFormat.is24HourFormat(context),
                zone = zone,
                eventsByDay = CalendarRepository.eventsByDay(events, gridStart, gridEnd, zone),
                rows = rows,
                compactHeader = compact,
                textScale = Prefs.textScale(context),
            )

            val sizes = widgetSizes(mgr.getAppWidgetOptions(id))
            val generation = System.currentTimeMillis()
            val views = LinkedHashMap<SizeF, RemoteViews>()
            sizes.forEachIndexed { index, size ->
                val viewW = max(1f, size.width * density)
                val viewH = max(1f, size.height * density)
                val scale = min(1f, MAX_SIDE_PX / max(viewW, viewH))
                val bmp = MonthRenderer.render((viewW * scale).roundToInt(), (viewH * scale).roundToInt(), spec)
                val uri = ImageStore.save(context, id, index, generation, bmp)
                bmp.recycle()
                val padPx = MonthLayout.pad(viewW.roundToInt(), viewH.roundToInt()).roundToInt()
                views[size] = monthViews(context, id, uri, padPx, offset, gridStart, rows, compact, today, zone)
            }

            val remote = if (views.size == 1) views.values.first() else RemoteViews(views)
            mgr.updateAppWidget(id, remote)
            ImageStore.cleanup(context, id, generation)
        }
    }

    /** Portrait and landscape sizes (dp) the launcher may show this widget at. */
    private fun widgetSizes(options: Bundle): List<SizeF> {
        val list: List<SizeF>? = if (Build.VERSION.SDK_INT >= 33) {
            options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        } else {
            @Suppress("DEPRECATION")
            options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        }
        if (!list.isNullOrEmpty()) return list.filter { it.width > 0 && it.height > 0 }.distinct().take(4)

        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (minW <= 0 || minH <= 0) return listOf(SizeF(400f, 600f))
        return listOf(
            SizeF(minW.toFloat(), maxH.toFloat()),  // portrait
            SizeF(maxW.toFloat(), minH.toFloat()),  // landscape
        ).distinct()
    }

    private fun monthViews(
        context: Context, widgetId: Int, image: Uri, padPx: Int, offset: Int,
        gridStart: LocalDate, rows: Int, compact: Boolean, today: LocalDate, zone: ZoneId,
    ): RemoteViews {
        val pkg = context.packageName
        val rv = RemoteViews(pkg, if (compact) R.layout.widget_month_compact else R.layout.widget_month)
        rv.setImageViewUri(R.id.calendar_image, image)
        rv.setViewPadding(R.id.overlay, padPx, 0, padPx, padPx)

        rv.setOnClickPendingIntent(R.id.nav_prev, navIntent(context, widgetId, -1))
        rv.setOnClickPendingIntent(R.id.nav_next, navIntent(context, widgetId, +1))
        // Middle of the header: jump back to this month, or open Google Calendar if already there.
        rv.setOnClickPendingIntent(
            R.id.nav_today,
            if (offset != 0) navIntent(context, widgetId, 0) else openCalendarIntent(context, widgetId, -1, today, zone),
        )

        rv.removeAllViews(R.id.grid)
        for (r in 0 until rows) {
            val row = RemoteViews(pkg, R.layout.widget_row)
            for (c in 0 until MonthLayout.COLS) {
                val index = r * MonthLayout.COLS + c
                val cell = RemoteViews(pkg, R.layout.widget_cell)
                cell.setOnClickPendingIntent(
                    R.id.cell,
                    openCalendarIntent(context, widgetId, index, gridStart.plusDays(index.toLong()), zone),
                )
                row.addView(R.id.row, cell)
            }
            rv.addView(R.id.grid, row)
        }
        return rv
    }

    /** Opens the calendar app (Google Calendar on most devices) on [date]. */
    private fun openCalendarIntent(context: Context, widgetId: Int, slot: Int, date: LocalDate, zone: ZoneId): PendingIntent {
        val millis = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$millis"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, widgetId * 100 + slot + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** delta = -1 / +1 to step months, 0 = back to the current month. */
    private fun navIntent(context: Context, widgetId: Int, delta: Int): PendingIntent {
        val intent = Intent(context, MonthWidgetProvider::class.java)
            .setAction(MonthWidgetProvider.ACTION_NAV)
            .setData(Uri.parse("kitchencalendar://nav/$widgetId/$delta"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(MonthWidgetProvider.EXTRA_DELTA, delta)
        return PendingIntent.getBroadcast(
            context, widgetId * 100 + 60 + delta, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun messageViews(context: Context, text: String): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_message)
        rv.setTextViewText(R.id.message_text, text)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.message_root, open)
        return rv
    }
}
