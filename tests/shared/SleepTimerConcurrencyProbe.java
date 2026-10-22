import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.jordanwoodson.extension.shared.SleepTimer;

/** Same bounded two-thread fixture on the host JVM and ART; only the pause boundary is a double. */
public final class SleepTimerConcurrencyProbe {
    public static final class Player {
        volatile boolean playing = true;
        int pauses;
        Runnable beforePause;

        public void pause() {
            if (beforePause != null) beforePause.run();
            // Matches the injected IVS entry callback: stopping reenters the timer on this thread.
            SleepTimer.onStop(this);
            pauses++;
            playing = false;
        }
    }

    /** Waiting for a lock: a monitor (BLOCKED) or the bounded dispatch gate (TIMED_WAITING). */
    private static boolean held(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.BLOCKED || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    public static void run(String boundary) throws Exception {
        if (boundary.startsWith("queued-")) {
            queued(boundary);
            return;
        }
        if (boundary.startsWith("lockfree-")) {
            lockFree(boundary);
            return;
        }
        Player player = new Player();
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread resumer = new Thread(() -> {
            try {
                attempting.countDown();
                SleepTimer.onPlay(player);
                player.playing = true;
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                resumed.countDown();
            }
        }, "sleep-test-resume");
        resumer.setDaemon(true);
        player.beforePause = () -> {
            resumer.start();
            await(attempting, "resumer did not reach onPlay");
            // Stop at the precise contention boundary, with no timing-based sleep. The pause callback
            // can now proceed if onPlay is held at the dispatch gate (a monitor or a timed lock wait); the
            // broken dispatch lets the resume finish first.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (resumed.getCount() != 0 && !held(resumer)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("resumer made no bounded progress");
                Thread.yield();
            }
        };

        AtomicReference<SleepTimer.Expiry> captured = new AtomicReference<>();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        if (boundary.equals("initial-expiry")) {
            SleepTimer.setExpiryAction(expiry -> player.pause());
        } else if (boundary.equals("initial-runnable")) {
            SleepTimer.setPauseAction(player::pause);
        } else if (boundary.equals("fallback")) {
            SleepTimer.setExpiryAction(captured::set);
        } else if (boundary.equals("executor")) {
            SleepTimer.setExpiryAction(expiry -> queued.set(guard(player::pause)));
        } else {
            throw new AssertionError(boundary);
        }
        SleepTimer.onPlay(player);
        fireTimer();
        if (boundary.equals("fallback")) captured.get().pauseLeftovers("concurrency fixture");
        if (boundary.equals("executor")) queued.get().run();
        await(resumed, "resume blocked after pause completed (possible lock inversion)");
        resumer.join(3000);
        if (resumer.isAlive()) throw new AssertionError("resumer failed to terminate");
        if (failure.get() != null) throw new AssertionError("resumer failed", failure.get());
        if (player.pauses != 1 || !player.playing) {
            throw new AssertionError(boundary + ": old expiry paused newer resumed playback; pauses="
                    + player.pauses + " playing=" + player.playing);
        }
        System.out.println("PASS concurrency: " + boundary + " pause precedes resume; reentrant stop completed");
    }

    /**
     * The app's pause action waits for another thread that reports a player stopping (lockfree-stop) or
     * starting (lockfree-play), as a pause path that hands the work to a player thread would. The timer must
     * not hold its bookkeeping lock while app code runs: a stop goes straight through, and a play waits at
     * the dispatch gate for its bound at most, so the action always finishes.
     */
    private static void lockFree(String boundary) throws Exception {
        Player player = new Player();
        Player other = new Player();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] waited = {-1};
        SleepTimer.setPauseAction(() -> {
            CountDownLatch done = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    if (boundary.equals("lockfree-stop")) {
                        SleepTimer.onStop(player);
                        SleepTimer.started();
                    } else if (boundary.equals("lockfree-play")) {
                        SleepTimer.onPlay(other);
                    } else {
                        throw new AssertionError(boundary);
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }, "sleep-test-player-thread");
            worker.setDaemon(true);
            long start = System.nanoTime();
            worker.start();
            await(done, boundary + ": the pause action deadlocked waiting on a player thread");
            waited[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            player.pauses++;
            player.playing = false;
        });
        SleepTimer.onPlay(player);
        fireTimer();
        if (failure.get() != null) throw new AssertionError(boundary + ": player thread failed", failure.get());
        if (player.pauses != 1 || waited[0] < 0) throw new AssertionError(boundary + ": the pause action did not run");
        if (boundary.equals("lockfree-stop")) {
            // A stop never waits at the gate (one second), only for the bookkeeping, which is free.
            if (waited[0] >= 900) throw new AssertionError("a stop waited " + waited[0] + " ms for the pause");
            if (SleepTimer.started().contains(player)) throw new AssertionError("the stop was not recorded");
        } else if (!SleepTimer.started().contains(other)) {
            throw new AssertionError("the play that waited at the gate was not recorded");
        }
        System.out.println("PASS lock-free pause: " + boundary + " finished after " + waited[0] + " ms");
    }

    private static void fireTimer() throws Exception {
        // Arm/fire the actual production timer without waiting for a minute or copying its decisions.
        Field generation = SleepTimer.class.getDeclaredField("generation");
        generation.setAccessible(true);
        Method arm = SleepTimer.class.getDeclaredMethod("arm", long.class);
        arm.setAccessible(true);
        arm.invoke(null, generation.getLong(null));
        Field timer = SleepTimer.class.getDeclaredField("scheduledSleep");
        timer.setAccessible(true);
        ((Runnable) timer.get(null)).run();
    }

    private static Runnable guard(Runnable operation) {
        try {
            return (Runnable) SleepTimer.class.getMethod("guardPauseOperation", Runnable.class).invoke(null, operation);
        } catch (NoSuchMethodException legacy) {
            // The old injected executor sends the original Runnable unchanged. This makes stale-queue
            // cases fail on the baseline by their observable pause result, rather than a missing symbol.
            return operation;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void queued(String boundary) throws Exception {
        Player old = new Player();
        Player fresh = new Player();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<SleepTimer.Expiry> expiry = new AtomicReference<>();
        Runnable pause = () -> { old.pause(); if (boundary.equals("queued-new")) fresh.pause(); };
        SleepTimer.setExpiryAction(current -> {
            expiry.set(current);
            // Boundary double of Kick's injected ExecutorService.execute argument, queued until later.
            queued.set(guard(pause));
            if (boundary.equals("queued-failure")) throw new IllegalStateException("dispatch failed");
        });
        SleepTimer.onPlay(old);
        try {
            fireTimer();
            if (boundary.equals("queued-failure")) throw new AssertionError("expected dispatch failure");
        } catch (IllegalStateException e) {
            if (!boundary.equals("queued-failure")) throw e;
        }
        if (boundary.equals("queued-new")) SleepTimer.onPlay(fresh);
        if (boundary.equals("queued-resume")) SleepTimer.onPlay(old);
        if (boundary.equals("queued-disabled")) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, String> values = (java.util.Map<String, String>) Class.forName(
                        "io.github.jordanwoodson.extension.shared.settings.Settings").getField("values").get(null);
                values.put("sleep_timer", "0");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        if (boundary.equals("queued-stock") || boundary.equals("queued-failure")) {
            Runnable stock = guard(pause);
            if (stock != pause) throw new AssertionError("dispatch context leaked into a stock operation");
            SleepTimer.onPlay(old);
            stock.run();
            if (old.pauses != 1) throw new AssertionError("ordinary pause was suppressed");
        } else {
            queued.get().run();
            if (boundary.equals("queued-new") || boundary.equals("queued-resume") || boundary.equals("queued-disabled")) {
                if (old.pauses != 0 || fresh.pauses != 0) throw new AssertionError(boundary + ": stale queued pause ran");
                expiry.get().pauseLeftovers("queued fixture");
                int expectedOld = boundary.equals("queued-new") ? 1 : 0;
                if (old.pauses != expectedOld || fresh.pauses != 0) throw new AssertionError("eligible missed-old semantics changed");
            } else if (old.pauses != 1) throw new AssertionError("eligible queued pause did not run");
        }
        System.out.println("PASS queued expiry: " + boundary);
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, e);
        }
    }
}
