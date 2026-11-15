package com.foster.kitchencalendar

import android.Manifest
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Calendar access, which calendars to show, and display options. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(48))
        }
        fun heading(text: String) = root.addView(TextView(this).apply {
            this.text = text; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        })
        fun para(text: String) = root.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setPadding(0, 0, 0, dp(12))
        })

        heading("Kitchen Calendar")
        para(
            "Long-press your home screen, choose Widgets, find Kitchen Calendar → \"Themed month\", " +
                "and drag it onto an empty page. Then resize it to fill the page. It redraws itself for " +
                "portrait and landscape.\n\nTap a day to open it in Google Calendar. Use the arrows to change " +
                "months; it drifts back to this month after a few minutes."
        )

        if (!CalendarRepository.hasPermission(this)) {
            heading("Calendar access")
            para("The widget reads events from the calendars synced on this tablet. Nothing leaves the device.")
            root.addView(Button(this).apply {
                text = "Allow calendar access"
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) }
            })
        } else {
            heading("Calendars to show")
            val calendars = CalendarRepository.listCalendars(this)
            if (calendars.isEmpty()) {
                para("No calendars found. Add a Google account in Settings → Accounts and make sure Calendar sync is on.")
            }
            val selected = (Prefs.selectedCalendars(this)
                ?: calendars.filter { it.visible }.map { it.id }.toSet()).toMutableSet()
            calendars.forEach { cal ->
                root.addView(CheckBox(this).apply {
                    text = if (cal.account.isNotBlank() && cal.account != cal.name) "${cal.name}  ·  ${cal.account}" else cal.name
                    textSize = 16f
                    isChecked = cal.id in selected
                    buttonTintList = ColorStateList.valueOf(cal.color or 0xFF000000.toInt())
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected += cal.id else selected -= cal.id
                        Prefs.setSelectedCalendars(this@SettingsActivity, selected.toSet())
                        Scheduler.refreshAll(this@SettingsActivity)
                    }
                })
            }

            heading("Display")
            root.addView(Switch(this).apply {
                text = "Week starts on Monday"; textSize = 16f
                isChecked = Prefs.weekStartsMonday(this@SettingsActivity)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, v ->
                    Prefs.setWeekStartsMonday(this@SettingsActivity, v); Scheduler.refreshAll(this@SettingsActivity)
                }
            })
            root.addView(Switch(this).apply {
                text = "Show event start times"; textSize = 16f
                isChecked = Prefs.showTimes(this@SettingsActivity)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, v ->
                    Prefs.setShowTimes(this@SettingsActivity, v); Scheduler.refreshAll(this@SettingsActivity)
                }
            })

            root.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
            root.addView(Button(this).apply {
                text = "Refresh widget now"
                setOnClickListener {
                    Scheduler.ensureScheduled(this@SettingsActivity)
                    Scheduler.refreshAll(this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "Refreshing…", Toast.LENGTH_SHORT).show()
                }
            })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
        Scheduler.ensureScheduled(this)
        Scheduler.refreshAll(this)
    }
}
