package com.foster.kitchencalendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** The three columns of a plate, plus an optional dip or sauce. */
enum class Part(val label: String, val short: String) {
    PROTEIN("Protein", "Protein"),
    GRAIN("Grain", "Grain"),
    PRODUCE("Fruit / veg", "Fruit/veg"),
    EXTRA("Dip / sauce", "Dip");
}

/** One thing on the plate. [isNew] marks a ✨ try-night food. */
data class PlateItem(val name: String, val emoji: String, val isNew: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("emoji", emoji).put("new", isNew)
    companion object {
        fun from(o: JSONObject?) = o?.let { PlateItem(it.getString("name"), it.optString("emoji", "🍽️"), it.optBoolean("new")) }
    }
}

/** A dinner in the meal bank, with its usual plate. [key] marks the family staples. */
data class Meal(
    val id: String, val name: String, val emoji: String, val key: String? = null,
    val usual: Map<Part, PlateItem> = emptyMap(),
) {
    /** Takeout and eating out aren't try nights or plates you build. */
    val homeCooked get() = key != "takeout" && key != "out"
}

data class DayPlan(val meal: Meal?, val parts: Map<Part, PlateItem>, val tryNight: Boolean) {
    val newItems get() = parts.filterValues { it.isNew }
}

enum class TryResult(val code: String, val emoji: String, val label: String) {
    NOPE("nope", "👎", "Nope"), TRIED("tried", "🙂", "Tried it"), LOVED("loved", "⭐", "Loved it");
    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } }
}

data class TryRecord(val date: LocalDate, val part: Part, val item: PlateItem, val result: TryResult?)

/** What can go in each column. "Usual" foods first, then gentle new ones for try nights. */
object PlateFoods {
    private fun i(emoji: String, name: String) = PlateItem(name, emoji)

    val usual: Map<Part, List<PlateItem>> = mapOf(
        Part.PROTEIN to listOf(
            i("🍗", "Chicken nuggets"), i("🍗", "Plain chicken"), i("🧆", "Meatballs"), i("🥩", "Taco meat"),
            i("🌭", "Hot dogs"), i("🍖", "Ham"), i("🦃", "Turkey"), i("🐟", "Fish sticks"), i("🥚", "Eggs"),
            i("🧀", "Cheese"), i("🍔", "Burger"), i("🥓", "Bacon"), i("🍕", "Pepperoni"),
        ),
        Part.GRAIN to listOf(
            i("🍚", "Rice"), i("🍝", "Pasta"), i("🫓", "Tortillas"), i("🍞", "Bread"), i("🧀", "Mac & cheese"),
            i("🌭", "Hot dog bun"), i("🥔", "Potatoes"), i("🍟", "Fries"), i("🍜", "Noodles"), i("🥐", "Rolls"),
            i("🥞", "Pancakes"), i("🌽", "Tortilla chips"), i("🍕", "Pizza"),
        ),
        Part.PRODUCE to listOf(
            i("🟢", "Peas"), i("🌽", "Corn"), i("🥕", "Carrots"), i("🥦", "Broccoli"), i("🫛", "Green beans"),
            i("🥒", "Cucumber"), i("🍎", "Apple slices"), i("🍌", "Banana"), i("🍇", "Grapes"), i("🍓", "Strawberries"),
            i("🍉", "Watermelon"), i("🍏", "Applesauce"), i("🫘", "Baked beans"), i("🍊", "Clementine"),
        ),
        Part.EXTRA to listOf(
            i("🍅", "Ketchup"), i("🥛", "Ranch"), i("🧈", "Butter"), i("🧀", "Parmesan"),
        ),
    )

    /** Gentle new foods: nothing spicy, nothing heavily seasoned, close to what they already eat. */
    val new: Map<Part, List<PlateItem>> = mapOf(
        Part.PROTEIN to listOf(
            i("🍗", "Chicken with a little honey glaze"), i("🧆", "Turkey meatballs"), i("🐟", "Fish sticks"),
            i("🍤", "Plain shrimp"), i("🥚", "Scrambled eggs"), i("🍖", "Ham cubes"), i("🍗", "Chicken drumstick"),
            i("🫘", "Black beans"), i("🍔", "Mini plain burger"),
        ),
        Part.GRAIN to listOf(
            i("🥖", "Garlic bread"), i("🍠", "Sweet potato fries"), i("🫓", "Plain naan"), i("🍚", "Fried rice"),
            i("🥙", "Pita triangles"), i("🍚", "Couscous with butter"), i("🥔", "Mashed potatoes"),
            i("🧄", "Garlic knots"), i("🍜", "Lo mein noodles"), i("🥟", "One dumpling"),
        ),
        Part.PRODUCE to listOf(
            i("🥦", "Broccoli trees with cheese"), i("🥕", "Butter carrot coins"), i("🫛", "Edamame"),
            i("🌽", "Corn on the cob"), i("🥒", "Cucumber slices"), i("🫑", "Sweet pepper strips"),
            i("🥭", "Mango cubes"), i("🍍", "Pineapple chunks"), i("🫐", "Blueberries"), i("🍐", "Pear slices"),
            i("🥑", "Avocado slices"), i("🍅", "Cherry tomatoes"), i("🍠", "Roasted sweet potato"), i("🫛", "Snap peas"),
            i("🥗", "A few salad bites"), i("🥝", "Kiwi"),
        ),
        Part.EXTRA to listOf(
            i("🍯", "Honey mustard"), i("🍖", "BBQ sauce"), i("🫙", "Mild salsa"), i("🥑", "Guacamole"),
            i("🧀", "Queso"), i("🍶", "Sour cream"), i("🍯", "Honey to dip"), i("🥫", "Marinara to dip"),
        ),
    )
}

/** Emoji choices for new dinners, grouped loosely so the grid reads well. */
val MEAL_EMOJI = listOf(
    "🍗", "🍝", "🌮", "🍕", "🥡", "🍽️", "🧀", "🌭", "🍔", "🍟", "🥪", "🌯",
    "🍳", "🥞", "🧇", "🥓", "🍲", "🍛", "🍜", "🍚", "🥟", "🍱", "🍣", "🍤",
    "🐟", "🥩", "🍖", "🦃", "🥚", "🫓", "🥙", "🥗", "🥔", "🍠", "🌽", "🥦",
    "🫛", "🥕", "🍅", "🥑", "🍎", "🍓", "🍌", "🍉", "🥧", "🍰", "🍦", "🎉",
)

/** Meal bank, the dinner plan, photos and how each try night went. JSON in prefs. */
object MealStore {
    private fun prefs(c: Context) = c.getSharedPreferences("meal_plan", Context.MODE_PRIVATE)
    private fun p(part: Part, emoji: String, name: String) = part to PlateItem(name, emoji)

    private val staples = listOf(
        Meal("nuggets", "Chicken nuggets, peas & rice", "🍗", "nuggets",
            mapOf(p(Part.PROTEIN, "🍗", "Chicken nuggets"), p(Part.GRAIN, "🍚", "Rice"), p(Part.PRODUCE, "🟢", "Peas"))),
        Meal("pasta", "Pasta & meatballs", "🍝", "pasta",
            mapOf(p(Part.PROTEIN, "🧆", "Meatballs"), p(Part.GRAIN, "🍝", "Pasta"))),
        Meal("tacos", "Tacos", "🌮", "tacos",
            mapOf(p(Part.PROTEIN, "🥩", "Taco meat"), p(Part.GRAIN, "🫓", "Tortillas"))),
        Meal("pizza", "Pizza", "🍕", "pizza", mapOf(p(Part.GRAIN, "🍕", "Pizza"))),
        Meal("takeout", "Order takeout", "🥡", "takeout"),
        Meal("out", "Eat out", "🍽️", "out"),
        Meal("mac", "Mac & cheese", "🧀", "mac", mapOf(p(Part.GRAIN, "🧀", "Mac & cheese"))),
        Meal("hotdogs", "Hot dogs & beans", "🌭", "hotdogs",
            mapOf(p(Part.PROTEIN, "🌭", "Hot dogs"), p(Part.GRAIN, "🌭", "Hot dog bun"), p(Part.PRODUCE, "🫘", "Baked beans"))),
    )

    // ---------- meal bank ----------

    fun bank(c: Context): List<Meal> {
        val raw = prefs(c).getString("bank2", null) ?: return migrateBank(c)
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { mealFrom(arr.getJSONObject(it)) }
    }

    private fun mealFrom(o: JSONObject): Meal {
        val usual = o.optJSONObject("usual")?.let { u ->
            Part.entries.mapNotNull { part -> PlateItem.from(u.optJSONObject(part.name))?.let { part to it } }.toMap()
        }.orEmpty()
        return Meal(o.getString("id"), o.getString("name"), o.optString("emoji", "🍽️"), o.optString("key").ifBlank { null }, usual)
    }

    /** First run after the plate update: staples get their usual plates, custom meals carry over. */
    private fun migrateBank(c: Context): List<Meal> {
        val old = prefs(c).getString("bank", null)?.let { raw ->
            val arr = JSONArray(raw)
            (0 until arr.length()).map { mealFrom(arr.getJSONObject(it)) }
        }.orEmpty()
        val custom = old.filter { o -> staples.none { it.id == o.id } }
        val list = staples + custom
        saveBank(c, list)
        return list
    }

    private fun saveBank(c: Context, meals: List<Meal>) {
        val arr = JSONArray()
        meals.forEach { m ->
            val usual = JSONObject().also { u -> m.usual.forEach { (part, item) -> u.put(part.name, item.toJson()) } }
            arr.put(JSONObject().put("id", m.id).put("name", m.name).put("emoji", m.emoji).put("key", m.key ?: "").put("usual", usual))
        }
        prefs(c).edit().putString("bank2", arr.toString()).apply()
    }

    fun addMeal(c: Context, name: String, emoji: String): Meal {
        val m = Meal("m" + System.currentTimeMillis(), name.trim(), emoji)
        saveBank(c, bank(c) + m)
        return m
    }

    fun updateMeal(c: Context, meal: Meal) = saveBank(c, bank(c).map { if (it.id == meal.id) meal else it })

    fun removeMeal(c: Context, id: String) {
        saveBank(c, bank(c).filter { it.id != id })
        photoFile(c, id).delete()
    }

    fun backup(c: Context): String = prefs(c).getString("backup", "plain chicken & rice")!!
    fun setBackup(c: Context, text: String) = prefs(c).edit().putString("backup", text.trim()).apply()

    // ---------- photos (your own pictures of your dinners) ----------

    fun photoFile(c: Context, mealId: String) = File(File(c.filesDir, "meal_photos").apply { mkdirs() }, "$mealId.jpg")

    fun photo(c: Context, mealId: String): Bitmap? = photoFile(c, mealId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Saves a picture for a meal, shrunk to at most 1600 px on the long side. */
    fun savePhoto(c: Context, mealId: String, open: () -> java.io.InputStream?) =
        Photos.saveUpright(open, photoFile(c, mealId), maxSide = 1600)

    fun removePhoto(c: Context, mealId: String) { photoFile(c, mealId).delete() }

    /** A warm backdrop color per meal for the illustrated card (when there's no photo). */
    fun color(meal: Meal): Int {
        val palette = listOf("#F4A261", "#E76F51", "#E9C46A", "#F28482", "#84A59D", "#F6BD60", "#B5838D", "#90BE6D")
        val fixed = mapOf("nuggets" to 0, "pasta" to 1, "tacos" to 2, "pizza" to 3, "takeout" to 4, "out" to 6, "mac" to 5, "hotdogs" to 7)
        val idx = fixed[meal.key] ?: abs(meal.id.hashCode()) % palette.size
        return Color.parseColor(palette[idx])
    }

    // ---------- plan ----------

    private fun planJson(c: Context) = JSONObject(prefs(c).getString("plan", "{}")!!)

    fun day(c: Context, date: LocalDate): DayPlan {
        val o = planJson(c).optJSONObject(date.toString()) ?: return DayPlan(null, emptyMap(), false)
        val meal = bank(c).firstOrNull { it.id == o.optString("meal") }
        val parts = o.optJSONObject("parts")?.let { j ->
            Part.entries.mapNotNull { part -> PlateItem.from(j.optJSONObject(part.name))?.let { part to it } }.toMap()
        } ?: run {
            // Plans saved before the plate update: usual plate + the old single try food as a new fruit/veg.
            val old = o.optJSONObject("try")?.let { PlateItem(it.getString("name"), it.optString("emoji", "✨"), true) }
            meal?.usual.orEmpty() + (old?.let { mapOf(Part.PRODUCE to it) } ?: emptyMap())
        }
        return DayPlan(meal, parts, o.optBoolean("tryNight", o.has("try")))
    }

    fun week(c: Context, start: LocalDate): List<Pair<LocalDate, DayPlan>> =
        (0 until 7).map { start.plusDays(it.toLong()) }.map { it to day(c, it) }

    private fun save(c: Context, date: LocalDate, plan: DayPlan?) {
        val all = planJson(c)
        val meal = plan?.meal
        if (plan == null || meal == null) all.remove(date.toString())
        else {
            val parts = JSONObject().also { j -> plan.parts.forEach { (part, item) -> j.put(part.name, item.toJson()) } }
            all.put(date.toString(), JSONObject().put("meal", meal.id).put("parts", parts).put("tryNight", plan.tryNight))
        }
        val cutoff = LocalDate.now().minusDays(21).toString()
        all.keys().asSequence().toList().filter { it < cutoff }.forEach { all.remove(it) }
        prefs(c).edit().putString("plan", all.toString()).apply()
        syncTryRecords(c, date, plan)
    }

    /** Choose the dinner: its usual plate comes along (try night stays on if it was). */
    fun setMeal(c: Context, date: LocalDate, meal: Meal?) {
        val cur = day(c, date)
        save(c, date, meal?.let { DayPlan(it, it.usual, cur.tryNight && it.homeCooked) })
    }

    fun setPart(c: Context, date: LocalDate, part: Part, item: PlateItem?) {
        val cur = day(c, date)
        if (cur.meal == null) return
        save(c, date, cur.copy(parts = if (item == null) cur.parts - part else cur.parts + (part to item)))
    }

    fun setTryNight(c: Context, date: LocalDate, on: Boolean) {
        val cur = day(c, date)
        val meal = cur.meal ?: return
        // Turning it off puts the usual plate back for anything that was new.
        val parts = if (on) cur.parts else cur.parts.filterValues { !it.isNew } + meal.usual.filterKeys { k -> cur.parts[k]?.isNew == true }
        save(c, date, cur.copy(parts = parts, tryNight = on))
    }

    /** Remember "usual plate" changes for next time this dinner is picked. */
    fun saveAsUsual(c: Context, date: LocalDate) {
        val cur = day(c, date)
        val meal = cur.meal ?: return
        updateMeal(c, meal.copy(usual = cur.parts.filterValues { !it.isNew }))
    }

    // ---------- try nights ----------

    fun tries(c: Context): List<TryRecord> {
        val arr = JSONArray(prefs(c).getString("tries2", "[]")!!)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val part = runCatching { Part.valueOf(o.getString("part")) }.getOrNull() ?: return@mapNotNull null
            TryRecord(LocalDate.parse(o.getString("date")), part, PlateItem(o.getString("name"), o.optString("emoji", "✨"), true),
                TryResult.of(o.optString("result")))
        }
    }

    private fun saveTries(c: Context, list: List<TryRecord>) {
        val arr = JSONArray()
        list.takeLast(300).forEach { r ->
            arr.put(JSONObject().put("date", r.date.toString()).put("part", r.part.name)
                .put("name", r.item.name).put("emoji", r.item.emoji).put("result", r.result?.code ?: ""))
        }
        prefs(c).edit().putString("tries2", arr.toString()).apply()
    }

    /** Keep one unanswered record per new food on the plate for that night. */
    private fun syncTryRecords(c: Context, date: LocalDate, plan: DayPlan?) {
        val keep = tries(c).filter { it.date != date || it.result != null }
        val fresh = plan?.newItems.orEmpty().map { (part, item) -> TryRecord(date, part, item, null) }
            .filter { f -> keep.none { it.date == date && it.item.name == f.item.name } }
        saveTries(c, keep + fresh)
    }

    fun recordResult(c: Context, rec: TryRecord, result: TryResult) {
        saveTries(c, tries(c).map { if (it.date == rec.date && it.item.name == rec.item.name) it.copy(result = result) else it })
    }

    /** New foods from a past try night that still need 👎 / 🙂 / ⭐. */
    fun pendingFeedback(c: Context, today: LocalDate): List<TryRecord> =
        tries(c).filter { it.result == null && it.date < today && it.date >= today.minusDays(10) }

    fun wins(c: Context): List<TryRecord> =
        tries(c).filter { it.result == TryResult.LOVED || it.result == TryResult.TRIED }.distinctBy { it.item.name }.reversed()

    /** New-food ideas for a column: skips recent nopes, things they already love, and anything tried in the last two weeks. */
    fun suggestions(c: Context, part: Part, date: LocalDate, page: Int = 0): List<PlateItem> {
        val history = tries(c)
        val blocked = history.filter {
            (it.result == TryResult.NOPE && it.date > date.minusDays(60)) || it.result == TryResult.LOVED ||
                (it.date > date.minusDays(14) && it.date != date)
        }.map { it.item.name }.toSet()
        val all = PlateFoods.new[part].orEmpty()
        val pool = all.filter { it.name !in blocked }.ifEmpty { all }
        return pool.shuffled(Random(date.toEpochDay() * 31 + part.ordinal + page * 7919L)).map { it.copy(isNew = true) }
    }

    // ---------- quick fills ----------

    /** Copy last week's dinners (usual plates; try nights get fresh new foods). */
    fun repeatLastWeek(c: Context, start: LocalDate) {
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            val last = day(c, d.minusDays(7))
            val meal = last.meal ?: continue
            setMeal(c, d, meal)
            if (last.tryNight) makeTryNight(c, d)
        }
    }

    /** Turn a night into a try night with one gentle new fruit or veg on the side. */
    fun makeTryNight(c: Context, date: LocalDate) {
        setTryNight(c, date, true)
        val cur = day(c, date)
        if (cur.newItems.isEmpty()) {
            val target = if (cur.parts[Part.PRODUCE] == null) Part.PRODUCE else Part.EXTRA
            suggestions(c, target, date).firstOrNull()?.let { setPart(c, date, target, it) }
        }
    }

    /** Fill empty nights: no repeats in the week if possible, not last week's same night, one try night midweek. */
    fun fillWeek(c: Context, start: LocalDate) {
        val bank = bank(c)
        if (bank.isEmpty()) return
        val rnd = Random(start.toEpochDay())
        val days = (0 until 7).map { start.plusDays(it.toLong()) }
        val used = days.mapNotNull { day(c, it).meal?.id }.toMutableSet()
        val hasOut = days.any { day(c, it).meal?.key == "out" }
        for (d in days) {
            if (day(c, d).meal != null) continue
            val lastWeek = day(c, d.minusDays(7)).meal?.id
            // Eating out once a week is the family habit: leave Friday/Saturday for it if not planned yet.
            val pick = if (!hasOut && d.dayOfWeek.value == 6 && "out" !in used) bank.firstOrNull { it.key == "out" } else null
            val choice = pick ?: run {
                val choices = bank.filter { it.id !in used && it.id != lastWeek && it.key != "out" }
                    .ifEmpty { bank.filter { it.id != lastWeek && it.key != "out" } }.ifEmpty { bank }
                choices[rnd.nextInt(choices.size)]
            }
            used += choice.id
            setMeal(c, d, choice)
        }
        if (days.none { day(c, it).tryNight }) {
            val tryDay = days.filter { day(c, it).meal?.homeCooked == true }.minByOrNull { abs(it.dayOfWeek.value - 3) } ?: return
            makeTryNight(c, tryDay)
        }
    }
}
