package io.github.johnrocky.hfmodels.samples.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone button's rule ([stillRunning]): a second tap on Stop, while the first stop is still unwinding, must not
 * start a second listen. The loop's side, that it takes a new listen while the stopped one's turn unwinds, is in
 * VoiceLoopTest (hfmodels-voice).
 */
class ListenJobTest {
    @Test fun aStoppedListenStillRunsUntilItHasUnwound() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val unwind = CompletableDeferred<Unit>()
        // A listen whose turn takes until [unwind] to stop (the runtime confirms the model's stop, the conversation closes).
        val listen = launch(Dispatchers.Default) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { unwind.await() }
            }
        }
        try {
            started.await()
            assertTrue(stillRunning(listen))
            listen.cancel()
            // What the second tap sees: no longer active (the old check started a second listen here), still running.
            assertFalse(listen.isActive)
            assertTrue(stillRunning(listen))
        } finally {
            unwind.complete(Unit)
        }
        listen.join()
        assertFalse(stillRunning(listen))
        assertFalse(stillRunning(null))
    }
}
