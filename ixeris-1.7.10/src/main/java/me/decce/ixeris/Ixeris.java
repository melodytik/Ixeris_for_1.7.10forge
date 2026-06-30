package me.decce.ixeris;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Core state holder for Ixeris 1.7.10.
 *
 * Manages the thread-split architecture: the original main thread becomes the
 * "event polling thread" (calls Display.processMessages), while a new "render
 * thread" runs the Minecraft game loop with the transferred GL context.
 */
public class Ixeris {

    public static final String MOD_ID = "ixeris";
    public static final String MOD_NAME = "Ixeris";
    public static final String VERSION = "1.7.10-1.0.0";

    /** Set to true once the mod has successfully initialised. */
    public static volatile boolean active = false;

    /** The original main thread — now the event polling thread. */
    public static volatile Thread mainThread;

    /** The new thread that runs the Minecraft game loop + rendering. */
    public static volatile Thread renderThread;

    /** Latch: the main thread has released the GL context. */
    public static volatile boolean contextReleased = false;

    /** Signals both threads to shut down. */
    public static volatile boolean shouldExit = false;

    /** Volatile mirror of Display.isCloseRequested() — readable cross-thread. */
    public static volatile boolean closeRequested = false;

    /** Volatile mirror of Display.wasResized() — readable cross-thread. */
    public static volatile boolean resized = false;
    public static volatile int newWidth = 0;
    public static volatile int newHeight = 0;

    private static volatile boolean threadSplitAttempted = false;

    private static final AtomicBoolean pollingLock = new AtomicBoolean(false);

    /**
     * Called from the ASM-injected head of Minecraft.run().
     *
     * On the first invocation (main thread):
     *  1. Release the GL context so the render thread can claim it.
     *  2. Spawn the render thread (which calls Minecraft.run() again).
     *  3. Enter the polling loop on this thread.
     *  4. Return true so the caller returns immediately.
     *
     * On subsequent invocations (render thread): returns false so the
     * original game-loop code executes normally.
     *
     * @param minecraft the Minecraft instance (passed as Object to avoid a
     *                  compile-time dependency on obfuscated Minecraft classes)
     * @return true if the main thread should return from run(), false otherwise
     */
    public static boolean beginThreadSplit(final Object minecraft) {
        if (threadSplitAttempted) {
            // Already attempted — if we're on the render thread, proceed normally
            return false;
        }
        threadSplitAttempted = true;

        try {
            mainThread = Thread.currentThread();

            IxerisConfig config = IxerisConfig.load();

            if (!config.enabled) {
                // Mod disabled — run the game normally
                return false;
            }

            // ---- Release GL context from main thread ----
            try {
                org.lwjgl.opengl.Display.releaseContext();
            } catch (Exception e) {
                System.err.println("[Ixeris] Failed to release GL context, aborting thread split: " + e);
                e.printStackTrace();
                return false;
            }
            contextReleased = true;

            // ---- Spawn render thread ----
            renderThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    // Wait until the main thread has released the context
                    while (!contextReleased) {
                        Thread.yield();
                    }
                    try {
                        org.lwjgl.opengl.Display.makeCurrent();
                    } catch (Exception e) {
                        System.err.println("[Ixeris] Render thread failed to make GL context current: " + e);
                        e.printStackTrace();
                        shouldExit = true;
                        return;
                    }

                    active = true;
                    System.out.println("[Ixeris] Render thread started, GL context transferred.");

                    // Call minecraft.run() via reflection (avoids compile-time dependency)
                    try {
                        minecraft.getClass().getMethod("run").invoke(minecraft);
                    } catch (Exception e) {
                        System.err.println("[Ixeris] Error running game loop on render thread: " + e);
                        e.printStackTrace();
                    }

                    shouldExit = true;
                }
            }, "Ixeris Render Thread");
            renderThread.setPriority(Thread.MAX_PRIORITY);
            renderThread.start();

            // ---- Run polling loop on main thread ----
            runPollingLoop(config);

            return true; // Tell Minecraft.run() to return on main thread

        } catch (Throwable t) {
            System.err.println("[Ixeris] Thread split failed, falling back to normal operation: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * The event polling loop. Runs on the original main thread.
     * Continuously calls Display.processMessages() and drains input events
     * into thread-safe queues for the render thread to consume.
     *
     * Timing strategy (configurable via spinMode):
     *   "yield" — spin-wait with Thread.yield() pauses, giving the
     *             render thread CPU time. Default & recommended.
     *   "park"  — always use LockSupport.parkNanos(), lower CPU.
     *   "busy"  — pure busy-loop, maximum precision but may starve
     *             the render thread on machines with few cores.
     */
    private static void runPollingLoop(IxerisConfig config) {
        final long intervalNanos = config.pollingIntervalUs * 1000L; // µs → ns
        final String mode = config.spinMode;
        System.out.println("[Ixeris] Event polling thread started (interval: "
                + config.pollingIntervalUs + " µs, mode: " + mode + ").");

        // Wait for the render thread to claim the GL context before we begin polling
        while (!active && !shouldExit) {
            Thread.yield();
        }

        while (!shouldExit) {
            long loopStart = System.nanoTime();

            try {
                // Drain LWJGL event queues into our thread-safe buffers
                if (pollingLock.compareAndSet(false, true)) {
                    try {
                        org.lwjgl.opengl.Display.processMessages();
                        InputManager.drainEvents();

                        if (org.lwjgl.opengl.Display.isCloseRequested()) {
                            closeRequested = true;
                        }
                        if (org.lwjgl.opengl.Display.wasResized()) {
                            resized = true;
                            newWidth = org.lwjgl.opengl.Display.getWidth();
                            newHeight = org.lwjgl.opengl.Display.getHeight();
                        }
                    } finally {
                        pollingLock.set(false);
                    }
                }

                // ---- Timing / wait ----
                if (intervalNanos > 0) {
                    long elapsed = System.nanoTime() - loopStart;
                    long remaining = intervalNanos - elapsed;

                    if (remaining > 0) {
                        switch (mode) {
                            case "busy":
                                // Pure spin — most precise but can starve render thread
                                while (System.nanoTime() - loopStart < intervalNanos) {
                                    // empty spin
                                }
                                break;

                            case "park":
                                // Always park — gentle on CPU, good precision
                                LockSupport.parkNanos(remaining);
                                break;

                            case "yield":
                            default:
                                if (remaining > 200_000L) {       // > 200 µs
                                    LockSupport.parkNanos(remaining);
                                } else if (remaining > 10_000L) { // 10–200 µs
                                    long deadline = System.nanoTime() + remaining;
                                    int yields = 0;
                                    while (System.nanoTime() < deadline) {
                                        Thread.yield();           // give render thread CPU
                                        // Periodically check shouldExit during long spins
                                        if (++yields % 64 == 0 && shouldExit) break;
                                    }
                                } else {                          // < 10 µs
                                    // Tiny wait — simple spin
                                    while (System.nanoTime() - loopStart < intervalNanos) {
                                        // minimal spin
                                    }
                                }
                                break;
                        }
                    }
                    // remaining <= 0 → already late, poll immediately
                }
                // intervalNanos == 0: pure busy-wait — always immediately poll

            } catch (Throwable t) {
                System.err.println("[Ixeris] Polling loop error: " + t);
                t.printStackTrace();
                LockSupport.parkNanos(10_000_000L); // 10 ms cooldown
            }
        }

        try {
            if (renderThread != null) {
                renderThread.join(5000);
            }
        } catch (InterruptedException ignored) {}

        System.out.println("[Ixeris] Event polling thread exiting.");
    }

    /** Used by the render thread to atomically read-and-clear the resize flag. */
    public static boolean consumeResized() {
        boolean was = resized;
        resized = false;
        return was;
    }

    /** Used by the render thread to request exclusive input access (prevents rare races). */
    public static boolean acquirePollingLock() {
        return pollingLock.compareAndSet(false, true);
    }

    public static void releasePollingLock() {
        pollingLock.set(false);
    }
}
