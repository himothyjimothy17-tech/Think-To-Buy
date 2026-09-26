package org.biobuzz.sim.hardware;

import com.qualcomm.robotcore.hardware.DcMotorController;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.PIDCoefficients;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.biobuzz.sim.physics.MotorState;
import org.biobuzz.simhooks.SimHooks;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

/**
 * A simulated motor port. Our TeamCode talks to this through the normal
 * DcMotorEx interface; this class passes the commands to the physics
 * ({@link MotorState}) and reads the simulated encoder back.
 *
 * Things it gets right (like the real SDK):
 *   - setDirection(REVERSE) flips both the power AND the encoder reading.
 *   - STOP_AND_RESET_ENCODER zeroes the encoder and stops the motor.
 *   - Every call costs simulated time (more on the Expansion Hub).
 *   - Setting the same power twice in a row only costs time once (the SDK caches it).
 *
 * TODO(stage 2): RUN_USING_ENCODER / setVelocity closed loop, RUN_TO_POSITION,
 * current draw, and velocity quantization. In stage 1 every mode is open-loop.
 */
public final class SimDcMotor implements DcMotorEx {

    private final String configName;
    private final SimHub hub;
    private final int port;
    public final MotorState state;
    private MotorConfigurationType motorType;

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
    private PIDFCoefficients velocityPidf = new PIDFCoefficients(10, 3, 0, 0);
    private PIDFCoefficients positionPidf = new PIDFCoefficients(10, 0, 0, 0);

    public SimDcMotor(String configName, SimHub hub, int port, MotorState state) {
        this.configName = configName;
        this.hub = hub;
        this.port = port;
        this.state = state;
        this.motorType = new MotorConfigurationType(state.spec.ticksPerRev, state.spec.gearRatio, state.spec.freeRpm);
    }

    public String configName() {
        return configName;
    }

    /** Encoder count straight from the motor, before direction and reset are applied. */
    private long rawTicks() {
        return (long) Math.floor(state.angleRad * state.spec.ticksPerRadian());
    }

    private int directionSign() {
        return direction == Direction.FORWARD ? 1 : -1;
    }

    private void read() {
        SimHooks.charge(hub.readNs);
    }

    private void write() {
        SimHooks.charge(hub.writeNs);
    }

    /** Sends the current power to the physics, taking direction and mode into account. */
    private void applyToPhysics() {
        boolean stopped = !enabled || mode == RunMode.STOP_AND_RESET_ENCODER;
        state.appliedPower = stopped ? 0.0 : power * directionSign();
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
        if (clipped != this.power) {
            write(); // the SDK skips sending a power the hub already has
        }
        this.power = clipped;
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
        write();
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
        write();
        this.targetPosition = position;
    }

    @Override
    public int getTargetPosition() {
        return targetPosition;
    }

    @Override
    public boolean isBusy() {
        read();
        // TODO(stage 2): true while RUN_TO_POSITION is still moving.
        return false;
    }

    @Override
    public int getCurrentPosition() {
        read();
        return (int) ((rawTicks() - encoderZeroTicks) * directionSign());
    }

    @Override
    public void setMode(RunMode mode) {
        write();
        this.mode = mode.migrate();
        if (this.mode == RunMode.STOP_AND_RESET_ENCODER) {
            encoderZeroTicks = rawTicks();
            power = 0.0;
        }
        applyToPhysics();
    }

    @Override
    public RunMode getMode() {
        return mode;
    }

    // ---- DcMotorEx ----

    @Override
    public void setMotorEnable() {
        write();
        enabled = true;
        applyToPhysics();
    }

    @Override
    public void setMotorDisable() {
        write();
        enabled = false;
        applyToPhysics();
    }

    @Override
    public boolean isMotorEnabled() {
        return enabled;
    }

    @Override
    public void setVelocity(double angularRate) {
        // TODO(stage 2): real closed-loop velocity control using velocityPidf.
        // Stage 1: open-loop approximation (power = requested / max achievable).
        double maxTicksPerSec = motorType.getAchieveableMaxTicksPerSecond();
        setPower(maxTicksPerSec > 0 ? angularRate / maxTicksPerSec : 0.0);
    }

    @Override
    public void setVelocity(double angularRate, AngleUnit unit) {
        double radPerSec = unit == AngleUnit.DEGREES ? Math.toRadians(angularRate) : angularRate;
        setVelocity(radPerSec * motorType.getTicksPerRev() / (2 * Math.PI));
    }

    @Override
    public double getVelocity() {
        read();
        return state.velocityRadPerSec * state.spec.ticksPerRadian() * directionSign();
    }

    @Override
    public double getVelocity(AngleUnit unit) {
        read();
        double radPerSec = state.velocityRadPerSec * directionSign();
        return unit == AngleUnit.DEGREES ? Math.toDegrees(radPerSec) : radPerSec;
    }

    @Override
    @Deprecated
    public void setPIDCoefficients(RunMode mode, PIDCoefficients pidCoefficients) {
        setPIDFCoefficients(mode, new PIDFCoefficients(pidCoefficients));
    }

    @Override
    public void setPIDFCoefficients(RunMode mode, PIDFCoefficients pidfCoefficients) {
        write();
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
        read();
        return new PIDFCoefficients(mode.migrate() == RunMode.RUN_TO_POSITION ? positionPidf : velocityPidf);
    }

    @Override
    public void setTargetPositionTolerance(int tolerance) {
        write();
        targetTolerance = tolerance;
    }

    @Override
    public int getTargetPositionTolerance() {
        return targetTolerance;
    }

    @Override
    public double getCurrent(CurrentUnit unit) {
        read();
        // TODO(stage 2): real current from the motor model.
        return 0.0;
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
        enabled = true;
        applyToPhysics();
    }

    @Override
    public void close() {
        setPower(0.0);
    }
}
