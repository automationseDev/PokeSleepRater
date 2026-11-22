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
    private val screenShare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = result.data
        if (result.resultCode == Activity.RESULT_OK && token != null) {
            val start = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, token)
            }
            startForegroundService(start)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        overlayGranted = { Settings.canDrawOverlays(this) },
                    )
                }
            }
        }
    }
}
@Composable
private fun Home(
    start: () -> Unit, stop: () -> Unit,
    overlayPermission: () -> Unit, overlayGranted: () -> Boolean,
) {
    var showRaw by remember { mutableStateOf(false) }
    val isOverlayGranted = overlayGranted()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("PokeSleep Rater", style = MaterialTheme.typography.headlineMedium)
        Text("Pokémon Sleepの画面を約2秒間隔でOCR解析し、画面上に暫定評価を重ねて表示します。")
        Text("状態：${LiveState.status}", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("得意タイプ（試作版では手動指定）", style = MaterialTheme.typography.titleMedium)
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
        Text("開始後、Androidの共有確認画面でPokémon Sleepだけを選んでください。")
        HorizontalDivider()
        Text("現在の評価", style = MaterialTheme.typography.titleMedium)
        Text(LiveState.report.oneLine, style = MaterialTheme.typography.headlineSmall)
        Text(LiveState.report.progress)
        LiveState.report.details.take(8).forEach { Text("・$it") }
        OutlinedButton(onClick = { LiveState.reset() }) { Text("別の個体を評価するためにクリア") }
        HorizontalDivider()
        Text("診断", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { showRaw = !showRaw }) {
            Text(if (showRaw) "読み取りテキストを隠す" else "読み取りテキストを表示")
        }
        if (showRaw) Text(LiveState.lastRecognizedText.ifEmpty { "まだOCR結果はありません" })
        Text("※独自の暫定評価です。種族固有性能・食材構成・ミント・Lv75/100の精密評価には未対応です。")
        Text("※キャプチャ画像・OCRテキストは保存・送信しません。個体を切り替える前にクリアしてください。")
    }
}
