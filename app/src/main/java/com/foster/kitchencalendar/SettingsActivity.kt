package com.foster.kitchencalendar

import android.Manifest
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

            root.addView(Switch(this).apply {
                text = "Compact header (more room for events)"; textSize = 16f
                isChecked = Prefs.compactHeader(this@SettingsActivity)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, v ->
                    Prefs.setCompactHeader(this@SettingsActivity, v); Scheduler.refreshAll(this@SettingsActivity)
                }
            })

            heading("Event text size")
            val sizes = listOf("Normal", "Large", "Extra large")
            root.addView(RadioGroup(this).apply {
                orientation = LinearLayout.VERTICAL
                sizes.forEachIndexed { i, label ->
                    addView(RadioButton(this@SettingsActivity).apply {
                        id = View.generateViewId(); text = label; textSize = 16f
                        tag = i
                        isChecked = Prefs.textSize(this@SettingsActivity) == i
                    })
                }
                setOnCheckedChangeListener { group, checkedId ->
                    val index = group.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
                    Prefs.setTextSize(this@SettingsActivity, index); Scheduler.refreshAll(this@SettingsActivity)
                }
            })

            heading("Week widget layout")
            root.addView(RadioGroup(this).apply {
                orientation = LinearLayout.VERTICAL
                listOf(
                    "Automatic (schedule in portrait, columns in landscape)",
                    "Always schedule (one row per day)",
                    "Always columns (seven days side by side)",
                ).forEachIndexed { i, label ->
                    addView(RadioButton(this@SettingsActivity).apply {
                        id = View.generateViewId(); text = label; textSize = 16f; tag = i
                        isChecked = Prefs.weekLayout(this@SettingsActivity) == i
                    })
                }
                setOnCheckedChangeListener { group, checkedId ->
                    val index = group.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
                    Prefs.setWeekLayout(this@SettingsActivity, index); Scheduler.refreshAll(this@SettingsActivity)
                }
            })

            buildWeatherSection(root, { heading(it) }, { para(it) })

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

    /** Weather for the day widget: Home Assistant first, Open-Meteo for the saved place as backup. */
    private fun buildWeatherSection(root: LinearLayout, heading: (String) -> Unit, para: (String) -> Unit) {
        val ctx = this
        heading("Weather (Today widget)")
        root.addView(Switch(this).apply {
            text = "Show weather"; textSize = 16f
            isChecked = Prefs.weatherEnabled(ctx)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> Prefs.setWeatherEnabled(ctx, v); Scheduler.refreshAll(ctx) }
        })
        root.addView(Switch(this).apply {
            text = "Fahrenheit (backup source)"; textSize = 16f
            isChecked = Prefs.weatherFahrenheit(ctx)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v ->
                Prefs.setWeatherFahrenheit(ctx, v); WeatherRepository.invalidate(ctx); Scheduler.refreshAll(ctx)
            }
        })

        para("Home Assistant is used first. Use a long-lived access token (your HA profile → Security). Leave blank to skip.")
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); textSize = 16f; setSingleLine()
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }.also { root.addView(it) }
        val url = field("http://homeassistant.local:8123", Prefs.haUrl(ctx))
        val token = field("Long-lived access token", Prefs.haToken(ctx), secret = true)
        val entity = field("weather.forecast_home", Prefs.haEntity(ctx))
        root.addView(Button(this).apply {
            text = "Save Home Assistant settings"
            setOnClickListener {
                Prefs.setHomeAssistant(ctx, url.text.toString(), token.text.toString(), entity.text.toString())
                WeatherRepository.invalidate(ctx); Scheduler.refreshAll(ctx)
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            }
        })

        para("Backup source (Open-Meteo, no account needed) uses this location if Home Assistant can't be reached.")
        val placeNow = TextView(this).apply { text = "Current: ${Prefs.weatherPlace(ctx)}"; textSize = 14f }
        root.addView(placeNow)
        val place = field("City, state (e.g. Greenwich, CT)", "")
        root.addView(Button(this).apply {
            text = "Look up location"
            setOnClickListener {
                val q = place.text.toString()
                if (q.isBlank()) { place.error = "Enter a city"; return@setOnClickListener }
                Thread {
                    val hit = WeatherRepository.geocode(q)
                    runOnUiThread {
                        if (hit == null) {
                            place.error = "Couldn't find that place"
                        } else {
                            Prefs.setWeatherPlace(ctx, hit.first, hit.second, hit.third)
                            placeNow.text = "Current: ${hit.third}"
                            place.setText("")
                            WeatherRepository.invalidate(ctx); Scheduler.refreshAll(ctx)
                        }
                    }
                }.start()
            }
        })

        root.addView(Button(this).apply {
            text = "Test weather"
            setOnClickListener {
                Thread {
                    WeatherRepository.invalidate(ctx)
                    val wx = WeatherRepository.get(ctx, forceRefresh = true)
                    runOnUiThread {
                        val msg = if (wx == null) "Couldn't get weather from either source"
                        else "${wx.temp ?: "?"}${wx.unit} ${wx.label} · via ${wx.source}"
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        Scheduler.refreshAll(ctx)
                    }
                }.start()
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
        Scheduler.ensureScheduled(this)
        Scheduler.refreshAll(this)
    }
}
