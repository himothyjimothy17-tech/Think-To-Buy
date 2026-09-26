package com.qualcomm.robotcore.eventloop.opmode;

import org.biobuzz.simhooks.SimHooks;

/**
 * A step-by-step OpMode: you write everything inside runOpMode().
 * Always check opModeIsActive() in your loops so STOP works.
 */
public abstract class LinearOpMode extends OpMode {

    /** Simulated cost of one idle() call: 0.1 ms (ESTIMATE). */
    static final long IDLE_COST_NS = 100_000L;

    /** sleep() advances simulated time in slices this big so STOP can interrupt it. */
    private static final long SLEEP_SLICE_NS = 5_000_000L;

    public LinearOpMode() {
    }

    /** Write your whole OpMode here. */
    public abstract void runOpMode() throws InterruptedException;

    /** Waits until START is pressed (or STOP during init). */
    public void waitForStart() {
        while (!isStarted() && !isStopRequested()) {
            idle();
        }
    }

    /** Gives the rest of the system a moment to run. */
    public final void idle() {
        Thread.yield();
        SimHooks.charge(IDLE_COST_NS);
    }

    /** Pauses this OpMode for the given number of (simulated) milliseconds. */
    public final void sleep(long milliseconds) {
        // Even a sleep() that returns early (because STOP was pressed) takes a
        // moment of CPU time. Without this, "while (true) sleep(10);" after STOP
        // would freeze the simulated clock.
        SimHooks.charge(IDLE_COST_NS);
        long remaining = milliseconds * 1_000_000L;
        while (remaining > 0 && !isStopRequested()) {
            long slice = Math.min(remaining, SLEEP_SLICE_NS);
            SimHooks.charge(slice);
            remaining -= slice;
        }
    }

    /** True after START and before STOP. Call this in every loop. */
    public final boolean opModeIsActive() {
        boolean isActive = !isStopRequested() && isStarted();
        if (isActive) {
            idle();
        }
        return isActive;
    }

    /** True after INIT and before START (and not stopped). */
    public final boolean opModeInInit() {
        return !isStarted() && !isStopRequested();
    }

    public final boolean isStarted() {
        return isStarted || Thread.currentThread().isInterrupted();
    }

    public final boolean isStopRequested() {
        return stopRequested || Thread.currentThread().isInterrupted();
    }

    // In a LinearOpMode these iterative methods are unused (and final, like the real SDK).
    @Override
    public final void init() {
    }

    @Override
    public final void init_loop() {
    }

    @Override
    public final void start() {
    }

    @Override
    public final void loop() {
    }

    @Override
    public final void stop() {
    }

    @Override
    final void internalRunOpMode() throws InterruptedException {
        runOpMode();
    }
}
