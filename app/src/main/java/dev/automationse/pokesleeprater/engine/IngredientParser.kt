package dev.automationse.pokesleeprater.engine

/** OCR of the three ingredient choices shown in Pokémon Sleep: Lv.1, 30, and 60. */
object IngredientParser {
    // Names verified against the current game ingredient list (2026-11).
    // See https://wikiwiki.jp/poke_sleep/食材/食材の一覧
    val ingredientNames: Set<String> = setOf(
        "ふといながねぎ", "あじわいキノコ", "とくせんエッグ", "ほっこりポテト",
        "とくせんリンゴ", "げきからハーブ", "マメミート", "モーモーミルク",
        "あまいミツ", "ピュアなオイル", "あったかジンジャー", "あんみんトマト",
        "リラックスカカオ", "おいしいシッポ", "ワカクサ大豆", "ワカクサコーン",
        "めざましコーヒー", "ずっしりカボチャ", "つやつやアボカド", "ひとくちナッツ",
    )

    val ingredientLevels = listOf(1, 30, 60)
    private val levelMarker = Regex("""(?i)(?:Lv[.．:]?\s*|レベル\s*)(1|30|60)(?!\d)""")

    /** Never assign a level without an explicit Lv label and unambiguous food name. */
    fun parse(text: String): List<IngredientSlot> {
        val lines = text.lines().map { it.replace(" ", "").replace("　", "").trim() }
            .filter { it.isNotEmpty() }
        val results = mutableListOf<IngredientSlot>()
        lines.forEachIndexed { index, line ->
            val marker = levelMarker.find(line) ?: return@forEachIndexed
            val level = marker.groupValues[1].toInt()
            val nearby = buildList {
                add(line)
                // OCR sometimes puts the level in the previous row and the icon caption below.
                for (offset in 1..2) {
                    val next = lines.getOrNull(index + offset) ?: break
                    if (levelMarker.containsMatchIn(next)) break
                    add(next)
                }
            }
            val matches = ingredientNames.filter { name -> nearby.any { it.contains(name) } }
            if (matches.size == 1) results += IngredientSlot(level, matches.single())
        }
        // Inconsistent reads of a slot should never yield two contradictory values.
        return results.groupBy { it.level }.values.mapNotNull { items ->
            items.distinctBy { it.name }.singleOrNull()
        }.sortedBy { it.level }
    }

    fun validatedName(input: String): String? {
        val name = input.replace(" ", "").replace("　", "").trim()
        return name.takeIf { it in ingredientNames }
    }
}
