package com.qualcomm.robotcore.eventloop.opmode;

import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Fields shared by every OpMode. (The real SDK has this same hidden base class.)
 * The simulator fills in gamepad1, gamepad2, telemetry and hardwareMap before
 * the OpMode starts.
 */
abstract class OpModeInternal {
    /** How long the SDK waits for an OpMode to stop before force-killing it. */
    public static final int MS_BEFORE_FORCE_STOP_AFTER_STOP_REQUESTED = 900;

    public volatile Gamepad gamepad1 = new Gamepad();
    public volatile Gamepad gamepad2 = new Gamepad();
    public Telemetry telemetry;
    public volatile HardwareMap hardwareMap;
    public int msStuckDetectStop = 1000;

    volatile boolean isStarted;
    volatile boolean stopRequested;

    OpModeInternal() {
    }

    /** Asks the OpMode to stop, like pressing the stop button. */
    public final void requestOpModeStop() {
        stopRequested = true;
    }

    /** Runs the OpMode's whole lifecycle on the current thread. */
    abstract void internalRunOpMode() throws InterruptedException;
}
