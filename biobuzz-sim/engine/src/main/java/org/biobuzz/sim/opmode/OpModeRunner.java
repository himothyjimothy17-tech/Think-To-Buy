package org.biobuzz.sim.opmode;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeSimBridge;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.biobuzz.sim.core.Lockstep;
import org.biobuzz.sim.hardware.RobotHardwareSim;
import org.biobuzz.sim.hardware.SimTelemetry;
import org.biobuzz.simhooks.OpModeTerminatedError;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

/**
 * Plays the role of the Driver Station app: INIT, START and STOP an OpMode.
 *
 * All methods are called from the physics thread. The OpMode itself runs on
 * its own thread, managed by {@link Lockstep}.
 */
public final class OpModeRunner {

    public enum State { NONE, INIT, RUNNING, STOPPED, CRASHED }

    /** Grace period after STOP before the OpMode is killed (real SDK: about 1 s). */
    private static final long FORCE_STOP_GRACE_NS = 1_000_000_000L;

    private final Lockstep lockstep;
    private final RobotHardwareSim hardware;
    private final SimTelemetry telemetry;
    private final Consumer<String> log;

    /** The gamepads handed to the OpMode (the sim copies browser input into these). */
    public final Gamepad gamepad1 = new Gamepad();
    public final Gamepad gamepad2 = new Gamepad();

    private volatile State state = State.NONE;
    private volatile String error;
    private OpMode current;
    private OpModeRegistry.Entry entry;
    private long startedAtNs = -1;

    public OpModeRunner(Lockstep lockstep, RobotHardwareSim hardware, SimTelemetry telemetry, Consumer<String> log) {
        this.lockstep = lockstep;
        this.hardware = hardware;
        this.telemetry = telemetry;
        this.log = log;
    }

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    public OpModeRegistry.Entry entry() {
        return entry;
    }

    /** Simulated time when START was pressed, or -1. */
    public long startedAtNs() {
        return startedAtNs;
    }

    /** Presses INIT for the given OpMode. */
    public void init(OpModeRegistry.Entry e) {
        if (state == State.INIT || state == State.RUNNING) {
            abort();
        }
        entry = e;
        error = null;
        startedAtNs = -1;
        hardware.resetForNewOpMode();
        telemetry.resetForNewOpMode();
        gamepad1.reset();
        gamepad2.reset();
        gamepad1.resetEdgeDetection();
        gamepad2.resetEdgeDetection();
        try {
            current = e.type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            crash(ex instanceof java.lang.reflect.InvocationTargetException ? ex.getCause() : ex);
            return;
        }
        current.hardwareMap = hardware.hardwareMap;
        current.telemetry = telemetry;
        current.gamepad1 = gamepad1;
        current.gamepad2 = gamepad2;
        final OpMode op = current;
        state = State.INIT;
        log.accept("INIT " + e.name);
        lockstep.startOpModeThread(() -> {
            try {
                OpModeSimBridge.run(op);
            } catch (OpModeTerminatedError t) {
                if (!"simulation was reset".equals(t.getMessage())) {
                    error = t.getMessage();
                }
            } catch (InterruptedException t) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                crash(t);
            }
        }, "OpMode: " + e.name);
    }

    /** Presses START. */
    public void start() {
        if (state != State.INIT || current == null) {
            return;
        }
        OpModeSimBridge.pressStart(current);
        state = State.RUNNING;
        startedAtNs = lockstep.physicsTimeNs();
        log.accept("START " + entry.name);
    }

    /** Presses STOP. */
    public void stop() {
        if ((state != State.INIT && state != State.RUNNING) || current == null) {
            return;
        }
        OpModeSimBridge.pressStop(current);
        lockstep.scheduleForceStop(FORCE_STOP_GRACE_NS);
        log.accept("STOP " + entry.name);
    }

    /** Kills the OpMode right away (used by Reset). */
    public void abort() {
        if (current != null) {
            OpModeSimBridge.pressStop(current);
        }
        lockstep.abortOpMode();
        hardware.stopAllMotors();
        if (state == State.INIT || state == State.RUNNING) {
            state = State.STOPPED;
        }
        current = null;
    }

    /**
     * The OpMode stopped calling the SDK entirely (endless loop with no
     * hardware calls, or Thread.sleep()). The real robot would freeze and the
     * SDK would eventually restart the app; the sim kills the OpMode.
     */
    public void killStuck(String reason) {
        error = reason;
        state = State.CRASHED;
        log.accept("KILLED " + (entry != null ? entry.name : "") + ": " + reason);
        lockstep.abortOpMode();
        hardware.stopAllMotors();
        current = null;
    }

    /** Called after every physics step: notices when the OpMode thread has ended. */
    public void afterStep() {
        if ((state == State.INIT || state == State.RUNNING) && !lockstep.isOpModeRunning()) {
            // Like the real SDK: when an OpMode ends, every motor is stopped.
            hardware.stopAllMotors();
            if (state != State.CRASHED) {
                state = State.STOPPED;
                log.accept(entry.name + " finished" + (error != null ? ": " + error : ""));
            }
            current = null;
        }
    }

    private void crash(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        // Keep the lines that point into TeamCode - that's where the bug is.
        StringBuilder msg = new StringBuilder("User code threw an uncaught exception: ").append(t);
        for (String line : sw.toString().split("\n")) {
            if (line.contains("org.firstinspires.ftc.teamcode")) {
                msg.append("\n  ").append(line.trim());
            }
        }
        error = msg.toString();
        state = State.CRASHED;
        log.accept("CRASH " + (entry != null ? entry.name : "") + ": " + t);
    }
}
