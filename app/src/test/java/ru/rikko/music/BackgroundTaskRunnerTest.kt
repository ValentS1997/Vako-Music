package ru.rikko.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class BackgroundTaskRunnerTest {
    @Test fun rejectionRestoresEachOwnedBusyStateAndRetryCanRun() {
        var reject = true
        val runner = BackgroundTaskRunner(Executor { command ->
            if (reject) throw RejectedExecutionException()
            command.run()
        })
        for (state in listOf("busy", "downloading", "autoCollectionBusy", "bulkDownloading", "scanning", "likedLoading")) {
            var active = true
            var ran = false
            assertFalse(state, runner.submit({ active = false }) { ran = true })
            assertFalse(state, active)
            assertFalse(state, ran)
            reject = false
            assertTrue(state, runner.submit({ active = false }) { ran = true })
            assertTrue(state, ran)
            reject = true
        }
    }

    @Test fun saturatedExecutorRejectsThenAcceptsRetry() {
        val pool = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        val runner = BackgroundTaskRunner(pool)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        try {
            assertTrue(runner.submit({}) { started.countDown(); release.await(); finished.countDown() })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(runner.submit({}) { finished.countDown() })
            var busy = true
            assertFalse(runner.submit({ busy = false }) { error("Rejected task ran") })
            assertFalse(busy)
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            var retried = false
            assertTrue(runner.submit({}) { retried = true })
            pool.shutdown()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(retried)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
