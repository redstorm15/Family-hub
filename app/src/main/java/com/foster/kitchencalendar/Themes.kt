package com.foster.kitchencalendar

import android.graphics.Color

/**
 * A seasonal look for one month. Colors are ARGB ints.
 * Every view (month, and later day/week) pulls its palette and header art from here.
 */
data class MonthTheme(
    val tagline: String,
    val headerBg: Int,        // header band + outer frame
    val headerText: Int,      // month name, year, tagline, nav arrows
    val bodyBg: Int,          // panel behind the grid
    val cellBg: Int,          // in-month day cells
    val outCellBg: Int,       // days from the previous/next month
    val dayText: Int,         // day numbers, weekday labels
    val mutedText: Int,       // out-of-month day numbers, "+2 more"
    val todayFill: Int,       // circle behind today's number; default chip color
    val todayText: Int,
    val decoColors: List<Int>,// motif fills, picked in rotation
    val decoAccent: Int,      // stems, centers, eyes, ribbons
    val motifs: List<Motif>,
)

private fun c(hex: String) = Color.parseColor(hex)

object Themes {
    private val white = c("#FFFFFF")
    private val paper = c("#F1EFE8")

    /** Set by the widget updater before each draw: true = night colors. */
    @Volatile var dark = false

    /** Index 1..12 (java.time month value). Dark variant when [dark] is on. */
    fun forMonth(month: Int): MonthTheme = all[(month - 1).coerceIn(0, 11)].let { if (dark) night(it) else it }

    private val nightCache = HashMap<MonthTheme, MonthTheme>()

    /**
     * Night version of a month: charcoal body and cells with a hint of the month's color,
     * a deeper header, light text, and the same accents and motifs.
     */
    fun night(t: MonthTheme): MonthTheme = synchronized(nightCache) {
        nightCache.getOrPut(t) {
            val header = Motif.blend(t.headerBg, c("#000000"), 0.38f)
            val today = if (Draw.luminance(t.todayFill) < 0.22) Motif.blend(t.todayFill, white, 0.3f) else t.todayFill
            t.copy(
                headerBg = header,
                headerText = Draw.onColor(header),
                bodyBg = Motif.blend(c("#151517"), t.headerBg, 0.10f),
                cellBg = Motif.blend(c("#232327"), t.headerBg, 0.09f),
                outCellBg = Motif.blend(c("#1B1B1E"), t.headerBg, 0.05f),
                dayText = c("#ECEAE4"),
                mutedText = c("#8E8B85"),
                todayFill = today,
                todayText = Draw.onColor(today),
                decoColors = t.decoColors.map { Motif.blend(it, c("#000000"), 0.22f) },
            )
        }
    }

    val all: List<MonthTheme> = listOf(
        // January — winter wonderland
        MonthTheme(
            tagline = "Happy New Year", headerBg = c("#0C447C"), headerText = white,
            bodyBg = c("#E6F1FB"), cellBg = white, outCellBg = c("#D6E6F5"),
            dayText = c("#042C53"), mutedText = c("#6E8FB0"),
            todayFill = c("#185FA5"), todayText = white,
            decoColors = listOf(white, c("#B5D4F4"), c("#85B7EB")), decoAccent = c("#FAC775"),
            motifs = listOf(Motif.SNOWFLAKE, Motif.SNOWFLAKE, Motif.STAR),
        ),
        // February — Valentine's
        MonthTheme(
            tagline = "Love is in the air", headerBg = c("#D4537E"), headerText = white,
            bodyBg = c("#FBEAF0"), cellBg = white, outCellBg = c("#F6DCE5"),
            dayText = c("#4B1528"), mutedText = c("#B07A8D"),
            todayFill = c("#993556"), todayText = white,
            decoColors = listOf(c("#F4C0D1"), white, c("#993556"), c("#ED93B1")), decoAccent = white,
            motifs = listOf(Motif.HEART),
        ),
        // March — St. Patrick's Day
        MonthTheme(
            tagline = "Lucky you", headerBg = c("#3B6D11"), headerText = white,
            bodyBg = c("#EAF3DE"), cellBg = white, outCellBg = c("#DCE9CB"),
            dayText = c("#173404"), mutedText = c("#7E9868"),
            todayFill = c("#27500A"), todayText = white,
            decoColors = listOf(c("#97C459"), c("#C0DD97"), c("#639922")), decoAccent = c("#FAC775"),
            motifs = listOf(Motif.CLOVER, Motif.CLOVER, Motif.STAR),
        ),
        // April — spring showers and eggs
        MonthTheme(
            tagline = "Spring has sprung", headerBg = c("#7F77DD"), headerText = white,
            bodyBg = c("#EEEDFE"), cellBg = white, outCellBg = c("#E0DEF9"),
            dayText = c("#26215C"), mutedText = c("#8C86C4"),
            todayFill = c("#534AB7"), todayText = white,
            decoColors = listOf(c("#F4C0D1"), c("#FAC775"), c("#9FE1CB"), c("#B5D4F4")), decoAccent = white,
            motifs = listOf(Motif.EGG, Motif.RAINDROP, Motif.FLOWER),
        ),
        // May — in bloom
        MonthTheme(
            tagline = "In full bloom", headerBg = c("#1D9E75"), headerText = white,
            bodyBg = c("#E1F5EE"), cellBg = white, outCellBg = c("#CDEDE2"),
            dayText = c("#04342C"), mutedText = c("#6BA493"),
            todayFill = c("#0F6E56"), todayText = white,
            decoColors = listOf(c("#F4C0D1"), c("#FAC775"), white, c("#ED93B1")), decoAccent = c("#EF9F27"),
            motifs = listOf(Motif.FLOWER, Motif.FLOWER, Motif.LEAF),
        ),
        // June — hello summer
        MonthTheme(
            tagline = "Hello summer", headerBg = c("#FAC775"), headerText = c("#633806"),
            bodyBg = c("#FAEEDA"), cellBg = white, outCellBg = c("#F3E2C3"),
            dayText = c("#412402"), mutedText = c("#AE8D5E"),
            todayFill = c("#D85A30"), todayText = white,
            decoColors = listOf(c("#EF9F27"), white, c("#F0997B")), decoAccent = c("#D85A30"),
            motifs = listOf(Motif.SUN, Motif.FLOWER),
        ),
        // July — Fourth of July
        MonthTheme(
            tagline = "Stars and stripes", headerBg = c("#0C447C"), headerText = white,
            bodyBg = c("#FCEBEB"), cellBg = white, outCellBg = c("#F3DCDC"),
            dayText = c("#042C53"), mutedText = c("#8C8FA8"),
            todayFill = c("#A32D2D"), todayText = white,
            decoColors = listOf(c("#E24B4A"), white, c("#85B7EB")), decoAccent = white,
            motifs = listOf(Motif.STAR, Motif.FIREWORK, Motif.FIREWORK),
        ),
        // August — beach days
        MonthTheme(
            tagline = "Beach days", headerBg = c("#378ADD"), headerText = white,
            bodyBg = c("#FAEEDA"), cellBg = white, outCellBg = c("#F1E2C8"),
            dayText = c("#042C53"), mutedText = c("#9C9480"),
            todayFill = c("#185FA5"), todayText = white,
            decoColors = listOf(c("#FAC775"), white, c("#9FE1CB")), decoAccent = c("#EF9F27"),
            motifs = listOf(Motif.SUN, Motif.WAVE, Motif.STAR),
        ),
        // September — back to school, apple picking
        MonthTheme(
            tagline = "Back to school", headerBg = c("#2F4F3A"), headerText = white,
            bodyBg = paper, cellBg = white, outCellBg = c("#E4E1D7"),
            dayText = c("#2C2C2A"), mutedText = c("#9A988F"),
            todayFill = c("#A32D2D"), todayText = white,
            decoColors = listOf(c("#E24B4A"), c("#97C459"), white), decoAccent = c("#633806"),
            motifs = listOf(Motif.APPLE, Motif.APPLE, Motif.STAR),
        ),
        // October — Halloween
        MonthTheme(
            tagline = "Happy Halloween", headerBg = c("#26215C"), headerText = c("#FAC775"),
            bodyBg = c("#FAEEDA"), cellBg = white, outCellBg = c("#F0E1C8"),
            dayText = c("#412402"), mutedText = c("#A98F6C"),
            todayFill = c("#D85A30"), todayText = white,
            decoColors = listOf(c("#EF9F27"), c("#AFA9EC"), white), decoAccent = c("#3B6D11"),
            motifs = listOf(Motif.PUMPKIN, Motif.BAT, Motif.GHOST, Motif.PUMPKIN, Motif.MOON),
        ),
        // November — fall and Thanksgiving
        MonthTheme(
            tagline = "Give thanks", headerBg = c("#712B13"), headerText = c("#FAEEDA"),
            bodyBg = c("#FAECE7"), cellBg = white, outCellBg = c("#F0DCD3"),
            dayText = c("#4A1B0C"), mutedText = c("#A9806F"),
            todayFill = c("#BA7517"), todayText = white,
            decoColors = listOf(c("#EF9F27"), c("#D85A30"), c("#FAC775"), c("#BA7517")), decoAccent = c("#633806"),
            motifs = listOf(Motif.LEAF, Motif.LEAF, Motif.PUMPKIN),
        ),
        // December — Christmas
        MonthTheme(
            tagline = "Merry and bright", headerBg = c("#A32D2D"), headerText = white,
            bodyBg = c("#EAF3DE"), cellBg = white, outCellBg = c("#DCE9CB"),
            dayText = c("#173404"), mutedText = c("#7E9868"),
            todayFill = c("#A32D2D"), todayText = white,
            decoColors = listOf(white, c("#97C459"), c("#FAC775")), decoAccent = c("#FAC775"),
            motifs = listOf(Motif.SNOWFLAKE, Motif.TREE, Motif.GIFT, Motif.SNOWFLAKE, Motif.STAR),
        ),
    )
}
