package dev.automationse.pokesleeprater.engine

enum class Specialty(val label: String) {
    BERRIES("きのみ"), INGREDIENTS("食材"), SKILLS("スキル")
}
data class SkillSlot(val level: Int, val name: String)
data class Observation(
    val nature: String? = null,
    val slots: List<SkillSlot> = emptyList(),
    val seenAtLeastOnce: Boolean = false,
) {
    fun merge(other: Observation): Observation = copy(
        nature = other.nature ?: nature,
        slots = (slots + other.slots)
            .distinctBy { if (it.level != 0) "${it.level}" else "?${it.name}" }
            .let { all -> all.filter { it.level != 0 || all.none { known -> known.level != 0 && known.name == it.name } } }
            .sortedBy { if (it.level == 0) 101 else it.level },
        seenAtLeastOnce = seenAtLeastOnce || other.seenAtLeastOnce,
    )
}
data class ScoreReport(
    val score: Int?, val grade: String, val details: List<String>, val progress: String,
) {
    val oneLine: String get() = if (score == null) "判定待ち" else "$grade  ${score}点 / 100（暫定）"
}
object Assessment {
    private val weights = mapOf(
        Specialty.BERRIES to mapOf(
            "きのみの数S" to 35, "おてつだいボーナス" to 21,
            "おてつだいスピードM" to 16, "おてつだいスピードS" to 9,
            "最大所持数アップL" to 2, "スキル確率アップM" to 2,
        ),
        Specialty.INGREDIENTS to mapOf(
            "食材確率アップM" to 25, "食材確率アップS" to 14,
            "おてつだいボーナス" to 19, "おてつだいスピードM" to 13,
            "おてつだいスピードS" to 8, "最大所持数アップL" to 12,
            "最大所持数アップM" to 8, "最大所持数アップS" to 4,
        ),
        Specialty.SKILLS to mapOf(
            "スキル確率アップM" to 27, "スキル確率アップS" to 15,
            "おてつだいボーナス" to 19, "おてつだいスピードM" to 13,
            "おてつだいスピードS" to 8, "スキルレベルアップM" to 9,
            "スキルレベルアップS" to 4,
        ),
    )
    private data class NatureBonus(val help: Int = 0, val food: Int = 0, val skill: Int = 0)
    private val natures = mapOf(
        "さみしがり" to NatureBonus(help = 10), "ゆうかん" to NatureBonus(help = 10),
        "いじっぱり" to NatureBonus(help = 10, food = -12),
        "やんちゃ" to NatureBonus(help = 10, skill = -13),
        "ずぶとい" to NatureBonus(help = -11), "のんき" to NatureBonus(),
        "わんぱく" to NatureBonus(food = -12), "のうてんき" to NatureBonus(skill = -13),
        "おくびょう" to NatureBonus(help = -11), "ようき" to NatureBonus(food = -12),
        "むじゃき" to NatureBonus(skill = -13),
        "ひかえめ" to NatureBonus(help = -11, food = 13),
        "おっとり" to NatureBonus(food = 13), "れいせい" to NatureBonus(food = 13),
        "うっかりや" to NatureBonus(food = 13, skill = -13),
        "おだやか" to NatureBonus(help = -11, skill = 15),
        "おとなしい" to NatureBonus(skill = 15), "なまいき" to NatureBonus(skill = 15),
        "しんちょう" to NatureBonus(food = -12, skill = 15),
        "せっかち" to NatureBonus(), "すなお" to NatureBonus(),
        "がんばりや" to NatureBonus(), "まじめ" to NatureBonus(),
        "てれや" to NatureBonus(), "きまぐれ" to NatureBonus(),
    )
    fun score(observation: Observation, specialty: Specialty): ScoreReport {
        if (observation.nature == null && observation.slots.isEmpty()) {
            return ScoreReport(null, "—", listOf("ポケモンの性格・サブスキル画面を表示してください"), "未取得")
        }
        val remarks = mutableListOf<String>()
        var score = 45.0
        val selected = weights.getValue(specialty)
        for (slot in observation.slots) {
            val impact = selected[slot.name] ?: 0
            if (impact == 0) continue
            val factor = when (slot.level) {
                10 -> 1.0
                25 -> 0.9
                50 -> 0.65
                75 -> 0.35
                100 -> 0.2
                else -> 0.55
            }
            val points = impact * factor
            score += points
            remarks += "${slot.name}（Lv${if (slot.level == 0) "不明" else slot.level}） +${points.toInt()}"
        }
        observation.nature?.let { nature ->
            val effect = natures[nature] ?: NatureBonus()
            val points = effect.help + when (specialty) {
                Specialty.BERRIES -> 0
                Specialty.INGREDIENTS -> effect.food
                Specialty.SKILLS -> effect.skill
            }
            score += points
            remarks += "性格：$nature ${if (points >= 0) "+" else ""}$points"
        }
        val total = score.toInt().coerceIn(0, 100)
        val grade = when {
            total >= 90 -> "S"
            total >= 80 -> "A"
            total >= 70 -> "B"
            total >= 55 -> "C"
            else -> "D"
        }
        val missing = mutableListOf<String>()
        if (observation.nature == null) missing += "性格"
        if (observation.slots.size < 5) missing += "サブスキル(${observation.slots.size}/5)"
        missing += "食材構成・種族固有性能"
        return ScoreReport(
            total, grade, remarks.ifEmpty { listOf("該当する加点スキルは未認識") },
            "暫定評価 / 未反映：${missing.joinToString("、")}",
        )
    }
}
