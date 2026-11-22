package dev.automationse.pokesleeprater.engine

import org.junit.Assert.*
import org.junit.Test

class AssessmentTest {
    @Test fun berryFindingSHelpsBerryRole() {
        val baseline = Assessment.score(Observation(nature = "まじめ"), Specialty.BERRIES)
        val boosted = Assessment.score(
            Observation(nature = "まじめ", slots = listOf(SkillSlot(10, "きのみの数S"))),
            Specialty.BERRIES,
        )
        assertTrue(boosted.score!! > baseline.score!!)
    }

    @Test fun parserFindsLevelAndNature() {
        val result = OcrParser.parse("サブスキル\nLv.10\nおてつだいボーナス\n性格\nゆうかん")
        assertEquals("ゆうかん", result.nature)
        assertEquals(SkillSlot(10, "おてつだいボーナス"), result.slots.single())
    }
    @Test fun noInventedRating() {
        assertNull(Assessment.score(Observation(), Specialty.SKILLS).score)
    }
}
