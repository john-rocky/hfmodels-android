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
import androidx.compose.runtime.LaunchedEffect
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
// The other person's language: their button, their language's choice and the translation into it in large type.
private val OTHER = Color(0xFFF5B83D)
private val RED = Color(0xFFF85149)
private val LIVE = Color(0xFFE5484D)

/**
 * The one screen, top to bottom: the other person's language (Spanish or French), what the app does, the status, the turn
 * (its translation first: into their language in large type for them to read, or the English that is said; then what
 * was heard), the seconds from the end of speech with their parts, the three models with where they run, the speech
 * model's license, and the two buttons. Only the turn scrolls ([cards]), so a finished turn shows its translation, the
 * seconds and the models together; a new turn starts at the top. The small lines set their own line height (the theme's
 * is 24 sp whatever the size).
 */
@Composable
fun TranslateScreen(ui: TranslateUi, onPress: (Direction) -> Unit, onLoad: () -> Unit, onLanguage: (Language) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = CARD, primary = ACCENT)) {
        Column(Modifier.fillMaxSize().background(BG).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("ON-DEVICE · LiteRT · NETWORK: ${ui.network.ifEmpty { "?" }.uppercase()}", color = DIM, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.6.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("They speak", color = DIM, fontSize = 13.sp)
                for (l in Language.entries) LanguageChoice(l, ui, onLanguage)
            }
            Text(intro(ui.language), color = FG, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
            Text(ui.status, color = DIM, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
            val scroll = rememberScrollState()
            // A new turn starts at the top of the scrolling part, where its translation comes.
            LaunchedEffect(ui.busy) { if (ui.busy) scroll.scrollTo(0) }
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val d = ui.turn
                if (d != null) for (card in ui.cards()) when (card) {
                    TurnCard.TRANSLATION -> Card {
                        Label(d.target)
                        if (d.spoken) Text(ui.translation, color = ACCENT, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                        else Text(ui.translation, color = OTHER, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                    }
                    TurnCard.HEARD -> Card {
                        Label("Heard · ${d.heard}")
                        Text(ui.heard, color = FG, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                }
                ui.error?.let { Text(it, color = RED, fontSize = 13.sp, lineHeight = 18.sp) }
            }
            if (ui.result.isNotEmpty()) {
                Text(ui.result, color = ACCENT, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(ui.breakdown, color = DIM, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
            }
            Column(Modifier.padding(top = 8.dp)) {
                for (m in ui.models) Text(m.text, color = DIM, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
                Text("Speech recognition: Qwen3-ASR-1.7B (Qwen), Apache-2.0", color = DIM, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                if (ui.ready) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (dir in ui.language.directions) LanguageButton(dir, ui, onPress, Modifier.weight(1f))
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
        colors = ButtonDefaults.buttonColors(containerColor = if (listening || running) LIVE else if (d.spoken) OTHER else ACCENT, contentColor = BG),
    ) { Text(label, fontSize = if (listening) 14.sp else 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

/** One language of the choice at the top: filled when chosen; not while a microphone is open or a turn runs. */
@Composable
private fun LanguageChoice(l: Language, ui: TranslateUi, onLanguage: (Language) -> Unit) {
    val chosen = ui.language == l
    Button(
        onClick = { onLanguage(l) },
        enabled = ui.listening == null && !ui.busy,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (chosen) OTHER else CARD, contentColor = if (chosen) BG else FG),
    ) { Text(l.label, fontSize = 15.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** A card's first line: what its text is, in which language. */
@Composable
private fun Label(text: String) {
    Text(text, color = DIM, fontSize = 12.sp, lineHeight = 16.sp)
}
