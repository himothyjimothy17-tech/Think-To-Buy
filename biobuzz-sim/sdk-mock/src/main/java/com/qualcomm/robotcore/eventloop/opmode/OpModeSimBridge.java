package com.qualcomm.robotcore.eventloop.opmode;

import org.biobuzz.simhooks.SimOnly;

/**
 * Lets the simulator drive an OpMode's lifecycle (INIT / START / STOP),
 * the way the Driver Station app does on a real robot.
 * TeamCode must never use this class.
 */
@SimOnly
public final class OpModeSimBridge {
    private OpModeSimBridge() {
    }

    /** Runs the full OpMode on the calling thread (blocks until it ends). */
    public static void run(OpMode opMode) throws InterruptedException {
        opMode.internalRunOpMode();
    }

    /** Presses START. */
    public static void pressStart(OpMode opMode) {
        opMode.isStarted = true;
    }

    /** Presses STOP. */
    public static void pressStop(OpMode opMode) {
        opMode.stopRequested = true;
    }

    public static boolean isStarted(OpMode opMode) {
        return opMode.isStarted;
    }

    public static boolean isStopRequested(OpMode opMode) {
        return opMode.stopRequested;
    }
}
