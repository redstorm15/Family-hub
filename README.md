# Kitchen Calendar

A full-screen, seasonally themed month calendar widget for an Android tablet.
Each month gets its own colors and header art (snowflakes in January, hearts in
February, pumpkins and bats in October, leaves in November, holly and gifts in
December, and so on). It reads events from the calendars synced on the tablet, so there are no API keys and nothing to sign into.

## Build and install

1. Open this folder in **Android Studio** (any 2024+ version). Let it sync. It downloads
   Gradle 8.11 and the Android plugin on first open.
   - If it asks to upgrade the Android Gradle Plugin, accept it.
2. On the tablet: **Settings → About tablet → Software information**, then tap
   **Build number** 7 times to turn on Developer options. Then go to
   **Settings → Developer options** and turn on **USB debugging** (or **Wireless debugging**).
3. Plug the tablet in, pick it in Android Studio's device menu, and press **Run ▶**.
   - Alternatively, use **Build → Build APK(s)**, copy `app/build/outputs/apk/debug/app-debug.apk`
     to the tablet, and open it there to sideload.
4. Open **Kitchen Calendar** on the tablet once, tap **Allow calendar access**, and pick
   which calendars to show.
5. Long-press the home screen → **Widgets** → **Kitchen Calendar → Themed month**. Drop it on
   an empty page and drag its handles to fill the page.

## How it behaves

- **Portrait and landscape:** it renders both sizes and the launcher swaps between them when the tablet rotates.
- **Tap a day** to open Google Calendar on that date. **Tap the month name** to open Google Calendar.
- **Arrows** step back and forward a month. Tapping the month name while away from the
  current month jumps back, and it drifts back on its own after 3 minutes.
- **Updates** within seconds of any calendar change (including events added on someone's
  phone that sync down), at midnight, and every 30 minutes as a backup.
- **Only as many weeks as the month needs** (usually 5), so cells get taller and fit more events.
- **Settings:** compact header (more room for the grid) and event text size (Normal / Large / Extra large).
- **All-day events** are solid color bars. **Timed events** show a colored dot and the start time.
  When a day overflows, it shows "+N more".

## Code map

| File | What it does |
| --- | --- |
| `CalendarRepository.kt` | Reads calendars and event instances from the device's calendar provider (shared by future day and week views) |
| `Themes.kt` | The 12 monthly palettes and motif choices. Tweak colors and taglines here |
| `Motif.kt` | Original vector illustrations drawn with Canvas (no image assets) |
| `MonthRenderer.kt` | Draws the whole month into one bitmap |
| `WidgetUpdater.kt` | Renders portrait and landscape sizes, builds the tap-target overlay, pushes to the launcher |
| `MonthWidgetProvider.kt` | Widget lifecycle and nav taps |
| `Scheduler.kt` / `CalendarChangeJob.kt` | Midnight redraw, auto-return to today, redraw on calendar changes |
| `WeekRenderer.kt` / `DayRenderer.kt` | The week and day views |
| `Weather.kt` | Home Assistant + Open-Meteo weather and weather icons |
| `Draw.kt` | Shared drawing: event chips, header art, text helpers |
| `SettingsActivity.kt` | Calendar access, calendar picker, week start, show times |

### Why one big image?

Home-screen widgets only support a few basic view types, which can't produce this look.
Drawing to a bitmap gives full design freedom. Because a full-screen bitmap is too large
to pass to the launcher directly, it's saved as a PNG and shared via a private
`content://` URI that only the launcher can read. A transparent grid of tap targets
sits on top, sized by the same proportions (`MonthLayout` ↔ `widget_month.xml`).

## Week and day widgets

**This week** (Kitchen Calendar → "This week"): a rolling 7 days starting today.
In portrait it's a schedule with one row per day; in landscape, seven columns. Tap a day to
open it in Google Calendar. Settings → Week widget layout can lock it to one style.

**Today** (Kitchen Calendar → "Today"): the full date, weather, all-day events, what's
happening now or up next (with a countdown), and a timeline that fits itself to the day
(8a–9p, extended for earlier or later events) with a line for the current time.
In landscape, the timeline sits on the left with a list on the right. It refreshes every
~15 minutes and switches to the new day at midnight.

**Weather** (Kitchen Calendar → "Weather"): your Ambient Weather station's live readings
(temperature, feels like, wind and gusts, humidity and dew point, rain today, UV), a
"What to wear" card for the kids, the hourly forecast, and a 7-day forecast with range bars.
The background follows the sky: blue when clear, gray when overcast, dark at night. Tap it to refresh.

**What to wear** uses the coldest and warmest "feels like" between 7am and 6pm, plus rain,
snow and UV, to suggest a winter coat, jacket, sweater, long sleeves, t-shirt or shorts, along
with a raincoat, umbrella, boots or sunscreen. "Kids run cold / warm" shifts it by about 5°.
It also appears on the Today widget (this can be turned off).

**Date** and **Weather (small)**: resizable from 1×1 up. Date shows the weekday and day
number at 1×1; at 2×2 it adds the month and what's next; wider or taller shows more events
(with tomorrow's when today is done). It uses the monthly theme. Small weather shows the icon and
temperature at 1×1, then the condition, high/low and feels-like, and the clothing pick and rain
chance when it's wider or taller. It uses sky colors.

### Weather

0. **Ambient Weather station (current conditions):** Settings → Ambient Weather station →
   paste the API key and Application key from ambientweather.net → Account → API Keys.
   Forecasts come from Open-Meteo at your station's location.
1. **Home Assistant (backup for current conditions):** Settings → Weather → enter your HA URL (for example
   `http://homeassistant.local:8123` or `http://192.168.x.x:8123`), a long-lived access token
   (HA profile → Security), and your weather entity (default `weather.forecast_home`).
2. **Backup:** if HA isn't set up or can't be reached, it uses Open-Meteo (free, no key) for
   the saved location (Greenwich, CT by default; change it with "Look up location").
3. **Test weather** shows which source answered. Results are cached for 30 minutes, and a
   cached reading up to 6 hours old is shown if both sources fail.

### Sticky notes

Add the **Sticky notes** widget once and resize it as big as you like. It's a family board:

- **Tap the green +** in the bottom-right corner to make a new note. A big note pops up to draw on with a finger or the S Pen.
- **Tap a note** to add to it, change its color, or **Throw away** when it's done.
- Buttons: paper colors, pen colors, **Undo**, **Erase**, **Aa** (type instead of drawing), **Stick it!**
- Tap outside the note to cancel. Erasing everything and tapping Stick it also removes the note.
- The board holds 12 notes, newest first, and they grow to fill the board. A 13th note bumps the oldest one.
- Notes are shared, so two boards show the same notes, and removing a board doesn't lose them.

### Doodle easel

Add the **Doodle easel** widget. It shows the last thing drawn (or a few crayons). Tap it and you're on paper right away.

- Tools: **Crayon**, **Marker**, **Paint** (soft, see-through), **Pencil**, **Eraser**, plus 12 big colors.
- **One finger draws, two fingers scroll** the endless roll. With the S Pen, the pen draws and one finger scrolls, so a resting hand doesn't scribble. The pen's button-eraser end erases.
- **⬇ More paper** rolls down to a fresh stretch; **🧻 New paper** clears the whole roll; **↩ Undo**; **✓ Done**.
- Art isn't saved as files. The roll stays while the tablet is on and resets after a restart or app update.

### Indoor air

Add the **Indoor air** widget. It reads the same Ambient Weather account as the weather widgets:

- Indoor temperature and humidity from the station console, and PM2.5 / PM10 / CO₂ from an AQIN (or PM2.5 from a PM25IN).
- The background color is the air quality: green good, yellow moderate, orange/red unhealthy (US EPA PM2.5 breakpoints).
- Bigger sizes add tiles for each reading and one tip ("Dry: fill and run the humidifiers", "Stuffy: open a window", which only suggests a window when the outdoor PM2.5 is clean).
- Tap to refresh. It updates with the weather every 15 minutes.

Each widget now shows its own preview picture in the widget picker.
