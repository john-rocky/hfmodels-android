package io.github.johnrocky.hfmodels.litertlm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Makes a native object with [create] on [dispatcher] and hands it to the caller, or closes it with [close] when the
 * caller was cancelled while [create] ran (a native call is not interrupted, so it finishes either way).
 *
 * The nesting is the point. `withContext(dispatcher + NonCancellable) { create() }` still loses the object: the return
 * to the caller's dispatcher is cancellable, so a caller cancelled meanwhile gets a CancellationException and the
 * result is dropped, made and never closed (NativeHandoffTest). Here the return happens inside NonCancellable, and the
 * caller's cancellation is checked after it, with the object in hand.
 */
internal suspend fun <T> handOver(dispatcher: CoroutineDispatcher, create: () -> T, close: suspend (T) -> Unit): T {
    currentCoroutineContext().ensureActive()
    val made = withContext(NonCancellable) { withContext(dispatcher) { create() } }
    try {
        currentCoroutineContext().ensureActive()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { close(made) }
        throw e
    }
    return made
}
