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
import android.util.TypedValue
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders and pushes every widget kind. All heavy work runs on one background thread. */
object WidgetUpdater {
    private const val TAG = "KitchenCalendar"
    private const val MAX_SIDE_PX = 2560f
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Queue a redraw. [kind] null = every widget kind; [ids] null = every placed widget of
     * that kind. [onDone] always runs (use it to finish goAsync / jobs).
     */
    fun request(context: Context, kind: WidgetKind? = null, ids: IntArray? = null, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            try {
                val kinds = if (kind == null) WidgetKind.entries.toList() else listOf(kind)
                for (k in kinds) {
                    try {
                        update(app, k, if (kind != null && ids != null) ids else k.ids(app))
                    } catch (t: Throwable) {
                        Log.e(TAG, "Widget update failed for $k", t)
                    }
                }
            } finally {
                onDone?.invoke()
            }
        }
    }

    private fun update(context: Context, kind: WidgetKind, ids: IntArray) {
        Themes.dark = Prefs.isDark(context)
        Prefs.setLastDrawnDark(context, Themes.dark)
        // The tick also catches dark-mode flips, so widgets that change color keep it running too.
        val ticking = listOf(WidgetKind.DAY, WidgetKind.WEATHER, WidgetKind.DATE, WidgetKind.MINI_WEATHER, WidgetKind.INDOOR,
            WidgetKind.MONTH, WidgetKind.WEEK, WidgetKind.MEALS, WidgetKind.PEOPLE)
        if (kind in ticking) Scheduler.scheduleTick(context, ticking.any { it.ids(context).isNotEmpty() })
        if (ids.isEmpty()) return
        // Re-arm tomorrow's redraw. (The calendar-change job re-arms itself; scheduling it
        // from here could cancel it mid-run.)
        Scheduler.scheduleMidnight(context)
        val mgr = AppWidgetManager.getInstance(context)
        if (kind == WidgetKind.INDOOR || kind == WidgetKind.WATCH || kind == WidgetKind.MEALS || kind == WidgetKind.TANK) {
            when (kind) {
                WidgetKind.TANK -> updateTank(context, mgr, ids)
                WidgetKind.INDOOR -> updateIndoor(context, mgr, ids)
                WidgetKind.WATCH -> updateWatch(context, mgr, ids)
                else -> updateMeals(context, mgr, ids)
            }
            return   // no calendar access needed
        }
        if (kind == WidgetKind.STICKY || kind == WidgetKind.EASEL) {
            if (kind == WidgetKind.STICKY) updateSticky(context, mgr, ids) else updateEasel(context, mgr, ids)
            return   // no calendar access needed
        }
        if (kind == WidgetKind.WEATHER || kind == WidgetKind.MINI_WEATHER) {
            if (kind == WidgetKind.WEATHER) updateWeather(context, mgr, ids) else updateMiniWeather(context, mgr, ids)
            return   // no calendar access needed
        }
        if (!CalendarRepository.hasPermission(context)) {
            ids.forEach { mgr.updateAppWidget(it, messageViews(context, context.getString(R.string.widget_needs_permission))) }
            return
        }
        when (kind) {
            WidgetKind.MONTH -> updateMonth(context, mgr, ids)
            WidgetKind.WEEK -> updateWeek(context, mgr, ids)
            WidgetKind.DAY -> updateDay(context, mgr, ids)
            WidgetKind.WEATHER -> updateWeather(context, mgr, ids)
            WidgetKind.DATE -> updateDate(context, mgr, ids)
            WidgetKind.MINI_WEATHER -> updateMiniWeather(context, mgr, ids)
            WidgetKind.STICKY -> updateSticky(context, mgr, ids)
            WidgetKind.EASEL -> updateEasel(context, mgr, ids)
            WidgetKind.INDOOR -> updateIndoor(context, mgr, ids)
            WidgetKind.WATCH -> updateWatch(context, mgr, ids)
            WidgetKind.MEALS -> updateMeals(context, mgr, ids)
            WidgetKind.TANK -> updateTank(context, mgr, ids)
            WidgetKind.PEOPLE -> updatePeople(context, mgr, ids)
        }
    }

    private fun updateMonth(context: Context, mgr: AppWidgetManager, ids: IntArray) {
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

    private fun updateWeek(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val end = today.plusDays((WeekLayout.DAYS - 1).toLong())
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli() - DAY_MS
        val endMs = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + DAY_MS
        val events = CalendarRepository.loadEvents(context, startMs, endMs, Prefs.selectedCalendars(context))
        val spec = WeekSpec(
            start = today,
            today = today,
            eventsByDay = CalendarRepository.eventsByDay(events, today, end, zone),
            showTimes = Prefs.showTimes(context),
            use24h = DateFormat.is24HourFormat(context),
            zone = zone,
            textScale = Prefs.textScale(context),
        )
        val mode = Prefs.weekLayout(context)
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val columns = WeekLayout.useColumns(mode, viewW, viewH)
                val uri = save(WeekRenderer.render(wPx, hPx, spec, columns))
                val padPx = MonthLayout.pad(viewW.roundToInt(), viewH.roundToInt()).roundToInt()
                weekViews(context, id, uri, padPx, columns, today, zone)
            }
        }
    }

    private fun updateDay(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)   // flips to the new day at midnight
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = CalendarRepository.loadEvents(context, startMs - DAY_MS, endMs + DAY_MS, Prefs.selectedCalendars(context))
        val spec = DaySpec(
            date = today,
            nowMillis = System.currentTimeMillis(),
            isToday = true,
            events = CalendarRepository.eventsByDay(events, today, today, zone)[today].orEmpty(),
            weather = WeatherRepository.get(context),
            wear = if (Prefs.wearOnToday(context)) wearAdvice(context, zone, today) else null,
            use24h = DateFormat.is24HourFormat(context),
            zone = zone,
            textScale = Prefs.textScale(context),
        )
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, _, _, save ->
                val uri = save(DayRenderer.render(wPx, hPx, spec))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, openCalendarIntent(context, id, -1, today, zone))
                }
            }
        }
    }

    /** Force-refresh weather data, then redraw the weather and Today widgets. */
    fun refreshWeatherNow(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            try { WeatherRepository.bundle(app, forceRefresh = true) } catch (t: Throwable) { Log.e(TAG, "Weather refresh failed", t) }
        }
        request(app, WidgetKind.WEATHER)
        request(app, WidgetKind.MINI_WEATHER)
        request(app, WidgetKind.INDOOR)
        request(app, WidgetKind.DAY, onDone = onDone)
    }

    private fun wearAdvice(context: Context, zone: ZoneId, today: LocalDate): WearAdvice? =
        WeatherRepository.bundle(context)?.let { WhatToWear.advise(it, Prefs.kidsRun(context), zone, today) }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7,
        Intent(context, MonthWidgetProvider::class.java).setAction(MonthWidgetProvider.ACTION_REFRESH_WEATHER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun updateMiniWeather(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val bundle = WeatherRepository.bundle(context)
        val spec = WeatherMiniSpec(
            now = bundle?.now ?: WeatherRepository.get(context),
            wear = bundle?.let { WhatToWear.advise(it, Prefs.kidsRun(context), zone, today) },
        )
        val density = context.resources.displayMetrics.density
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val uri = save(WeatherMiniRenderer.render(wPx, hPx, viewW / density, viewH / density, spec))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, refreshIntent(context))
                }
            }
        }
    }

    private fun updateDate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val tomorrow = today.plusDays(1)
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = tomorrow.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = CalendarRepository.loadEvents(context, startMs - DAY_MS, endMs + DAY_MS, Prefs.selectedCalendars(context))
        val byDay = CalendarRepository.eventsByDay(events, today, tomorrow, zone)
        val now = System.currentTimeMillis()
        val use24h = DateFormat.is24HourFormat(context)
        val upcoming = mutableListOf<Upcoming>()
        byDay[today].orEmpty().forEach { e ->
            if (e.allDay) upcoming += Upcoming("All day", e)
            else if (e.endMillis > now) upcoming += Upcoming(Draw.timeLabel(e.beginMillis, zone, use24h), e)
        }
        byDay[tomorrow].orEmpty().forEach { e ->
            upcoming += Upcoming(if (e.allDay) "Tmrw" else "Tmrw ${Draw.timeLabel(e.beginMillis, zone, use24h)}", e)
        }
        // Timed events first so "what's next" leads; all-day items after them.
        val ordered = upcoming.filter { !it.event.allDay && it.lead != "Tmrw" && !it.lead.startsWith("Tmrw") } +
            upcoming.filter { it.event.allDay && !it.lead.startsWith("Tmrw") } +
            upcoming.filter { it.lead.startsWith("Tmrw") }
        val spec = DateMiniSpec(today, ordered)
        val density = context.resources.displayMetrics.density
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val uri = save(DateMiniRenderer.render(wPx, hPx, viewW / density, viewH / density, spec))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, openCalendarIntent(context, id, -1, today, zone))
                }
            }
        }
    }

    private fun updateWeather(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val bundle = WeatherRepository.bundle(context)
        val spec = WeatherSpec(
            bundle = bundle,
            wear = bundle?.let { WhatToWear.advise(it, Prefs.kidsRun(context), zone, today) },
            use24h = DateFormat.is24HourFormat(context),
            zone = zone,
            textScale = Prefs.textScale(context),
            placeName = Prefs.weatherPlace(context),
        )
        val refresh = refreshIntent(context)
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, _, _, save ->
                val uri = save(WeatherRenderer.render(wPx, hPx, spec))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, refresh)   // tap to refresh
                }
            }
        }
    }

    private fun updateSticky(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val notes = StickyStore.all(context)
        val pkg = context.packageName
        val density = context.resources.displayMetrics.density
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val uri = save(StickyRenderer.render(wPx, hPx, density * wPx / viewW, notes))
                val (cols, rows) = StickyBoard.grid(notes.size, viewW, viewH)
                val plus = StickyBoard.addButton(notes.size, viewW, viewH, density)
                RemoteViews(pkg, R.layout.widget_sticky).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    removeAllViews(R.id.grid)
                    if (notes.isEmpty()) {
                        // Empty board: tapping anywhere starts the first note.
                        setOnClickPendingIntent(R.id.widget_root, stickyIntent(context, id, 99, -1))
                    } else for (r in 0 until rows) {
                        val row = RemoteViews(pkg, R.layout.widget_row)
                        for (c in 0 until cols) {
                            val slot = r * cols + c
                            val cell = RemoteViews(pkg, R.layout.widget_cell)
                            // Empty leftover spots also start a new note.
                            cell.setOnClickPendingIntent(R.id.cell, stickyIntent(context, id, slot, notes.getOrNull(slot)?.id ?: -1))
                            row.addView(R.id.row, cell)
                        }
                        addView(R.id.grid, row)
                    }
                    // The floating + gets its own tap target on top of the notes.
                    setViewLayoutWidth(R.id.add_button, plus.width(), TypedValue.COMPLEX_UNIT_PX)
                    setViewLayoutHeight(R.id.add_button, plus.height(), TypedValue.COMPLEX_UNIT_PX)
                    setViewLayoutMargin(R.id.add_button, RemoteViews.MARGIN_LEFT, plus.left, TypedValue.COMPLEX_UNIT_PX)
                    setViewLayoutMargin(R.id.add_button, RemoteViews.MARGIN_TOP, plus.top, TypedValue.COMPLEX_UNIT_PX)
                    setOnClickPendingIntent(R.id.add_button, stickyIntent(context, id, 98, -1))
                }
            }
        }
    }

    private fun updateIndoor(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val bundle = WeatherRepository.bundle(context)
        val spec = IndoorSpec(
            indoor = bundle?.station?.indoor,
            outdoorTemp = bundle?.now?.temp,
            fahrenheit = Prefs.weatherFahrenheit(context),
            configured = Prefs.ambientApiKey(context).isNotEmpty() && Prefs.ambientAppKey(context).isNotEmpty(),
        )
        val density = context.resources.displayMetrics.density
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val uri = save(IndoorRenderer.render(wPx, hPx, viewW / density, viewH / density, spec))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, refreshIntent(context))   // tap to refresh
                }
            }
        }
    }

    private fun updateWatch(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val services = WatchServices.installed(context)
        val pkg = context.packageName
        val density = context.resources.displayMetrics.density
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val uri = save(WatchRenderer.render(wPx, hPx, density * wPx / viewW, services))
                val pad = 8 * density
                val (cols, rows) = WatchRenderer.grid(services.size, viewW - pad * 2, viewH - pad * 2)
                RemoteViews(pkg, R.layout.widget_grid).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setViewPadding(R.id.grid, pad.toInt(), pad.toInt(), pad.toInt(), pad.toInt())
                    removeAllViews(R.id.grid)
                    if (services.isNotEmpty()) for (r in 0 until rows) {
                        val row = RemoteViews(pkg, R.layout.widget_row)
                        for (c in 0 until cols) {
                            val slot = r * cols + c
                            val cell = RemoteViews(pkg, R.layout.widget_cell)
                            services.getOrNull(slot)?.let { s ->
                                WatchServices.launchIntent(context, s.pkg)?.let { intent ->
                                    cell.setOnClickPendingIntent(R.id.cell, PendingIntent.getActivity(
                                        context, 600_000 + id * 100 + slot, intent,
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                                    ))
                                }
                            }
                            row.addView(R.id.row, cell)
                        }
                        addView(R.id.grid, row)
                    }
                }
            }
        }
    }

    private fun updateMeals(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val today = LocalDate.now()
        val week = MealStore.week(context, today)
        val photo = week.first().second.meal?.let { MealStore.photo(context, it.id) }
        val spec = MealSpec(today, week, MealStore.backup(context), MealStore.pendingFeedback(context, today), photo)
        val density = context.resources.displayMetrics.density
        val open = PendingIntent.getActivity(
            context, 700_000,
            Intent(context, MealPlanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val uri = save(MealRenderer.render(wPx, hPx, viewW / density, viewH / density, spec))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, open)
                }
            }
        }
        photo?.recycle()
    }

    private fun updateTank(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val today = LocalDate.now()
        val spec = TankSpec(today, TankStore.tasks(context), TankStore.tankName(context))
        val density = context.resources.displayMetrics.density
        val open = PendingIntent.getActivity(
            context, 800_000,
            Intent(context, AquariumActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, viewH, save ->
                val uri = save(TankRenderer.render(wPx, hPx, viewW / density, viewH / density, spec))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, open)
                }
            }
        }
    }

    private fun updatePeople(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val people = PeopleStore.people(context)
        val selected = Prefs.selectedCalendars(context)
        val owned = people.flatMap { it.calendarIds }.toSet()
        val wanted: Set<Long>? = if (selected == null) null else selected + owned
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = CalendarRepository.loadEvents(context, startMs - DAY_MS, endMs + DAY_MS, wanted)
        val todays = CalendarRepository.eventsByDay(events, today, today, zone)[today].orEmpty()
        val columns = people.map { p ->
            PersonColumn(p, PeopleStore.photo(context, p.id), family = false, events = todays.filter { it.calendarId in p.calendarIds })
        }.toMutableList()
        // Shared calendars nobody owns (e.g. "Family") get their own column when they have something today.
        val shared = todays.filter { it.calendarId !in owned }
        if (PeopleStore.showFamily(context) && shared.isNotEmpty()) {
            columns.add(0, PersonColumn(Person("family", "Family", Themes.forMonth(today.monthValue).todayFill, emptySet()), null, true, shared))
        }
        val spec = PeopleSpec(today, System.currentTimeMillis(), columns, DateFormat.is24HourFormat(context), zone, Prefs.textScale(context))
        val setup = PendingIntent.getActivity(
            context, 900_000,
            Intent(context, PeopleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, _, viewH, save ->
                val uri = save(PeopleRenderer.render(wPx, hPx, spec))
                RemoteViews(context.packageName, R.layout.widget_people).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, openCalendarIntent(context, id, -1, today, zone))
                    // The faces at the top open the People setup.
                    setViewLayoutHeight(R.id.header_tap, viewH * 0.24f, android.util.TypedValue.COMPLEX_UNIT_PX)
                    setOnClickPendingIntent(R.id.header_tap, setup)
                }
            }
        }
        columns.forEach { it.photo?.recycle() }
    }

    private fun updateEasel(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val preview = EaselRoll.preview(context)
        val density = context.resources.displayMetrics.density
        val open = PendingIntent.getActivity(
            context, 400_000,
            Intent(context, EaselActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            pushSizes(context, mgr, id) { wPx, hPx, viewW, _, save ->
                val uri = save(EaselRenderer.render(wPx, hPx, density * wPx / viewW, preview))
                RemoteViews(context.packageName, R.layout.widget_day).apply {
                    setImageViewUri(R.id.calendar_image, uri)
                    setOnClickPendingIntent(R.id.widget_root, open)
                }
            }
        }
        preview?.recycle()
    }

    private fun stickyIntent(context: Context, widgetId: Int, slot: Int, noteId: Int): PendingIntent {
        val intent = Intent(context, StickyEditorActivity::class.java)
            .setData(Uri.parse("kitchencalendar://sticky/$widgetId/$slot/$noteId"))
            .putExtra(StickyEditorActivity.EXTRA_NOTE_ID, noteId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            context, 500_000 + widgetId * 100 + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Renders one image per launcher size (portrait/landscape) and pushes them together.
     * [build] gets bitmap px, view px, and a save function that stores a bitmap and
     * returns its URI.
     */
    private fun pushSizes(
        context: Context, mgr: AppWidgetManager, id: Int,
        build: (wPx: Int, hPx: Int, viewW: Float, viewH: Float, save: (android.graphics.Bitmap) -> Uri) -> RemoteViews,
    ) {
        val density = context.resources.displayMetrics.density
        val generation = System.currentTimeMillis()
        val views = LinkedHashMap<SizeF, RemoteViews>()
        widgetSizes(mgr.getAppWidgetOptions(id)).forEachIndexed { index, size ->
            val viewW = max(1f, size.width * density)
            val viewH = max(1f, size.height * density)
            val scale = min(1f, MAX_SIDE_PX / max(viewW, viewH))
            val save: (android.graphics.Bitmap) -> Uri = { bmp ->
                val uri = ImageStore.save(context, id, index, generation, bmp)
                bmp.recycle()
                uri
            }
            views[size] = build((viewW * scale).roundToInt(), (viewH * scale).roundToInt(), viewW, viewH, save)
        }
        mgr.updateAppWidget(id, if (views.size == 1) views.values.first() else RemoteViews(views))
        ImageStore.cleanup(context, id, generation)
    }

    private fun weekViews(
        context: Context, widgetId: Int, image: Uri, padPx: Int, columns: Boolean, start: LocalDate, zone: ZoneId,
    ): RemoteViews {
        val pkg = context.packageName
        val rv = RemoteViews(pkg, if (columns) R.layout.widget_week_cols else R.layout.widget_week_rows)
        rv.setImageViewUri(R.id.calendar_image, image)
        rv.setViewPadding(R.id.overlay, padPx, 0, padPx, padPx)
        rv.setOnClickPendingIntent(R.id.header_tap, openCalendarIntent(context, widgetId, -1, start, zone))
        rv.removeAllViews(R.id.days)
        for (i in 0 until WeekLayout.DAYS) {
            val item = RemoteViews(pkg, if (columns) R.layout.widget_cell else R.layout.widget_row)
            item.setOnClickPendingIntent(
                if (columns) R.id.cell else R.id.row,
                openCalendarIntent(context, widgetId, i, start.plusDays(i.toLong()), zone),
            )
            rv.addView(R.id.days, item)
        }
        return rv
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
