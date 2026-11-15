package com.foster.kitchencalendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** One occurrence of an event (recurring events are expanded by the calendar provider). */
data class CalEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val color: Int,
    val calendarId: Long,
) {
    /** First local date this event touches. All-day events are stored in UTC midnights. */
    fun startDate(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(beginMillis).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

    /** Last local date this event touches (inclusive). */
    fun endDate(zone: ZoneId): LocalDate {
        val lastInstant = maxOf(beginMillis, endMillis - 1)
        return Instant.ofEpochMilli(lastInstant).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
    }
}

data class CalInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val visible: Boolean,
)

/**
 * Reads events straight from the device's calendar provider, i.e. whatever Google
 * accounts are synced on the tablet. No API keys or sign-in needed.
 * Shared by every view (month now; day and week later).
 */
object CalendarRepository {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun listCalendars(context: Context): List<CalInfo> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
        )
        val out = mutableListOf<CalInfo>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "(unnamed)",
                    account = c.getString(2) ?: "",
                    color = c.getInt(3),
                    visible = c.getInt(4) == 1,
                )
            }
        }
        return out
    }

    /**
     * Event instances overlapping [startMillis, endMillis).
     * @param calendarIds restrict to these calendars; null = every calendar marked visible.
     */
    fun loadEvents(
        context: Context,
        startMillis: Long,
        endMillis: Long,
        calendarIds: Set<Long>?,
    ): List<CalEvent> {
        if (!hasPermission(context)) return emptyList()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMillis)
            ContentUris.appendId(it, endMillis)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_ID,
        )

        val where = StringBuilder()
        val args = mutableListOf<String>()
        // Skip events you've declined.
        where.append("(${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ?)")
        args += CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString()
        if (calendarIds == null) {
            where.append(" AND ${CalendarContract.Instances.VISIBLE} = 1")
        } else {
            if (calendarIds.isEmpty()) return emptyList()
            where.append(" AND ${CalendarContract.Instances.CALENDAR_ID} IN (")
            where.append(calendarIds.joinToString(",") { "?" })
            where.append(")")
            args += calendarIds.map { it.toString() }
        }

        val out = mutableListOf<CalEvent>()
        context.contentResolver.query(
            uri, projection, where.toString(), args.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalEvent(
                    title = c.getString(0)?.trim().orEmpty().ifEmpty { "(No title)" },
                    beginMillis = c.getLong(1),
                    endMillis = c.getLong(2),
                    allDay = c.getInt(3) == 1,
                    color = c.getInt(4),
                    calendarId = c.getLong(5),
                )
            }
        }
        return out
    }

    /** Buckets events onto each date they touch, sorted all-day first then by start time. */
    fun eventsByDay(events: List<CalEvent>, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, List<CalEvent>> {
        val map = HashMap<LocalDate, MutableList<CalEvent>>()
        for (e in events) {
            val first = e.startDate(zone)
            val end = e.endDate(zone)
            var d: LocalDate = if (first.isBefore(from)) from else first
            val last: LocalDate = if (end.isAfter(to)) to else end
            while (!d.isAfter(last)) {
                map.getOrPut(d) { mutableListOf() } += e
                d = d.plusDays(1)
            }
        }
        val order = compareBy<CalEvent>({ !it.allDay }, { it.beginMillis }, { it.title })
        return map.mapValues { (_, v) -> v.sortedWith(order) }
    }
}
