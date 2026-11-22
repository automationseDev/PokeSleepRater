package dev.automationse.pokesleeprater.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.automationse.pokesleeprater.engine.Assessment
import dev.automationse.pokesleeprater.engine.Observation
import dev.automationse.pokesleeprater.engine.OcrParser
import dev.automationse.pokesleeprater.engine.Specialty

/** 画面画像・OCRテキストは外部へ送信しない。永続化もしない。 */
object LiveState {
    var capturing by mutableStateOf(false)
        private set
    var role by mutableStateOf(Specialty.BERRIES)
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
    fun updateCaptureState(value: Boolean) {
        capturing = value
        if (value) {
            processedFrames = 0
            lastError = ""
            status = "画面共有を開始しました。OCR結果を待っています"
        } else {
            status = if (lastError.isNotBlank()) "エラーで停止" else "停止中"
        }
    }
    fun chooseRole(value: Specialty) {
        role = value
        report = Assessment.score(observation, role)
    }
    fun reset() {
        observation = Observation()
        lastRecognizedText = ""
        processedFrames = 0
        lastError = ""
        report = Assessment.score(observation, role)
        status = if (capturing) "画面を読み取り中" else "準備完了"
    }
    fun onRecognizedText(raw: String) {
        processedFrames += 1
        lastRecognizedText = raw.take(1500)
        if (raw.isBlank()) {
            status = "映像取得中：文字を認識できません（画面の共有対象を確認）"
            return
        }
        val hint = listOf("サブスキル", "おてつだい", "スキル確率", "きのみ", "食材確率", "性格")
        if (hint.none { raw.contains(it) }) {
            status = "映像取得中：性格・サブスキルが見つかりません"
            return
        }
        val parsed = OcrParser.parse(raw)
        if (parsed.seenAtLeastOnce) {
            observation = observation.merge(parsed)
            report = Assessment.score(observation, role)
            status = "OCR取得済み / 同じ個体の別画面も読み取れます"
        } else {
            status = "ポケモン画面を検知 / 読み取り待ち"
        }
    }
    fun onError(reason: String = "画面取得またはOCRでエラーが発生しました") {
        lastError = reason.take(300)
        status = "エラー：$lastError"
    }
}
