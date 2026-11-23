package dev.automationse.pokesleeprater.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.automationse.pokesleeprater.engine.Assessment
import dev.automationse.pokesleeprater.engine.CaptureChecklist
import dev.automationse.pokesleeprater.engine.CaptureProgress
import dev.automationse.pokesleeprater.engine.IngredientParser
import dev.automationse.pokesleeprater.engine.IngredientSlot
import dev.automationse.pokesleeprater.engine.Observation
import dev.automationse.pokesleeprater.engine.OcrParser
import dev.automationse.pokesleeprater.engine.PokemonCatalog
import dev.automationse.pokesleeprater.engine.Specialty
import java.util.UUID

/** Transient OCR state; only structured assessments are persisted locally. */
object LiveState {
    var capturing by mutableStateOf(false)
        private set
    var role by mutableStateOf(Specialty.BERRIES)
        private set
    var roleDetected by mutableStateOf(false)
        private set
    var pokemonName by mutableStateOf<String?>(null)
        private set
    var observation by mutableStateOf(Observation())
        private set
    var lastRecognizedText by mutableStateOf("")
        private set
    var report by mutableStateOf(Assessment.score(Observation(), role))
        private set
    var progress by mutableStateOf(CaptureChecklist.evaluate(null, false, Observation()))
        private set
    var status by mutableStateOf("準備完了")
        private set
    var processedFrames by mutableStateOf(0)
        private set
    var lastError by mutableStateOf("")
        private set
    var history by mutableStateOf<List<SavedAssessment>>(emptyList())
        private set
    var waitingForNext by mutableStateOf(false)
        private set

    private var context: Context? = null
    private var sessionId = UUID.randomUUID().toString()
    private var manualRole = false
    private var manualName = false
    private var pendingName: String? = null
    private var pendingNameCount = 0
    private var pendingNature: String? = null
    private var pendingNatureCount = 0
    private var previousName: String? = null
    private var previousObservation = Observation()

    fun initialize(applicationContext: Context) {
        if (context != null) return
        context = applicationContext.applicationContext
        history = AssessmentHistory.load(applicationContext)
    }

    fun updateCaptureState(value: Boolean) {
        capturing = value
        if (value) {
            processedFrames = 0
            lastError = ""
            status = "画面共有中・ポケモン画面を待っています"
        } else {
            persistCurrent()
            status = if (lastError.isNotBlank()) "エラーで停止" else "停止中"
        }
    }

    private fun refreshAssessment() {
        report = Assessment.score(observation, role)
        progress = CaptureChecklist.evaluate(
            pokemonName, roleDetected || manualRole, observation,
        )
        persistCurrent()
    }

    fun chooseRole(value: Specialty) {
        role = value
        roleDetected = false
        manualRole = true
        refreshAssessment()
    }

    /** Manual correction is useful when a Pokémon has a custom nickname. */
    fun correctPokemonName(value: String) {
        val name = value.trim().take(32)
        if (name.isBlank()) return
        pokemonName = name
        manualName = true
        pendingName = null
        pendingNameCount = 0
        refreshAssessment()
    }

    fun correctIngredient(level: Int, value: String) {
        if (level !in IngredientParser.ingredientLevels) return
        val name = IngredientParser.validatedName(value)
        if (name == null) {
            status = "食材名を認識できません。ゲーム内の正式名称を入力してください"
            return
        }
        val items = observation.ingredients.filterNot { it.level == level } +
            IngredientSlot(level, name)
        observation = observation.copy(
            ingredients = items.sortedBy { it.level },
            seenAtLeastOnce = true,
        )
        refreshAssessment()
        updateReadingStatus()
    }

    private fun resetCurrent() {
        pokemonName = null
        observation = Observation()
        role = Specialty.BERRIES
        roleDetected = false
        manualRole = false
        manualName = false
        pendingName = null
        pendingNameCount = 0
        pendingNature = null
        pendingNatureCount = 0
        lastRecognizedText = ""
        sessionId = UUID.randomUUID().toString()
        report = Assessment.score(observation, role)
        progress = CaptureChecklist.evaluate(null, false, observation)
    }

    fun saveAndNext() {
        persistCurrent()
        previousName = pokemonName
        previousObservation = observation
        resetCurrent()
        waitingForNext = true
        lastError = ""
        status = "保存済み。次のポケモンの名前・性格画面を表示してください"
    }

    /** Manually proceed if two individuals are indistinguishable from visible text. */
    fun startAnyway() {
        waitingForNext = false
        previousName = null
        previousObservation = Observation()
        pendingName = null
        pendingNameCount = 0
        pendingNature = null
        pendingNatureCount = 0
        status = "新しい個体を読み取り中"
    }

    fun reset() = saveAndNext()

    fun lastFinished(): SavedAssessment? =
        history.firstOrNull { it.id != sessionId }

    private fun persistCurrent() {
        val score = report.score ?: return
        val record = SavedAssessment(
            id = sessionId,
            pokemonName = pokemonName ?: "名前未判定",
            role = role,
            nature = observation.nature,
            slots = observation.slots,
            score = score,
            grade = report.grade,
            savedAt = System.currentTimeMillis(),
            ingredients = observation.ingredients,
        )
        val old = history.firstOrNull { it.id == sessionId }
        if (old != null &&
            old.pokemonName == record.pokemonName &&
            old.role == record.role &&
            old.nature == record.nature &&
            old.slots == record.slots &&
            old.ingredients == record.ingredients &&
            old.score == record.score
        ) return
        history = (listOf(record) + history.filterNot { it.id == sessionId }).take(100)
        context?.let { AssessmentHistory.save(it, history) }
    }

    private fun switchToRecognizedPokemon(name: String?) {
        persistCurrent()
        resetCurrent()
        pokemonName = name
        previousName = null
        previousObservation = Observation()
        waitingForNext = false
        refreshAssessment()
    }

    private fun confirmCandidate(name: String?, nature: String?, isName: Boolean): Boolean {
        if (isName) {
            if (name != pendingName) {
                pendingName = name
                pendingNameCount = 1
            } else pendingNameCount++
            return pendingNameCount >= 2
        }
        if (nature != pendingNature) {
            pendingNature = nature
            pendingNatureCount = 1
        } else pendingNatureCount++
        return pendingNatureCount >= 2
    }

    private fun updateReadingStatus() {
        status = if (progress.complete) {
            "評価完了（暫定）・5項目を取得済み"
        } else {
            "${pokemonName ?: "名前未判定"}：${progress.summary} / ${progress.guidance}"
        }
    }

    fun onRecognizedText(raw: String) {
        processedFrames++
        lastRecognizedText = raw.take(1500)
        if (raw.isBlank()) {
            status = "映像取得中：文字を認識できません"
            return
        }

        val detectedName = PokemonCatalog.detect(raw)
        val parsed = OcrParser.parse(raw)
        val detectedRole = PokemonCatalog.detectSpecialty(raw)

        // "Save and next" must not immediately pick up the previous screen.
        // Never use merely newly visible skills/ingredients as a different-identity signal:
        // they routinely appear as the user scrolls the SAME Pokémon.
        if (waitingForNext) {
            val differentName = detectedName != null &&
                previousName != null && detectedName != previousName
            val differentNature = parsed.nature != null &&
                previousObservation.nature != null &&
                parsed.nature != previousObservation.nature
            val confirmed = when {
                differentName -> confirmCandidate(detectedName, null, true)
                differentNature -> confirmCandidate(null, parsed.nature, false)
                else -> {
                    pendingName = null
                    pendingNameCount = 0
                    pendingNature = null
                    pendingNatureCount = 0
                    false
                }
            }
            if (!confirmed) {
                status = "次の個体を待機中（同種・同条件の場合は手動で再開）"
                return
            }
            waitingForNext = false
            pendingName = null
            pendingNameCount = 0
            pendingNature = null
            pendingNatureCount = 0
        }

        // Don't carry a previous individual's nature/skills/ingredients to the next.
        // Require the identity change to be stable across 2 OCR frames.
        val changedSpecies = !manualName && pokemonName != null &&
            detectedName != null && detectedName != pokemonName
        val changedNature = observation.nature != null && parsed.nature != null &&
            parsed.nature != observation.nature
        if (changedSpecies || changedNature) {
            val confirmed = if (changedSpecies) confirmCandidate(detectedName, null, true)
                else confirmCandidate(null, parsed.nature, false)
            if (!confirmed) {
                status = "別の個体の可能性を確認中"
                return
            }
            val newName = if (changedSpecies) detectedName else pokemonName
            switchToRecognizedPokemon(newName)
        } else {
            pendingNature = null
            pendingNatureCount = 0
            if (detectedName == pokemonName) {
                pendingName = null
                pendingNameCount = 0
            }
        }

        if (!manualName && pokemonName == null && detectedName != null) {
            // The first Pokémon can accumulate stats before its name is confirmed.
            if (confirmCandidate(detectedName, null, true)) {
                pokemonName = detectedName
                pendingName = null
                pendingNameCount = 0
            }
        }

        if (!manualRole && detectedRole != null) {
            role = detectedRole
            roleDetected = true
        }

        if (parsed.seenAtLeastOnce) {
            observation = observation.merge(parsed)
        }
        refreshAssessment()
        updateReadingStatus()
    }

    fun onError(reason: String = "画面取得またはOCRでエラーが発生しました") {
        lastError = reason.take(300)
        status = "エラー：$lastError"
    }
}
