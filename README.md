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
| `SettingsActivity.kt` | Calendar access, calendar picker, week start, show times |

### Why one big image?

Home-screen widgets only support a few basic view types, which can't produce this look.
Drawing to a bitmap gives full design freedom. Because a full-screen bitmap is too large
to pass to the launcher directly, it's saved as a PNG and shared via a private
`content://` URI that only the launcher can read. A transparent grid of tap targets
sits on top, sized by the same proportions (`MonthLayout` ↔ `widget_month.xml`).

## Next: day and week views

Planned as additional widgets (`DayWidgetProvider`, `WeekWidgetProvider`) that reuse
`CalendarRepository`, `Themes`, `Motif`, `ImageStore`, and the scheduling, each with its own renderer.
