package dev.automationse.pokesleeprater.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.automationse.pokesleeprater.engine.Assessment
import dev.automationse.pokesleeprater.engine.Observation
import dev.automationse.pokesleeprater.engine.OcrParser
import dev.automationse.pokesleeprater.engine.PokemonCatalog
import dev.automationse.pokesleeprater.engine.Specialty
import java.util.UUID

/**
 * Session-scoped OCR state. Persist only structured, extracted evaluations.
 * Raw OCR text and screenshots remain transient and never leave the device.
 */
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
    private var pendingCount = 0
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

    fun chooseRole(value: Specialty) {
        role = value
        roleDetected = false
        manualRole = true
        report = Assessment.score(observation, role)
        persistCurrent()
    }

    /** Manual correction for nicknamed Pokémon or missed name OCR. */
    fun correctPokemonName(value: String) {
        val name = value.trim().take(32)
        if (name.isBlank()) return
        pokemonName = name
        manualName = true
        pendingName = null
        pendingCount = 0
        persistCurrent()
    }

    private fun resetCurrent() {
        pokemonName = null
        observation = Observation()
        role = Specialty.BERRIES
        roleDetected = false
        manualRole = false
        manualName = false
        pendingName = null
        pendingCount = 0
        lastRecognizedText = ""
        report = Assessment.score(observation, role)
        sessionId = UUID.randomUUID().toString()
    }

    /** Save the finished assessment and wait for a visibly different individual. */
    fun saveAndNext() {
        persistCurrent()
        previousName = pokemonName
        previousObservation = observation
        waitingForNext = true
        resetCurrent()
        lastError = ""
        status = "前の評価を保存しました。次のポケモンを表示してください"
    }

    /** Explicit manual skip when OCR cannot determine that the screen changed. */
    fun startAnyway() {
        waitingForNext = false
        previousName = null
        previousObservation = Observation()
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
        )
        val old = history.firstOrNull { it.id == sessionId }
        if (old != null &&
            old.pokemonName == record.pokemonName &&
            old.role == record.role &&
            old.nature == record.nature &&
            old.slots == record.slots &&
            old.score == record.score
        ) return
        history = (listOf(record) + history.filterNot { it.id == sessionId }).take(100)
        context?.let { AssessmentHistory.save(it, history) }
    }

    private fun switchToRecognizedPokemon(name: String) {
        persistCurrent()
        resetCurrent()
        pokemonName = name
        waitingForNext = false
        previousName = null
        previousObservation = Observation()
        status = "ポケモンを自動検出：$name"
    }

    fun onRecognizedText(raw: String) {
        processedFrames += 1
        lastRecognizedText = raw.take(1500)
        if (raw.isBlank()) {
            status = "映像取得中：文字を認識できません"
            return
        }
        val detected = PokemonCatalog.detect(raw)
        val parsed = OcrParser.parse(raw)
        val detectedRole = PokemonCatalog.detectSpecialty(raw)

        if (waitingForNext) {
            val differentSpecies = detected != null && detected != previousName
            val differentNature = parsed.nature != null &&
                previousObservation.nature != null &&
                parsed.nature != previousObservation.nature
            val differentSkills = parsed.slots.isNotEmpty() &&
                previousObservation.slots.isNotEmpty() &&
                parsed.slots.any { it !in previousObservation.slots }
            if (!differentSpecies && !differentNature && !differentSkills) {
                status = "次の個体の画面を待機中（同種・同条件なら強制再開）"
                return
            }
            waitingForNext = false
        }

        // Two consecutive OCR reads prevent accidental name switching on menus.
        if (!manualName && detected != null && detected != pokemonName) {
            if (detected == pendingName) pendingCount++ else {
                pendingName = detected
                pendingCount = 1
            }
            if (pendingCount < 2 && pokemonName != null) {
                // A possible switch must not mix the next Pokémon's stats into this one.
                status = "次のポケモン名を確認中：$detected"
                return
            }
            // For the very first Pokémon, retain its skills while confirming the name.
            if (pendingCount < 2) {
                status = "ポケモン名を確認中：$detected"
            } else {
                if (pokemonName != null) {
                    switchToRecognizedPokemon(detected)
                } else {
                    pokemonName = detected
                }
                pendingName = null
                pendingCount = 0
            }
        } else if (detected == pokemonName) {
            pendingName = null
            pendingCount = 0
        }

        if (!manualRole && detectedRole != null) {
            if (detectedRole != role) {
                role = detectedRole
                report = Assessment.score(observation, role)
                persistCurrent()
            }
            roleDetected = true
        }

        if (parsed.seenAtLeastOnce) {
            val updated = observation.merge(parsed)
            if (updated != observation || detectedRole != null || pokemonName != null) {
                observation = updated
                report = Assessment.score(observation, role)
                persistCurrent()
            }
            status = "${pokemonName ?: "名前未判定"}：評価取得中（${observation.slots.size}/5スキル）"
        } else {
            status = if (pokemonName == null) "ポケモン名・能力を認識待ち" else
                "$pokemonName：性格・サブスキル画面を表示してください"
        }
    }

    fun onError(reason: String = "画面取得またはOCRでエラーが発生しました") {
        lastError = reason.take(300)
        status = "エラー：$lastError"
    }
}
