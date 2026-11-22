package dev.automationse.pokesleeprater.ui

import android.content.Context
import dev.automationse.pokesleeprater.engine.SkillSlot
import dev.automationse.pokesleeprater.engine.Specialty
import org.json.JSONArray
import org.json.JSONObject

/** Persist only the extracted assessment, never screenshots or raw OCR text. */
data class SavedAssessment(
    val id: String,
    val pokemonName: String,
    val role: Specialty,
    val nature: String?,
    val slots: List<SkillSlot>,
    val score: Int,
    val grade: String,
    val savedAt: Long,
)

object AssessmentHistory {
    private const val PREFS_NAME = "pokemon_assessment_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100

    fun load(context: Context): List<SavedAssessment> {
        val source = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val records = JSONArray(source)
            buildList {
                for (i in 0 until minOf(records.length(), MAX_ENTRIES)) {
                    val record = records.optJSONObject(i) ?: continue
                    val role = runCatching {
                        Specialty.valueOf(record.getString("role"))
                    }.getOrNull() ?: continue
                    val rawSkills = record.optJSONArray("skills") ?: JSONArray()
                    val skills = buildList {
                        for (index in 0 until rawSkills.length()) {
                            val skill = rawSkills.optJSONObject(index) ?: continue
                            val name = skill.optString("name").take(80)
                            if (name.isNotBlank()) {
                                add(SkillSlot(skill.optInt("level", 0), name))
                            }
                        }
                    }
                    val id = record.optString("id")
                    if (id.isBlank()) continue
                    add(SavedAssessment(
                        id = id,
                        pokemonName = record.optString("pokemon", "未判定"),
                        role = role,
                        nature = record.optString("nature").ifBlank { null },
                        slots = skills,
                        score = record.optInt("score").coerceIn(0, 100),
                        grade = record.optString("grade", "—"),
                        savedAt = record.optLong("savedAt", 0L),
                    ))
                }
            }
        } catch (_: Exception) {
            // Older/malformed records should never crash live capture.
            emptyList()
        }
    }

    fun save(context: Context, records: List<SavedAssessment>) {
        val array = JSONArray()
        for (entry in records.take(MAX_ENTRIES)) {
            val skillArray = JSONArray()
            for (skill in entry.slots) {
                skillArray.put(JSONObject().put("level", skill.level).put("name", skill.name))
            }
            array.put(JSONObject()
                .put("id", entry.id)
                .put("pokemon", entry.pokemonName)
                .put("role", entry.role.name)
                .put("nature", entry.nature ?: "")
                .put("skills", skillArray)
                .put("score", entry.score)
                .put("grade", entry.grade)
                .put("savedAt", entry.savedAt))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
