package dev.automationse.pokesleeprater

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.automationse.pokesleeprater.capture.ScreenCaptureService
import dev.automationse.pokesleeprater.engine.Specialty
import dev.automationse.pokesleeprater.ui.LiveState

class MainActivity : ComponentActivity() {
    private var overlayEnabled by mutableStateOf(false)
    override fun onResume() {
        super.onResume()
        overlayEnabled = Settings.canDrawOverlays(this)
    }

    private val screenShare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = result.data
        if (result.resultCode == Activity.RESULT_OK && token != null) {
            val start = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, token)
            }
            try {
                startForegroundService(start)
            } catch (e: Exception) {
                LiveState.onError("画面共有サービス起動失敗: ${e.message ?: e.javaClass.simpleName}")
            }
        } else {
            LiveState.onError("画面共有がキャンセルされました")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveState.initialize(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Home(
                        start = {
                            val manager = getSystemService(MediaProjectionManager::class.java)
                            screenShare.launch(manager.createScreenCaptureIntent())
                        },
                        stop = {
                            startService(Intent(this, ScreenCaptureService::class.java).apply {
                                action = ScreenCaptureService.ACTION_STOP
                            })
                        },
                        overlayPermission = {
                            startActivity(Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ))
                        },
                        overlayGranted = overlayEnabled,
                    )
                }
            }
        }
    }
}
@Composable
private fun Home(
    start: () -> Unit, stop: () -> Unit,
    overlayPermission: () -> Unit, overlayGranted: Boolean,
) {
    var showRaw by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    val isOverlayGranted = overlayGranted
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("PokeSleep Rater", style = MaterialTheme.typography.headlineMedium)
        Text("Pokémon Sleepの画面を約2秒間隔でOCR解析し、画面上に暫定評価を重ねて表示します。")
        Text("状態：${LiveState.status}", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("現在のポケモン：${LiveState.pokemonName ?: "自動認識待ち"}", style = MaterialTheme.typography.titleMedium)
        Text("得意タイプ：${LiveState.role.label}（${if (LiveState.roleDetected) "画面から自動認識" else "手動・仮設定"}）")
        Text("名前や得意タイプが画面で読めない場合は手動補正できます。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualName,
                onValueChange = { manualName = it },
                label = { Text("種族名またはニックネーム") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                LiveState.setPokemonName(manualName)
                manualName = ""
            }, enabled = manualName.isNotBlank()) { Text("設定") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Specialty.entries.forEach { type ->
                FilterChip(
                    selected = LiveState.role == type,
                    onClick = { LiveState.chooseRole(type) },
                    label = { Text(type.label) },
                )
            }
        }
        if (!isOverlayGranted) {
            Text("評価をゲーム画面に表示するには『他のアプリの上に表示』を許可してください。")
            OutlinedButton(onClick = overlayPermission) { Text("オーバーレイ権限を設定") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = start, enabled = !LiveState.capturing) { Text("画面解析を開始") }
            OutlinedButton(onClick = stop, enabled = LiveState.capturing) { Text("停止") }
        }
        Text("開始後、Androidの画面共有で「画面全体」を選択し、Pokémon Sleepの個体画面を開いてください。上部のフローティングアイコンをタップすると、評価・保存・次の個体・前回の評価を操作できます。")
        HorizontalDivider()
        Text("現在の評価", style = MaterialTheme.typography.titleMedium)
        Text(LiveState.report.oneLine, style = MaterialTheme.typography.headlineSmall)
        Text(LiveState.report.progress)
        LiveState.report.details.take(8).forEach { Text("・$it") }
        Button(onClick = { LiveState.saveAndNext() }) { Text("保存して次のポケモンへ") }
        if (LiveState.waitingForNext) {
            OutlinedButton(onClick = { LiveState.startAnyway() }) {
                Text("同種・同条件でも評価を再開")
            }
        }
        HorizontalDivider()
        Text("評価履歴（自動保存・最新100件）", style = MaterialTheme.typography.titleMedium)
        if (LiveState.history.isEmpty()) {
            Text("評価結果を読み取ると、ここに保存されます。")
        } else {
            LiveState.history.take(12).forEach { record ->
                Text("${record.pokemonName}  ${record.grade} ${record.score}点  " +
                    "${record.role.label} / ${record.nature ?: "性格未確認"}  " +
                    "スキル${record.slots.size}/5")
            }
        }
        HorizontalDivider()
        Text("診断", style = MaterialTheme.typography.titleSmall)
        Text("処理済みOCRフレーム：${LiveState.processedFrames}")
        if (LiveState.lastError.isNotBlank()) {
            Text("エラー詳細：${LiveState.lastError}", color = MaterialTheme.colorScheme.error)
        }
        if (LiveState.capturing && !isOverlayGranted) {
            Text("画面は読み取れますが、評価をゲーム上に重ねるにはオーバーレイ許可が必要です。")
        }
        TextButton(onClick = { showRaw = !showRaw }) {
            Text(if (showRaw) "読み取りテキストを隠す" else "読み取りテキストを表示")
        }
        if (showRaw) Text(LiveState.lastRecognizedText.ifEmpty { "まだOCR結果はありません" })
        Text("※独自の暫定評価です。種族固有性能・食材構成・ミント・Lv75/100の精密評価には未対応です。")
        Text("※キャプチャ画像・OCRテキストは保存・送信しません。評価履歴（名前・性格・サブスキル・点数のみ）は端末内に保存します。")
        Text("※ニックネームを付けた個体、同じ種族の個体切替は自動識別できない場合があります。その際は「保存して次へ」を使ってください。")
    }
}
