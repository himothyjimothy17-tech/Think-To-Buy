package com.qualcomm.robotcore.eventloop.opmode;

import org.biobuzz.simhooks.OpModeTerminatedError;
import org.biobuzz.simhooks.SimHooks;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.HashMap;

/**
 * An "iterative" OpMode: you write init(), loop() etc. and the SDK calls
 * them over and over. (For a step-by-step OpMode use LinearOpMode.)
 */
public abstract class OpMode extends OpModeInternal {

    /** Survives between OpModes (e.g. pass the end-of-auto pose to TeleOp). */
    public static final HashMap<String, Object> blackboard = new HashMap<>();

    /** Seconds since the OpMode started (updated before each loop()). */
    public volatile double time;

    public int msStuckDetectInit = 5000;
    public int msStuckDetectInitLoop = 5000;
    public int msStuckDetectStart = 5000;
    public int msStuckDetectLoop = 5000;

    /**
     * Simulated time the SDK's own event loop spends between two loop() calls
     * (sending telemetry, reading gamepads). 0.5 ms = ESTIMATE.
     */
    static final long EVENT_LOOP_OVERHEAD_NS = 500_000L;

    private long startTimeNs;

    public OpMode() {
        startTimeNs = SimHooks.nanoTime();
    }

    /** Runs once when INIT is pressed. */
    public abstract void init();

    /** Runs repeatedly after init() until START is pressed. */
    public void init_loop() {
    }

    /** Runs once when START is pressed. */
    public void start() {
    }

    /** Runs repeatedly after START until STOP is pressed. */
    public abstract void loop();

    /** Runs once when STOP is pressed. */
    public void stop() {
    }

    /** Ends the OpMode immediately. */
    public final void terminateOpModeNow() {
        requestOpModeStop();
        throw new OpModeTerminatedError("terminateOpModeNow() called");
    }

    /** Seconds since this OpMode was created (or since resetRuntime()). */
    public double getRuntime() {
        return (SimHooks.nanoTime() - startTimeNs) / 1e9;
    }

    public void resetRuntime() {
        startTimeNs = SimHooks.nanoTime();
    }

    public void updateTelemetry(Telemetry telemetry) {
        telemetry.update();
    }

    /** The iterative OpMode lifecycle, run by the simulator on the OpMode thread. */
    @Override
    void internalRunOpMode() throws InterruptedException {
        init();
        telemetry.update();
        while (!isStarted && !stopRequested) {
            init_loop();
            telemetry.update();
            SimHooks.charge(EVENT_LOOP_OVERHEAD_NS);
        }
        if (!stopRequested) {
            resetRuntime();
            start();
            while (!stopRequested) {
                time = getRuntime();
                loop();
                telemetry.update();
                SimHooks.charge(EVENT_LOOP_OVERHEAD_NS);
            }
        }
        stop();
    }
}
