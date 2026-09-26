package org.biobuzz.sim.core;

import org.biobuzz.simhooks.OpModeTerminatedError;
import org.biobuzz.simhooks.SimHooks;

/**
 * Makes the OpMode thread and the physics thread TAKE TURNS on one simulated clock.
 *
 * WHY (worth explaining to judges): on a real robot, your OpMode runs on its
 * own thread while the world keeps moving. If the simulator did the same with
 * two free-running threads, results would depend on how busy your computer
 * is - the same test could give different scores. Instead:
 *
 *   1. The physics thread owns the simulated clock and advances it in fixed
 *      1 ms steps.
 *   2. The OpMode thread keeps its own clock. Every SDK call that takes time
 *      on a real Control Hub (reading an encoder, sleep(), idle()) "charges"
 *      that time to the OpMode clock.
 *   3. When the OpMode clock gets ahead of the physics clock, the OpMode
 *      thread pauses and physics runs until it catches up. Then physics
 *      pauses and the OpMode continues.
 *
 * Only one thread runs at a time and the handoffs always happen at the same
 * simulated moments, so the same seed + same inputs = the exact same match,
 * and headless mode can run as fast as the computer allows.
 */
public final class Lockstep implements SimHooks.Backend {

    private final Object lock = new Object();

    private volatile Thread opModeThread;
    /** Simulated time of the physics world. Only the physics thread changes it. */
    private long physicsTimeNs;
    /** Simulated time the OpMode thread has used. Only the OpMode thread changes it. */
    private long opModeTimeNs;
    /** True while the OpMode thread is allowed to run. */
    private boolean opModeTurn;
    private boolean opModeDone = true;
    private boolean abort;
    /** If the OpMode is still running at this simulated time after STOP, it is killed. */
    private long forceStopAtNs = Long.MAX_VALUE;

    // ---------------------------------------------------------------------
    // Called from the OpMode thread (through the fake SDK)
    // ---------------------------------------------------------------------

    @Override
    public long nanoTime() {
        return Thread.currentThread() == opModeThread ? opModeTimeNs : physicsTimeNs;
    }

    @Override
    public void charge(long nanos) {
        if (Thread.currentThread() != opModeThread) {
            return; // e.g. the physics thread reading a device: no cost
        }
        opModeTimeNs += nanos;
        if (opModeTimeNs > physicsTimeNs) {
            yieldToPhysics();
        }
        if (opModeTimeNs >= forceStopAtNs) {
            throw new OpModeTerminatedError(
                    "OpMode kept running after STOP was pressed. Check opModeIsActive() in every loop.");
        }
    }

    private void yieldToPhysics() {
        boolean interrupted = false;
        synchronized (lock) {
            opModeTurn = false;
            lock.notifyAll();
            while (!opModeTurn) {
                if (abort) {
                    throw new OpModeTerminatedError("simulation was reset");
                }
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    // The real SDK interrupts the OpMode thread on STOP. Remember it and
                    // keep waiting for our turn (re-setting the flag inside this loop
                    // would make wait() throw again immediately).
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            // Restore the flag so LinearOpMode.isStopRequested() sees it.
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------
    // Called from the physics thread
    // ---------------------------------------------------------------------

    public long physicsTimeNs() {
        return physicsTimeNs;
    }

    /** Moves the physics clock forward. Only call while the OpMode thread is paused. */
    public void advancePhysics(long dtNs) {
        physicsTimeNs += dtNs;
    }

    /** Resets the clock to zero (only when no OpMode thread is running). */
    public void resetClock() {
        physicsTimeNs = 0;
        opModeTimeNs = 0;
    }

    /**
     * Starts a new OpMode thread. It waits for its first turn, so nothing
     * happens until the physics thread calls {@link #letOpModeCatchUp(long)}.
     */
    public void startOpModeThread(Runnable body, String threadName) {
        synchronized (lock) {
            opModeTimeNs = physicsTimeNs;
            opModeDone = false;
            opModeTurn = false;
            abort = false;
            forceStopAtNs = Long.MAX_VALUE;
        }
        Thread t = new Thread(() -> {
            boolean interrupted = false;
            synchronized (lock) {
                while (!opModeTurn && !abort) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                if (!abort) {
                    body.run();
                }
            } finally {
                synchronized (lock) {
                    opModeDone = true;
                    opModeTurn = false;
                    lock.notifyAll();
                }
            }
        }, threadName);
        t.setDaemon(true);
        opModeThread = t;
        t.start();
    }

    /**
     * Lets the OpMode thread run until its clock is ahead of the physics
     * clock (or it finishes).
     *
     * @return false if the OpMode thread didn't hand control back within
     *     {@code wallTimeoutMs} of REAL time. That means it is stuck in code
     *     that never calls the SDK (an endless loop, or Thread.sleep()).
     *     The caller should report it and call again.
     */
    public boolean letOpModeCatchUp(long wallTimeoutMs) {
        synchronized (lock) {
            if (opModeDone || opModeTimeNs > physicsTimeNs) {
                return true;
            }
            if (!opModeTurn) {
                opModeTurn = true;
                lock.notifyAll();
            }
            long deadline = System.nanoTime() + wallTimeoutMs * 1_000_000L;
            while (opModeTurn && !opModeDone) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return false;
                }
                try {
                    lock.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** STOP was pressed: the OpMode gets a grace period (simulated time) to finish. */
    public void scheduleForceStop(long graceNs) {
        synchronized (lock) {
            forceStopAtNs = physicsTimeNs + graceNs;
        }
        Thread t = opModeThread;
        if (t != null) {
            t.interrupt(); // like the real SDK: wakes up sleep()/wait() in the OpMode
        }
    }

    public boolean isOpModeRunning() {
        synchronized (lock) {
            return !opModeDone;
        }
    }

    /** Kills the OpMode thread immediately (used by Reset). */
    public void abortOpMode() {
        Thread t;
        synchronized (lock) {
            abort = true;
            lock.notifyAll();
            t = opModeThread;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            opModeDone = true;
            opModeTurn = false;
            opModeThread = null;
        }
    }
}
