package io.github.johnrocky.hfmodels.samples.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * The voice loop's screen. Launch extras, for a scripted take (all optional; a running screen takes them again):
 *   --ez autoload true     load the three models now
 *   --ez autolisten true   open the microphone once they are loaded
 *   --es say "<text>"      one turn from this text once they are loaded (no microphone)
 *   --es record <name>     write each turn's sound and events under <external files>/record/<name>/<turn>/
 *   --ef start_rms 0.01    the endpointer's start level (default 0.02, a voice toward the phone; less for a speaker)
 * e.g. adb shell am start -n io.github.johnrocky.hfmodels.samples.voice/.MainActivity --ez autoload true --ez autolisten true
 *
 * Scripted mode (any of these extras present, whatever its value) shows the screen over the keyguard and turns the
 * display on; a normal launch does not. Under the keyguard the activity is not visible: Android drops the Clock app's
 * SET_ALARM activity start from the app (BAL_BLOCK, result code 102; Galaxy S26, 2026-10-03) while the alarm tool
 * still reports the alarm set, and the hidden activity's process runs in the background cpuset.
 */
class MainActivity : ComponentActivity() {
    private val vm: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (scripted(intent)) overKeyguard()
        enableEdgeToEdge()
        // Keep the screen on through a turn: a locked phone moves a hidden activity's process to the background cpuset
        // (little cores), where phone-agent's model produced one token a second.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        setContent {
            val ui by vm.ui.collectAsState()
            VoiceScreen(ui, onMic = { vm.toggleListen() }, onLoad = { vm.load() })
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (scripted(intent)) overKeyguard()
        handle(intent)
    }

    private fun scripted(i: Intent) = SCRIPT_EXTRAS.any { i.hasExtra(it) }

    private fun overKeyguard() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    private fun handle(i: Intent) {
        val say = i.getStringExtra("say")
        val listen = i.getBooleanExtra("autolisten", false)
        i.getStringExtra("record")?.let { vm.record(it) }
        if (i.hasExtra("start_rms")) vm.startRms(i.getFloatExtra("start_rms", 0.02f))
        Log.i(VoiceViewModel.TAG, "extras autoload=${i.getBooleanExtra("autoload", false)} autolisten=$listen say=${say != null} record=${i.getStringExtra("record")}")
        when {
            say != null -> vm.load { vm.say(say) }
            listen -> vm.load { vm.listen() }
            i.getBooleanExtra("autoload", false) -> vm.load()
        }
    }

    private companion object {
        val PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val SCRIPT_EXTRAS = listOf("autoload", "autolisten", "say", "record", "start_rms")
    }
}
