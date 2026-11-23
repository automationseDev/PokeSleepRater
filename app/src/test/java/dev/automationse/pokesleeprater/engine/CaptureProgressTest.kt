package dev.automationse.pokesleeprater.engine

import org.junit.Assert.*
import org.junit.Test

class CaptureProgressTest {
    @Test fun parsesIngredientsAcrossScrollingScreens() {
        val first = OcrParser.parse("食材\nLv.1\nあまいミツ×2")
        val second = OcrParser.parse("食材\nLv.30\nげきからハーブ×3")
        val third = OcrParser.parse("Lv.60\nひとくちナッツ×4")
        val result = first.merge(second).merge(third)
        assertEquals(
            listOf(
                IngredientSlot(1, "あまいミツ"),
                IngredientSlot(30, "げきからハーブ"),
                IngredientSlot(60, "ひとくちナッツ"),
            ),
            result.ingredients,
        )
    }

    @Test fun doesNotInventIngredientLevelFromGenericText() {
        assertTrue(IngredientParser.parse("食材\nあまいミツ\nげきからハーブ").isEmpty())
        assertTrue(IngredientParser.parse("食材\nLv.30\nあまいミツ\nげきからハーブ").isEmpty())
        assertTrue(IngredientParser.parse("Lv.50\nひとくちナッツ").isEmpty())
        assertNull(IngredientParser.validatedName("未知の食材"))
    }

    @Test fun mergePreservesOriginalSlotsAcrossMultipleFrames() {
        val first = Observation(
            nature = "まじめ",
            slots = listOf(SkillSlot(10, "おてつだいボーナス")),
            seenAtLeastOnce = true,
        )
        val second = Observation(
            slots = listOf(SkillSlot(25, "おてつだいスピードM")),
            seenAtLeastOnce = true,
            ingredients = listOf(IngredientSlot(30, "あまいミツ")),
        )
        val combined = first.merge(second)
        assertEquals("まじめ", combined.nature)
        assertEquals(2, combined.slots.size)
        assertEquals(listOf(IngredientSlot(30, "あまいミツ")), combined.ingredients)
    }

    @Test fun onlyCompleteWithAllFiveKnownGroups() {
        val completeObservation = Observation(
            nature = "まじめ",
            slots = listOf(
                SkillSlot(10, "おてつだいボーナス"),
                SkillSlot(25, "おてつだいスピードM"),
                SkillSlot(50, "食材確率アップS"),
                SkillSlot(70, "スキル確率アップM"),
                SkillSlot(80, "最大所持数アップL"),
            ),
            ingredients = listOf(
                IngredientSlot(1, "あまいミツ"),
                IngredientSlot(30, "げきからハーブ"),
                IngredientSlot(60, "ひとくちナッツ"),
            ),
            seenAtLeastOnce = true,
        )
        val incomplete = CaptureChecklist.evaluate("モンメン", true,
            completeObservation.copy(ingredients = completeObservation.ingredients.take(2)))
        assertFalse(incomplete.complete)
        assertTrue(incomplete.guidance.contains("Lv.60"))
        val complete = CaptureChecklist.evaluate("モンメン", true, completeObservation)
        assertTrue(complete.complete)
        assertEquals("評価完了（暫定）", complete.summary)
        assertEquals(5, complete.recognizedGroups)
        assertFalse(CaptureChecklist.evaluate(null, true, completeObservation).complete)
        assertFalse(CaptureChecklist.evaluate("モンメン", false, completeObservation).complete)
    }

    @Test fun newSubskillLevelsAreRecognized() {
        val parsed = OcrParser.parse("Lv.70\nスキル確率アップM\nLv.80\n最大所持数アップL")
        assertTrue(parsed.slots.contains(SkillSlot(70, "スキル確率アップM")))
        assertTrue(parsed.slots.contains(SkillSlot(80, "最大所持数アップL")))
    }
}
