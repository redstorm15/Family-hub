package com.foster.kitchencalendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.random.Random

/** A dinner in the meal bank. [key] links the family's staples to their gentle try-ideas. */
data class Meal(val id: String, val name: String, val emoji: String, val key: String? = null) {
    val homeCooked get() = key != "takeout"
}

/** One small new thing on the side: a veggie, a dip or sauce, or a side. */
data class TryFood(val name: String, val emoji: String)

data class DayPlan(val meal: Meal?, val tryFood: TryFood?)

enum class TryResult(val code: String, val emoji: String, val label: String) {
    NOPE("nope", "👎", "Nope"), TRIED("tried", "🙂", "Tried it"), LOVED("loved", "⭐", "Loved it");
    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } }
}

data class TryRecord(val date: LocalDate, val mealName: String, val food: TryFood, val result: TryResult?)

/**
 * Gentle try-ideas: the main dish stays exactly as the kids know it, and one new thing goes
 * on the side. Nothing spicy, nothing heavily seasoned, nothing far from home base.
 */
object TryIdeas {
    private fun t(emoji: String, name: String) = TryFood(name, emoji)

    private val byMeal = mapOf(
        "nuggets" to listOf(
            t("🍯", "honey mustard to dip"), t("🍠", "sweet potato fries"), t("🫛", "edamame"),
            t("🌽", "corn on the cob"), t("🥦", "broccoli trees with melted cheese"), t("🥕", "butter carrot coins"),
            t("🥒", "cucumber slices"), t("🍖", "BBQ sauce to dip"),
        ),
        "pasta" to listOf(
            t("🥖", "garlic bread"), t("🧀", "parmesan sprinkle"), t("🫛", "green beans"),
            t("🥒", "zucchini coins"), t("🥗", "a few Caesar salad bites"), t("🍅", "cherry tomatoes"),
            t("🥦", "a little broccoli"),
        ),
        "tacos" to listOf(
            t("🫙", "mild salsa on the side"), t("🥑", "guacamole"), t("🫘", "black beans"),
            t("🌽", "corn"), t("🥭", "mango cubes"), t("🧀", "queso to dip chips"), t("🍶", "sour cream"),
            t("🫑", "sweet pepper strips"),
        ),
        "pizza" to listOf(
            t("🍕", "one slice with pepperoni"), t("🥒", "cucumber chips with ranch"), t("🍅", "cherry tomatoes"),
            t("🥗", "a small salad with ranch"), t("🍍", "pineapple chunks"), t("🧄", "garlic knots"),
            t("🫒", "one olive on a slice"),
        ),
        "takeout" to listOf(
            t("🥟", "one dumpling"), t("🍚", "a spoon of fried rice"), t("🫛", "edamame"),
            t("🍜", "a few lo mein noodles"), t("🫓", "a piece of plain naan"), t("🥢", "one new thing from the menu"),
        ),
        "mac" to listOf(
            t("🟢", "peas stirred in"), t("🥦", "tiny broccoli bits"), t("🍞", "crunchy bread-crumb top"),
            t("🥓", "ham cubes"), t("🥕", "carrot sticks"), t("🍎", "apple slices"),
        ),
        "hotdogs" to listOf(
            t("🌽", "corn"), t("🥒", "a pickle spear"), t("🥔", "potato wedges"),
            t("🍉", "watermelon"), t("🥗", "a bite of coleslaw"), t("🍎", "apple slices"), t("🟡", "mustard to dip"),
        ),
    )

    val general = listOf(
        t("🥦", "steamed broccoli"), t("🥕", "carrots with ranch"), t("🫑", "sweet pepper strips"),
        t("🥒", "cucumber slices"), t("🫛", "snap peas"), t("🫐", "berries"), t("🌽", "corn"),
        t("🫛", "green beans"), t("🍠", "roasted sweet potato"), t("🥑", "avocado slices"), t("🍐", "pear slices"),
    )

    fun forMeal(meal: Meal?): List<TryFood> = (byMeal[meal?.key].orEmpty() + general).distinctBy { it.name }
}

/** Meal bank, the dinner plan, and how each try night went. Stored as JSON in prefs. */
object MealStore {
    private fun prefs(c: Context) = c.getSharedPreferences("meal_plan", Context.MODE_PRIVATE)

    private val staples = listOf(
        Meal("nuggets", "Chicken nuggets, peas & rice", "🍗", "nuggets"),
        Meal("pasta", "Pasta & meatballs", "🍝", "pasta"),
        Meal("tacos", "Tacos", "🌮", "tacos"),
        Meal("pizza", "Pizza", "🍕", "pizza"),
        Meal("takeout", "Order takeout", "🥡", "takeout"),
        Meal("mac", "Mac & cheese", "🧀", "mac"),
        Meal("hotdogs", "Hot dogs & beans", "🌭", "hotdogs"),
    )

    // ---------- meal bank ----------

    fun bank(c: Context): List<Meal> {
        val raw = prefs(c).getString("bank", null) ?: return staples.also { saveBank(c, it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Meal(o.getString("id"), o.getString("name"), o.optString("emoji", "🍽️"), o.optString("key").ifBlank { null })
        }
    }

    private fun saveBank(c: Context, meals: List<Meal>) {
        val arr = JSONArray()
        meals.forEach { m -> arr.put(JSONObject().put("id", m.id).put("name", m.name).put("emoji", m.emoji).put("key", m.key ?: "")) }
        prefs(c).edit().putString("bank", arr.toString()).apply()
    }

    fun addMeal(c: Context, name: String, emoji: String): Meal {
        val m = Meal("m" + System.currentTimeMillis(), name.trim(), emoji)
        saveBank(c, bank(c) + m)
        return m
    }

    fun removeMeal(c: Context, id: String) = saveBank(c, bank(c).filter { it.id != id })

    fun backup(c: Context): String = prefs(c).getString("backup", "plain chicken & rice")!!
    fun setBackup(c: Context, text: String) = prefs(c).edit().putString("backup", text.trim()).apply()

    // ---------- plan ----------

    private fun planJson(c: Context) = JSONObject(prefs(c).getString("plan", "{}")!!)

    fun day(c: Context, date: LocalDate): DayPlan {
        val o = planJson(c).optJSONObject(date.toString()) ?: return DayPlan(null, null)
        val meal = bank(c).firstOrNull { it.id == o.optString("meal") }
        val t = o.optJSONObject("try")?.let { TryFood(it.getString("name"), it.optString("emoji", "✨")) }
        return DayPlan(meal, t)
    }

    fun week(c: Context, start: LocalDate): List<Pair<LocalDate, DayPlan>> =
        (0 until 7).map { start.plusDays(it.toLong()) }.map { it to day(c, it) }

    fun setMeal(c: Context, date: LocalDate, meal: Meal?) {
        val plan = planJson(c)
        val o = plan.optJSONObject(date.toString()) ?: JSONObject()
        if (meal == null) { plan.remove(date.toString()); setTryRecord(c, date, null, null) }
        else plan.put(date.toString(), o.put("meal", meal.id))
        savePlan(c, plan)
    }

    fun setTry(c: Context, date: LocalDate, food: TryFood?) {
        val plan = planJson(c)
        val o = plan.optJSONObject(date.toString()) ?: return
        if (food == null) o.remove("try") else o.put("try", JSONObject().put("name", food.name).put("emoji", food.emoji))
        plan.put(date.toString(), o)
        savePlan(c, plan)
        setTryRecord(c, date, day(c, date).meal?.name, food)
    }

    private fun savePlan(c: Context, plan: JSONObject) {
        // Keep three weeks back (for "repeat last week"), drop the rest.
        val cutoff = LocalDate.now().minusDays(21).toString()
        plan.keys().asSequence().toList().filter { it < cutoff }.forEach { plan.remove(it) }
        prefs(c).edit().putString("plan", plan.toString()).apply()
    }

    // ---------- try nights ----------

    fun tries(c: Context): List<TryRecord> {
        val arr = JSONArray(prefs(c).getString("tries", "[]")!!)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TryRecord(LocalDate.parse(o.getString("date")), o.optString("meal"),
                TryFood(o.getString("name"), o.optString("emoji", "✨")), TryResult.of(o.optString("result")))
        }
    }

    private fun saveTries(c: Context, list: List<TryRecord>) {
        val arr = JSONArray()
        list.takeLast(200).forEach { r ->
            arr.put(JSONObject().put("date", r.date.toString()).put("meal", r.mealName)
                .put("name", r.food.name).put("emoji", r.food.emoji).put("result", r.result?.code ?: ""))
        }
        prefs(c).edit().putString("tries", arr.toString()).apply()
    }

    private fun setTryRecord(c: Context, date: LocalDate, mealName: String?, food: TryFood?) {
        val others = tries(c).filter { !(it.date == date && it.result == null) }
        saveTries(c, if (food == null) others else others + TryRecord(date, mealName.orEmpty(), food, null))
    }

    fun recordResult(c: Context, rec: TryRecord, result: TryResult) {
        saveTries(c, tries(c).map { if (it.date == rec.date && it.food.name == rec.food.name) it.copy(result = result) else it })
    }

    /** A try night that has passed and still needs a 👎 / 🙂 / ⭐. */
    fun pendingFeedback(c: Context, today: LocalDate): TryRecord? =
        tries(c).lastOrNull { it.result == null && it.date < today && it.date >= today.minusDays(10) }

    fun wins(c: Context): List<TryRecord> =
        tries(c).filter { it.result == TryResult.LOVED || it.result == TryResult.TRIED }.distinctBy { it.food.name }.reversed()

    /**
     * Ideas for [meal], best first: skips anything they said nope to in the last 60 days,
     * anything tried in the last two weeks, and anything they already love (that's not new anymore).
     */
    fun suggestions(c: Context, meal: Meal?, date: LocalDate, skip: Int = 0): List<TryFood> {
        val history = tries(c)
        val blocked = history.filter {
            (it.result == TryResult.NOPE && it.date > date.minusDays(60)) ||
                it.result == TryResult.LOVED ||
                (it.date > date.minusDays(14) && it.date != date)
        }.map { it.food.name }.toSet()
        val pool = TryIdeas.forMeal(meal).filter { it.name !in blocked }.ifEmpty { TryIdeas.forMeal(meal) }
        val shuffled = pool.shuffled(Random(date.toEpochDay() + skip * 7919L))
        return shuffled
    }

    // ---------- quick fills ----------

    /** Copy last week's dinners onto the next 7 days (try nights get a fresh idea). */
    fun repeatLastWeek(c: Context, start: LocalDate) {
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            val last = day(c, d.minusDays(7))
            last.meal?.let { m ->
                setMeal(c, d, m)
                if (last.tryFood != null) setTry(c, d, suggestions(c, m, d).firstOrNull())
            }
        }
    }

    /**
     * Fill empty nights from the bank: no repeats inside the week when there are enough meals,
     * avoid what was on the same night last week, and make one home-cooked night a try night.
     */
    fun fillWeek(c: Context, start: LocalDate) {
        val bank = bank(c)
        if (bank.isEmpty()) return
        val rnd = Random(start.toEpochDay())
        val days = (0 until 7).map { start.plusDays(it.toLong()) }
        val used = days.mapNotNull { day(c, it).meal?.id }.toMutableSet()
        for (d in days) {
            if (day(c, d).meal != null) continue
            val lastWeek = day(c, d.minusDays(7)).meal?.id
            val choices = bank.filter { it.id !in used && it.id != lastWeek }.ifEmpty { bank.filter { it.id != lastWeek } }.ifEmpty { bank }
            val pick = choices[rnd.nextInt(choices.size)]
            used += pick.id
            setMeal(c, d, pick)
        }
        if (days.none { day(c, it).tryFood != null }) {
            val candidates = days.filter { day(c, it).meal?.homeCooked == true }
            // Prefer a weeknight in the middle of the week for the try night.
            val tryDay = candidates.minByOrNull { kotlin.math.abs(it.dayOfWeek.value - 3) } ?: return
            setTry(c, tryDay, suggestions(c, day(c, tryDay).meal, tryDay).firstOrNull())
        }
    }
}
