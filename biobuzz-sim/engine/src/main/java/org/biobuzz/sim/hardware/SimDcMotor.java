package org.biobuzz.sim.hardware;

import com.qualcomm.robotcore.hardware.DcMotorController;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.PIDCoefficients;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.biobuzz.sim.physics.MotorState;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

/**
 * A simulated motor port, including the hub's built-in motor control.
 *
 * Things it gets right (like the real SDK + REV hub):
 *   - setDirection(REVERSE) flips the power AND the encoder reading.
 *   - STOP_AND_RESET_ENCODER zeroes the encoder and stops the motor.
 *   - RUN_WITHOUT_ENCODER: setPower() is the raw duty cycle.
 *   - RUN_USING_ENCODER: setPower(p) asks for p x max velocity, and
 *     setVelocity(v) asks for v ticks/s; the HUB runs a PIDF loop every
 *     20 ms to hold it (REV units: F = 32767 / max ticks per second).
 *   - RUN_TO_POSITION: the hub drives to setTargetPosition() at up to
 *     |power| x max speed; isBusy() is true until it's within tolerance.
 *   - getVelocity() is MEASURED from the encoder over a 50 ms window and
 *     rounded to whole ticks, so 28-tick motors read in steps of 20 ticks/s.
 *   - Every call costs simulated time; bulk caching makes reads cheaper.
 */
public final class SimDcMotor implements DcMotorEx {

    /** REV's PIDF output scale: 32767 = full power. */
    private static final double REV_OUTPUT_SCALE = 32767.0;

    private final String configName;
    private final SimHub hub;
    private final int port;
    public final MotorState state;
    private MotorConfigurationType motorType;
    private final double controlPeriodS;
    private final int velocityWindowSteps;

    private Direction direction = Direction.FORWARD;
    private ZeroPowerBehavior zeroPowerBehavior = ZeroPowerBehavior.BRAKE;
    private RunMode mode = RunMode.RUN_WITHOUT_ENCODER;
    private double power;
    private int targetPosition;
    private int targetTolerance = 5;
    private boolean enabled = true;
    private double currentAlertAmps = 5.0;
    /** Raw encoder count at the last STOP_AND_RESET_ENCODER. */
    private long encoderZeroTicks;
    /** Velocity target for RUN_USING_ENCODER (code direction, ticks/s), or NaN to use power x max. */
    private double velocityTarget = Double.NaN;
    // Defaults the SDK uses for goBILDA motors (ESTIMATE of REV firmware behavior).
    private PIDFCoefficients velocityPidf = new PIDFCoefficients(10, 3, 0, 0);
    private PIDFCoefficients positionPidf = new PIDFCoefficients(10, 0, 0, 0);

    // ---- hub control loop state ----
    private double controlTimer;
    private double integral;
    private double lastError;
    private double controlOutput;
    private boolean busy;
    /** Raw ticks over the last velocityWindow steps (ring buffer) for the velocity measurement. */
    private final long[] tickHistory;
    private int historyIndex;
    private double measuredTps;

    public SimDcMotor(String configName, SimHub hub, int port, MotorState state, HubTiming timing) {
        this.configName = configName;
        this.hub = hub;
        this.port = port;
        this.state = state;
        this.motorType = new MotorConfigurationType(state.spec.ticksPerRev, state.spec.gearRatio, state.spec.freeRpm);
        this.controlPeriodS = timing.motorControlPeriodS;
        this.velocityWindowSteps = Math.max(1, timing.velocityWindowSteps);
        this.tickHistory = new long[velocityWindowSteps + 1];
        hub.motors[port] = this;
    }

    public String configName() {
        return configName;
    }

    /** Encoder count straight from the motor, before direction and reset are applied. */
    long rawTicks() {
        return (long) Math.floor(state.angleRad * state.spec.ticksPerRadian());
    }

    /** Measured velocity in raw (motor) direction, ticks/s. */
    double measuredTicksPerSecondRaw() {
        return measuredTps;
    }

    boolean busyRaw() {
        return busy;
    }

    private int directionSign() {
        return direction == Direction.FORWARD ? 1 : -1;
    }

    private double maxTicksPerSecond() {
        return motorType.getAchieveableMaxTicksPerSecond();
    }

    // =====================================================================
    // Hub firmware: runs every physics step (called by the simulation)
    // =====================================================================

    /** Updates the velocity measurement and runs the hub's control loop. */
    public void hubStep(double dt) {
        // Velocity = change in encoder count over the window, in whole ticks per second.
        long now = rawTicks();
        historyIndex = (historyIndex + 1) % tickHistory.length;
        long old = tickHistory[historyIndex];
        tickHistory[historyIndex] = now;
        double window = velocityWindowSteps * dt;
        measuredTps = Math.round((now - old) / window);

        controlTimer += dt;
        if (controlTimer + 1e-9 >= controlPeriodS) {
            controlTimer -= controlPeriodS;
            runControlLoop();
        }
        applyToPhysics();
    }

    private void runControlLoop() {
        if (mode == RunMode.RUN_USING_ENCODER || mode == RunMode.RUN_TO_POSITION) {
            int dir = directionSign();
            double positionCode = (rawTicks() - encoderZeroTicks) * dir;
            double velocityCode = measuredTps * dir;
            double target;
            if (mode == RunMode.RUN_TO_POSITION) {
                double error = targetPosition - positionCode;
                busy = Math.abs(error) > targetTolerance;
                // Position loop -> velocity request, limited by |power| x max speed.
                double limit = Math.abs(power) * maxTicksPerSecond();
                target = Math.max(-limit, Math.min(limit, positionPidf.p * error));
                if (!busy) {
                    target = 0;
                }
            } else {
                busy = false;
                target = Double.isNaN(velocityTarget) ? power * maxTicksPerSecond() : velocityTarget;
            }
            double error = target - velocityCode;
            // Anti-windup ("conditional integration"): while the output is already
            // maxed out in the direction of the error, don't keep adding to the
            // integral - otherwise it overshoots badly after a big spin-up.
            double trial = (velocityPidf.p * error + velocityPidf.i * (integral + error)
                    + velocityPidf.d * (error - lastError) + velocityPidf.f * target) / REV_OUTPUT_SCALE;
            boolean saturated = Math.abs(trial) >= 1.0 && Math.signum(trial) == Math.signum(error);
            if (!saturated) {
                integral += error;
            }
            double out = (velocityPidf.p * error + velocityPidf.i * integral
                    + velocityPidf.d * (error - lastError) + velocityPidf.f * target) / REV_OUTPUT_SCALE;
            lastError = error;
            controlOutput = Math.max(-1.0, Math.min(1.0, out));
        } else {
            busy = false;
            integral = 0;
            lastError = 0;
        }
    }

    /** Sends the right duty cycle to the physics for the current mode. */
    private void applyToPhysics() {
        double duty;
        if (!enabled || mode == RunMode.STOP_AND_RESET_ENCODER) {
            duty = 0.0;
        } else if (mode == RunMode.RUN_USING_ENCODER || mode == RunMode.RUN_TO_POSITION) {
            duty = controlOutput * directionSign();
        } else {
            duty = power * directionSign();
        }
        state.appliedPower = duty;
        state.brakeAtZero = zeroPowerBehavior != ZeroPowerBehavior.FLOAT;
    }

    // ---- DcMotorSimple ----

    @Override
    public void setDirection(Direction direction) {
        this.direction = direction;
        applyToPhysics();
    }

    @Override
    public Direction getDirection() {
        return direction;
    }

    @Override
    public void setPower(double power) {
        double clipped = Math.max(-1.0, Math.min(1.0, power));
        if (clipped != this.power || !Double.isNaN(velocityTarget)) {
            hub.write(); // the SDK skips sending a power the hub already has
        }
        this.power = clipped;
        velocityTarget = Double.NaN;
        applyToPhysics();
    }

    @Override
    public double getPower() {
        return power;
    }

    // ---- DcMotor ----

    @Override
    public MotorConfigurationType getMotorType() {
        return motorType;
    }

    @Override
    public void setMotorType(MotorConfigurationType motorType) {
        this.motorType = motorType;
    }

    @Override
    public DcMotorController getController() {
        return hub;
    }

    @Override
    public int getPortNumber() {
        return port;
    }

    @Override
    public void setZeroPowerBehavior(ZeroPowerBehavior zeroPowerBehavior) {
        hub.write();
        this.zeroPowerBehavior = zeroPowerBehavior;
        applyToPhysics();
    }

    @Override
    public ZeroPowerBehavior getZeroPowerBehavior() {
        return zeroPowerBehavior;
    }

    @Override
    @Deprecated
    public void setPowerFloat() {
        setZeroPowerBehavior(ZeroPowerBehavior.FLOAT);
        setPower(0.0);
    }

    @Override
    public boolean getPowerFloat() {
        return zeroPowerBehavior == ZeroPowerBehavior.FLOAT && power == 0.0;
    }

    @Override
    public void setTargetPosition(int position) {
        hub.write();
        this.targetPosition = position;
    }

    @Override
    public int getTargetPosition() {
        return targetPosition;
    }

    @Override
    public boolean isBusy() {
        if (mode != RunMode.RUN_TO_POSITION) {
            return false;
        }
        return hub.readMotor(port, SimHub.Channel.BUSY) > 0.5;
    }

    @Override
    public int getCurrentPosition() {
        double raw = hub.readMotor(port, SimHub.Channel.POSITION);
        return (int) ((raw - encoderZeroTicks) * directionSign());
    }

    @Override
    public void setMode(RunMode mode) {
        hub.write();
        RunMode m = mode.migrate();
        if (m == RunMode.RUN_TO_POSITION && this.mode != RunMode.RUN_TO_POSITION) {
            busy = Math.abs(targetPosition - (rawTicks() - encoderZeroTicks) * directionSign()) > targetTolerance;
        }
        this.mode = m;
        if (m == RunMode.STOP_AND_RESET_ENCODER) {
            encoderZeroTicks = rawTicks();
            power = 0.0;
            velocityTarget = Double.NaN;
        }
        integral = 0;
        lastError = 0;
        controlOutput = 0;
        applyToPhysics();
    }

    @Override
    public RunMode getMode() {
        return mode;
    }

    // ---- DcMotorEx ----

    @Override
    public void setMotorEnable() {
        hub.write();
        enabled = true;
        applyToPhysics();
    }

    @Override
    public void setMotorDisable() {
        hub.write();
        enabled = false;
        applyToPhysics();
    }

    @Override
    public boolean isMotorEnabled() {
        return enabled;
    }

    /** Target velocity in ticks/s. Switches the motor to RUN_USING_ENCODER if needed (like the SDK). */
    @Override
    public void setVelocity(double angularRate) {
        hub.write();
        if (mode != RunMode.RUN_USING_ENCODER && mode != RunMode.RUN_TO_POSITION) {
            mode = RunMode.RUN_USING_ENCODER;
            integral = 0;
            lastError = 0;
        }
        velocityTarget = angularRate;
        power = maxTicksPerSecond() > 0 ? Math.max(-1, Math.min(1, angularRate / maxTicksPerSecond())) : 0;
        applyToPhysics();
    }

    @Override
    public void setVelocity(double angularRate, AngleUnit unit) {
        double radPerSec = unit == AngleUnit.DEGREES ? Math.toRadians(angularRate) : angularRate;
        setVelocity(radPerSec * motorType.getTicksPerRev() / (2 * Math.PI));
    }

    @Override
    public double getVelocity() {
        return hub.readMotor(port, SimHub.Channel.VELOCITY) * directionSign();
    }

    @Override
    public double getVelocity(AngleUnit unit) {
        double tps = getVelocity();
        double radPerSec = tps / motorType.getTicksPerRev() * 2 * Math.PI;
        return unit == AngleUnit.DEGREES ? Math.toDegrees(radPerSec) : radPerSec;
    }

    @Override
    @Deprecated
    public void setPIDCoefficients(RunMode mode, PIDCoefficients pidCoefficients) {
        setPIDFCoefficients(mode, new PIDFCoefficients(pidCoefficients));
    }

    @Override
    public void setPIDFCoefficients(RunMode mode, PIDFCoefficients pidfCoefficients) {
        hub.write();
        if (mode.migrate() == RunMode.RUN_TO_POSITION) {
            positionPidf = new PIDFCoefficients(pidfCoefficients);
        } else {
            velocityPidf = new PIDFCoefficients(pidfCoefficients);
        }
    }

    @Override
    public void setVelocityPIDFCoefficients(double p, double i, double d, double f) {
        setPIDFCoefficients(RunMode.RUN_USING_ENCODER, new PIDFCoefficients(p, i, d, f));
    }

    @Override
    public void setPositionPIDFCoefficients(double p) {
        setPIDFCoefficients(RunMode.RUN_TO_POSITION, new PIDFCoefficients(p, 0, 0, 0));
    }

    @Override
    @Deprecated
    public PIDCoefficients getPIDCoefficients(RunMode mode) {
        PIDFCoefficients c = getPIDFCoefficients(mode);
        return new PIDCoefficients(c.p, c.i, c.d);
    }

    @Override
    public PIDFCoefficients getPIDFCoefficients(RunMode mode) {
        hub.write();
        return new PIDFCoefficients(mode.migrate() == RunMode.RUN_TO_POSITION ? positionPidf : velocityPidf);
    }

    @Override
    public void setTargetPositionTolerance(int tolerance) {
        hub.write();
        targetTolerance = tolerance;
    }

    @Override
    public int getTargetPositionTolerance() {
        return targetTolerance;
    }

    /** Motor current (not part of bulk reads - its own message, like the real hub). */
    @Override
    public double getCurrent(CurrentUnit unit) {
        hub.write();
        return unit.convert(Math.abs(state.currentA), CurrentUnit.AMPS);
    }

    @Override
    public double getCurrentAlert(CurrentUnit unit) {
        return unit.convert(currentAlertAmps, CurrentUnit.AMPS);
    }

    @Override
    public void setCurrentAlert(double current, CurrentUnit unit) {
        currentAlertAmps = unit.toAmps(current);
    }

    @Override
    public boolean isOverCurrent() {
        return getCurrent(CurrentUnit.AMPS) > currentAlertAmps;
    }

    // ---- HardwareDevice ----

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return "goBILDA Yellow Jacket (" + state.spec.id + ")";
    }

    @Override
    public String getConnectionInfo() {
        return hub.name + "; port " + port;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    /** Called between OpModes: the SDK resets motors to defaults. */
    @Override
    public void resetDeviceConfigurationForOpMode() {
        direction = Direction.FORWARD;
        mode = RunMode.RUN_WITHOUT_ENCODER;
        power = 0.0;
        velocityTarget = Double.NaN;
        enabled = true;
        integral = 0;
        controlOutput = 0;
        applyToPhysics();
    }

    @Override
    public void close() {
        setPower(0.0);
    }
}
