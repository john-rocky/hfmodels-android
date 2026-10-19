package io.github.johnrocky.hfmodels.samples.translate

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.johnrocky.hfmodels.BackendKind
import java.io.File

/**
 * The translator's screen. Launch extras, for a scripted take or a check in a debuggable build (a release build ignores
 * them; all optional; a running screen takes them again):
 *   --ez autoload true        load the three models now
 *   --es pair es|fr           the other person's language, as the choice at the top (default es, Spanish; fr, French)
 *   --es play <wav>           this WAV (16 kHz mono 16-bit; a path, or a name in the external files dir) as one press of
 *                             the button of --es dir, through the endpointer in real time (--ez realtime false: at once)
 *   --es dir es-en|en-es|fr-en|en-fr  which button (default: the other person's of the chosen language, es-en "Habla
 *                             español" or fr-en "Parlez français"); a direction of the other language switches to it
 *   --es record <name>        write each turn's sound and events under <external files>/record/<name>/<turn>/
 *   --ef start_rms 0.01       the endpointer's start level (default 0.02, a voice toward the phone; less for a speaker)
 *   --ef floor_rms 0.005      the input gate's RMS floor (below it: "Nothing heard")
 *   --es mt_backend gpu|cpu   where Hy-MT2 runs (default: the GPU after its output check, unless it failed on this phone)
 * e.g. adb shell am start -n io.github.johnrocky.hfmodels.samples.translate/.MainActivity --ez autoload true
 *
 * Scripted mode (any of these extras, in a debuggable build) shows the screen over the keyguard, turns the display on and
 * keeps it on; a normal launch keeps it on only while the models load, a microphone is open or a turn runs. While the
 * screen is not visible, the microphone and any turn in progress stop.
 */
class MainActivity : ComponentActivity() {
    private val vm: TranslateViewModel by viewModels()

    /** Set by a scripted launch for the rest of the activity's life, as the keyguard flags are. */
    private var scriptedMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (scripted(intent)) overKeyguard()
        enableEdgeToEdge()
        // Creates /sdcard/Android/data/<package>/files/, where pushed model files are picked up (README).
        getExternalFilesDir(null)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        setContent {
            val ui by vm.ui.collectAsState()
            val keepOn = scriptedMode || ui.keepsScreenOn
            LaunchedEffect(keepOn) {
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (keepOn) window.addFlags(flag) else window.clearFlags(flag)
            }
            TranslateScreen(ui, onPress = { vm.press(it) }, onLoad = { vm.load() }, onLanguage = { vm.chooseLanguage(it) })
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.visible = true
    }

    override fun onStop() {
        super.onStop()
        vm.visible = false
        vm.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (scripted(intent)) overKeyguard()
        handle(intent)
    }

    /** The script extras are for development: a release build ignores them (any app could send them). */
    private fun debuggable() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun scripted(i: Intent) = debuggable() && SCRIPT_EXTRAS.any { i.hasExtra(it) }

    private fun overKeyguard() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        scriptedMode = true
    }

    private fun handle(i: Intent) {
        if (!debuggable()) return
        i.getStringExtra("mt_backend")?.let { vm.mtPreference = if (it == "cpu") BackendKind.CPU else if (it == "gpu") BackendKind.GPU else null }
        i.getStringExtra("record")?.let { vm.record(it) }
        val start = if (i.hasExtra("start_rms")) i.getFloatExtra("start_rms", 0.02f) else null
        val floor = if (i.hasExtra("floor_rms")) i.getFloatExtra("floor_rms", InputGate.DEFAULT_FLOOR_RMS) else null
        if (start != null || floor != null) vm.levels(start, floor)
        i.getStringExtra("pair")?.let { tag -> Language.of(tag)?.let { vm.chooseLanguage(it) } ?: Log.w(TranslateViewModel.TAG, "unknown pair $tag (es or fr)") }
        val play = i.getStringExtra("play")
        val dir = i.getStringExtra("dir")?.let { Direction.of(it) } ?: vm.ui.value.language.toEnglish
        Log.i(TranslateViewModel.TAG, "extras autoload=${i.getBooleanExtra("autoload", false)} play=$play dir=${dir.tag} record=${i.getStringExtra("record")} mt_backend=${i.getStringExtra("mt_backend")} pair=${vm.ui.value.language.tag}")
        when {
            play != null -> {
                val f = File(play).takeIf { it.isAbsolute } ?: File(getExternalFilesDir(null), play)
                vm.load { vm.play(f, dir, realTime = i.getBooleanExtra("realtime", true)) }
            }
            i.getBooleanExtra("autoload", false) -> vm.load()
        }
    }

    private companion object {
        val SCRIPT_EXTRAS = listOf("autoload", "play", "record", "start_rms", "floor_rms", "mt_backend", "pair")
    }
}
