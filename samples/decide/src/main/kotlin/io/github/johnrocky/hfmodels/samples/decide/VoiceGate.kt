package io.github.johnrocky.hfmodels.samples.decide

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The platform recognizer, restarted after every result so it keeps listening. Each final
 * utterance goes to [onUtterance]; partial text to [onPartial]. On-device ASR from the catalog can
 * replace this later; the gate does not care where the text comes from.
 */
class VoiceGate(private val context: Context, private val onPartial: (String) -> Unit, private val onUtterance: (String) -> Unit, private val onStatus: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var language = "en-US"
    private var wanted = false

    fun start(languageTag: String) {
        language = languageTag
        wanted = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onStatus("no speech recognition service on this device"); return }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        listen()
    }

    fun stop() {
        wanted = false
        recognizer?.cancel()
        onStatus("stopped")
    }

    fun destroy() { wanted = false; recognizer?.destroy(); recognizer = null }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
        onStatus("listening ($language)")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            // 6 = no speech, 7 = no match: keep listening; anything else is reported and we retry once the user presses Listen.
            if (wanted && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) listen()
            else if (wanted) { onStatus("recognizer error $error; press Listen again"); wanted = false }
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) onUtterance(text)
            if (wanted) listen()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onPartial(it) }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
