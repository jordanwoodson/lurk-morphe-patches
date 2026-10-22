package io.github.jordanwoodson.extension.shared.twitch;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Bounded Twitch I/O. A caller can leave even when a platform provider ignores interruption. */
@SuppressWarnings("deprecation") // URL(String) remains available on the pinned Android runtime.
final class TwitchRequest {
    /** The Android app's own Client-ID, the only identity anonymous Lurk requests to Twitch send. */
    static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    static final int MAX_BYTES = 1024 * 1024;
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "lurk-twitch-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadLocal<Operation> CURRENT = new ThreadLocal<>();

    interface Task<T> { T run() throws Exception; }

    private static final class Operation {
        final long deadline;
        final BooleanSupplier active;
        volatile boolean cancelled;
        Operation(long deadline, BooleanSupplier active) { this.deadline = deadline; this.active = active; }
        boolean live() { return !cancelled && now() < deadline && active.getAsBoolean(); }
    }

    static long now() { return SystemClock.elapsedRealtime(); }

    static boolean active() {
        Operation operation = CURRENT.get();
        return !Thread.currentThread().isInterrupted() && (operation == null || operation.live());
    }

    static void check() throws IOException {
        if (!active()) throw new IOException("Twitch request cancelled or expired");
    }

    static <T> T run(long deadline, BooleanSupplier active, Task<T> task) {
        if (CURRENT.get() != null) {
            if (!active() || !active.getAsBoolean() || now() >= deadline) return null;
            try { T result = task.run(); return active() && active.getAsBoolean() && now() < deadline ? result : null; }
            catch (Exception e) { return null; }
        }
        Operation operation = new Operation(deadline, active);
        if (!operation.live()) return null;
        FutureTask<T> future = new FutureTask<>(() -> {
            CURRENT.set(operation);
            try { check(); T result = task.run(); check(); return result; }
            finally { CURRENT.remove(); }
        });
        try {
            IO.execute(future);
            while (operation.live()) {
                try {
                    T result = future.get(Math.min(50, Math.max(1, deadline - now())), TimeUnit.MILLISECONDS);
                    return operation.live() ? result : null;
                }
                catch (TimeoutException ignored) { }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Includes a full queue: the caller uses its existing fallback.
        } finally {
            operation.cancelled = true;
            future.cancel(true);
            IO.remove(future);
        }
        return null;
    }

    static String http(String url, String json, String clientId, long deadline, BooleanSupplier enabled) {
        return run(deadline, enabled, () -> {
            HttpURLConnection connection = null;
            try {
                check();
                connection = (HttpURLConnection) new URL(url).openConnection();
                int timeout = (int) Math.max(1, Math.min(1000, deadline - now()));
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                // Redirects would escape both the checked request chain and its privacy boundary.
                connection.setInstanceFollowRedirects(false);
                if (json != null) {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Client-ID", clientId);
                    connection.setRequestProperty("Content-Type", "application/json");
                    check();
                    try (OutputStream out = connection.getOutputStream()) {
                        check();
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                }
                check();
                if (connection.getResponseCode() != 200) return null;
                check();
                if (connection.getContentLengthLong() > MAX_BYTES) return null;
                try (InputStream in = connection.getInputStream()) {
                    return new String(read(in), StandardCharsets.UTF_8);
                }
            } finally {
                // Cleanup stays on the bounded worker: disconnect itself can wait on a blocked read.
                if (connection != null) connection.disconnect();
            }
        });
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        while (true) {
            check();
            int count = in.read(chunk);
            check();
            if (count < 0) return out.toByteArray();
            if (count > MAX_BYTES - out.size()) throw new IOException("Twitch response too large");
            out.write(chunk, 0, count);
        }
    }

    private TwitchRequest() { }
}
