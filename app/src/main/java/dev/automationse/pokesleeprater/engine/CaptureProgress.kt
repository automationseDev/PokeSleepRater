package dev.automationse.pokesleeprater.engine

/**
 * Reading completion is separate from the provisional rating score.
 * Every required field must be explicitly recognized or manually confirmed.
 */
data class CaptureProgress(
    val recognizedGroups: Int,
    val totalGroups: Int,
    val missing: List<String>,
) {
    val complete: Boolean get() = missing.isEmpty()
    val summary: String get() = if (complete) "評価完了（暫定）" else
        "読み取り中 ${recognizedGroups}/${totalGroups}"
    val guidance: String get() = if (complete) {
        "5項目取得済み。数値評価は暫定です"
    } else {
        "未取得：${missing.joinToString("・")}"
    }
}

object CaptureChecklist {
    val skillLevels = listOf(10, 25, 50, 70, 80)

    fun evaluate(
        pokemonName: String?,
        roleConfirmed: Boolean,
        observation: Observation,
    ): CaptureProgress {
        val missing = mutableListOf<String>()
        if (pokemonName.isNullOrBlank()) missing += "ポケモン名"
        if (!roleConfirmed) missing += "得意タイプ"
        if (observation.nature == null) missing += "性格"

        val knownSkills = observation.slots.map { it.level }
            .filter { it in skillLevels }.toSet()
        if (!knownSkills.containsAll(skillLevels)) {
            missing += "サブスキル(Lv.${(skillLevels - knownSkills).joinToString("/")})"
        }

        val knownIngredients = observation.ingredients.map { it.level }
            .filter { it in IngredientParser.ingredientLevels }.toSet()
        if (!knownIngredients.containsAll(IngredientParser.ingredientLevels)) {
            missing += "食材(Lv.${(IngredientParser.ingredientLevels - knownIngredients).joinToString("/")})"
        }

        return CaptureProgress(5 - missing.size, 5, missing)
    }
}
