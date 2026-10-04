package io.github.johnrocky.hfmodels.samples.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BG = Color(0xFF0B0F14)
private val CARD = Color(0xFF141B24)
private val FG = Color(0xFFF2F4F7)
private val DIM = Color(0xFF8A97A6)
private val ACCENT = Color(0xFF22D3EE)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)
private val LIVE = Color(0xFFE5484D)

/**
 * The one screen, top to bottom: the status, what was heard, the tool calls with the phone's answers, the reply as
 * it is said, the time from the end of speech to the first sound, the microphone button, and Android's next alarm.
 */
@Composable
fun VoiceScreen(ui: VoiceUi, onMic: () -> Unit, onLoad: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = CARD, primary = ACCENT)) {
        Column(Modifier.fillMaxSize().background(BG).safeDrawingPadding().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Text("ON-DEVICE · LiteRT · NETWORK: ${ui.network.ifEmpty { "?" }.uppercase()}", color = DIM, fontSize = 11.sp, letterSpacing = 1.6.sp)
            Text(ui.status, color = FG, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.heard.isNotEmpty()) Card {
                    Text("Heard", color = DIM, fontSize = 12.sp)
                    Text(ui.heard, color = FG, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                if (ui.tools.isNotEmpty()) Card {
                    for (t in ui.tools) {
                        Text("${t.icon} ${t.call}", color = DIM, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text(t.result, color = if (t.result.startsWith("Error")) RED else GREEN, fontSize = 16.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                if (ui.reply.isNotEmpty()) Card {
                    Text("Reply", color = DIM, fontSize = 12.sp)
                    Text(ui.reply, color = FG, fontSize = 20.sp)
                    ui.modelReply?.let { Text("The model said: $it", color = DIM, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
                ui.error?.let { Text(it, color = RED, fontSize = 13.sp) }
            }
            if (ui.replyIn.isNotEmpty()) {
                Text(ui.replyIn, color = ACCENT, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 10.dp))
                Text(ui.breakdown, color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                if (ui.ready || ui.listening) {
                    Button(
                        onClick = onMic, shape = CircleShape, modifier = Modifier.size(96.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (ui.listening) LIVE else ACCENT, contentColor = BG),
                    ) { Text(if (ui.listening) "Stop" else "🎤", fontSize = if (ui.listening) 18.sp else 34.sp) }
                } else {
                    Button(onClick = onLoad, enabled = !ui.loading, shape = RoundedCornerShape(24.dp)) { Text(if (ui.loading) "Loading…" else "Load the models") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.phoneState.lineSequence().firstOrNull().orEmpty(), color = DIM, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}
