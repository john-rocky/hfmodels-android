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
 * e.g. adb shell am start -n io.github.johnrocky.hfmodels.samples.voice/.MainActivity --ez autoload true --ez autolisten true
 */
class MainActivity : ComponentActivity() {
    private val vm: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        handle(intent)
    }

    private fun handle(i: Intent) {
        val say = i.getStringExtra("say")
        val listen = i.getBooleanExtra("autolisten", false)
        i.getStringExtra("record")?.let { vm.record(it) }
        Log.i(VoiceViewModel.TAG, "extras autoload=${i.getBooleanExtra("autoload", false)} autolisten=$listen say=${say != null} record=${i.getStringExtra("record")}")
        when {
            say != null -> vm.load { vm.say(say) }
            listen -> vm.load { vm.listen() }
            i.getBooleanExtra("autoload", false) -> vm.load()
        }
    }

    private companion object {
        val PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}
