package io.github.johnrocky.hfmodels.samples.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BG = Color(0xFF0B0F14)
private val CARD = Color(0xFF141B24)
private val FG = Color(0xFFF2F4F7)
private val DIM = Color(0xFF8A97A6)
private val ACCENT = Color(0xFF22D3EE)
private val SPANISH = Color(0xFFF5B83D)
private val RED = Color(0xFFF85149)
private val LIVE = Color(0xFFE5484D)

/**
 * The one screen, top to bottom: what the app does, the status, what was heard, the translation (the Spanish in large
 * type, for the other person to read), the seconds from the end of speech with their parts, the three models with where
 * they run, the speech model's license, and the two buttons.
 */
@Composable
fun TranslateScreen(ui: TranslateUi, onPress: (Direction) -> Unit, onLoad: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = CARD, primary = ACCENT)) {
        Column(Modifier.fillMaxSize().background(BG).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("ON-DEVICE · LiteRT · NETWORK: ${ui.network.ifEmpty { "?" }.uppercase()}", color = DIM, fontSize = 11.sp, letterSpacing = 1.6.sp)
            Text("No network, no account: Spanish speech becomes an English voice, English becomes Spanish text.", color = FG, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Text(ui.status, color = DIM, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val d = ui.turn
                if (d != null && ui.heard.isNotEmpty()) Card {
                    Text("Heard · ${d.heard}", color = DIM, fontSize = 12.sp)
                    Text(ui.heard, color = FG, fontSize = 19.sp)
                }
                if (d != null && ui.translation.isNotEmpty()) Card {
                    Text(d.target, color = DIM, fontSize = 12.sp)
                    if (d.spoken) Text(ui.translation, color = ACCENT, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    else Text(ui.translation, color = SPANISH, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
                }
                ui.error?.let { Text(it, color = RED, fontSize = 13.sp) }
            }
            if (ui.result.isNotEmpty()) {
                Text(ui.result, color = ACCENT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
                Text(ui.breakdown, color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Column(Modifier.padding(top = 10.dp)) {
                for (m in ui.models) Text(m.text, color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("Speech recognition: NVIDIA parakeet-tdt-0.6b-v3, CC-BY-4.0", color = DIM, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                if (ui.ready) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (dir in Direction.entries) LanguageButton(dir, ui, onPress, Modifier.weight(1f))
                    }
                } else {
                    Button(onClick = onLoad, enabled = !ui.loading, shape = RoundedCornerShape(24.dp)) { Text(if (ui.loading) "Loading…" else "Load the models") }
                }
            }
        }
    }
}

@Composable
private fun LanguageButton(d: Direction, ui: TranslateUi, onPress: (Direction) -> Unit, modifier: Modifier) {
    val listening = ui.listening == d
    val running = ui.busy && ui.turn == d
    val label = when {
        listening -> "Listening… tap to finish"
        running -> "Stop"
        else -> d.button
    }
    Button(
        onClick = { onPress(d) },
        enabled = listening || running || (ui.listening == null && !ui.busy),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.height(76.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (listening || running) LIVE else if (d.spoken) SPANISH else ACCENT, contentColor = BG),
    ) { Text(label, fontSize = if (listening) 14.sp else 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}
