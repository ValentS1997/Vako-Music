package ru.rikko.music

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Reports rejected work to the owner so UI state can be restored immediately. */
internal class BackgroundTaskRunner(private val executor: Executor) {
    fun submit(onRejected: () -> Unit, task: () -> Unit): Boolean = try {
        executor.execute { if (!Thread.currentThread().isInterrupted) task() }
        true
    } catch (_: RejectedExecutionException) {
        onRejected()
        false
    }
}
