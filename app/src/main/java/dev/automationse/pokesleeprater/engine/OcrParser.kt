package dev.automationse.pokesleeprater.engine

/** OCRで確実に認識した項目のみを取り込む。 */
object OcrParser {
    private val natures = listOf(
        "さみしがり", "ゆうかん", "いじっぱり", "やんちゃ", "ずぶとい",
        "すなお", "のんき", "わんぱく", "のうてんき", "おくびょう",
        "せっかち", "まじめ", "ようき", "むじゃき", "ひかえめ",
        "おっとり", "れいせい", "てれや", "うっかりや", "おだやか",
        "おとなしい", "なまいき", "しんちょう", "きまぐれ", "がんばりや",
    )
    private val skills = listOf(
        "きのみの数S", "おてつだいボーナス", "おてつだいスピードM",
        "おてつだいスピードS", "食材確率アップM", "食材確率アップS",
        "最大所持数アップL", "最大所持数アップM", "最大所持数アップS",
        "スキル確率アップM", "スキル確率アップS", "スキルレベルアップM",
        "スキルレベルアップS", "睡眠EXPボーナス", "リサーチEXPボーナス",
        "ゆめのかけらボーナス", "げんき回復ボーナス",
    )
    private val level = Regex("(?:Lv|LV|lv|レベル)[.．\\s]*(10|25|50|70|80)(?!\\d)")
    fun parse(text: String): Observation {
        val lines = text.lines().map { it.replace(" ", "").replace("　", "") }.filter { it.isNotBlank() }
        val nature = natures.firstOrNull { n -> lines.any { it.contains(n) } }
        val slots = mutableListOf<SkillSlot>()
        lines.forEachIndexed { index, line ->
            // A level on the same OCR line is more reliable than an adjacent slot's label.
            val candidates = listOfNotNull(line, lines.getOrNull(index - 1), lines.getOrNull(index + 1))
            for (skill in skills) {
                if (!line.contains(skill)) continue
                val parsedLevel = candidates.firstNotNullOfOrNull {
                    level.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                } ?: 0
                slots += SkillSlot(parsedLevel, skill)
            }
        }
        val ingredients = IngredientParser.parse(text)
        return Observation(
            nature = nature,
            slots = slots.distinct(),
            seenAtLeastOnce = nature != null || slots.isNotEmpty() || ingredients.isNotEmpty(),
            ingredients = ingredients,
        )
    }
}
