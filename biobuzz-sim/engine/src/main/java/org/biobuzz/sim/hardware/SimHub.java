package org.biobuzz.sim.hardware;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorController;
import com.qualcomm.robotcore.hardware.ServoController;
import com.qualcomm.robotcore.hardware.VoltageSensor;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.biobuzz.simhooks.SimHooks;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit;

import java.util.function.DoubleSupplier;

/**
 * A simulated REV Control Hub or Expansion Hub. Like the real hub, it is at
 * the same time a LynxModule (bulk reads), a motor controller, a servo
 * controller and a battery voltage sensor (hardwareMap.voltageSensor lists
 * one entry per hub).
 */
public final class SimHub extends LynxModule implements DcMotorController, ServoController, VoltageSensor {

    /** What a bulk read returns for one motor port. */
    enum Channel { POSITION, VELOCITY, BUSY }

    public static final int MOTOR_PORTS = 4;
    public static final int SERVO_PORTS = 6;

    public final String name;
    public final boolean isControlHub;
    final long readNs;
    final long writeNs;
    private final long voltageReadNs;
    private final DoubleSupplier batteryVoltage;

    final SimDcMotor[] motors = new SimDcMotor[MOTOR_PORTS];
    final SimServo[] servos = new SimServo[SERVO_PORTS];
    private PwmStatus pwmStatus = PwmStatus.ENABLED;

    // ---- bulk read cache ----
    private boolean cacheValid;
    private final double[][] cache = new double[MOTOR_PORTS][Channel.values().length];
    private final boolean[][] readSinceBulk = new boolean[MOTOR_PORTS][Channel.values().length];
    /** How many messages this hub has handled (shown in the sim; useful for loop-time tuning). */
    public long messageCount;

    public SimHub(String name, boolean isControlHub, HubTiming timing, DoubleSupplier batteryVoltage) {
        super(isControlHub);
        this.name = name;
        this.isControlHub = isControlHub;
        this.readNs = isControlHub ? timing.controlHubReadNs : timing.expansionHubReadNs;
        this.writeNs = isControlHub ? timing.controlHubWriteNs : timing.expansionHubWriteNs;
        this.voltageReadNs = timing.voltageReadNs;
        this.batteryVoltage = batteryVoltage;
    }

    // ---- Reads, with bulk caching ----

    /**
     * Reads one value from a motor port, the way the real SDK does:
     * OFF = its own message; AUTO/MANUAL = from the last bulk read if possible.
     */
    double readMotor(int port, Channel channel) {
        BulkCachingMode mode = getBulkCachingMode();
        if (mode == BulkCachingMode.OFF) {
            messageCount++;
            SimHooks.charge(readNs);
            return live(port, channel);
        }
        int c = channel.ordinal();
        if (!cacheValid || (mode == BulkCachingMode.AUTO && readSinceBulk[port][c])) {
            bulkRead();
        }
        readSinceBulk[port][c] = true;
        return cache[port][c];
    }

    /** One message that returns every motor's encoder, velocity and busy flag. */
    private void bulkRead() {
        messageCount++;
        SimHooks.charge(readNs);
        for (int port = 0; port < MOTOR_PORTS; port++) {
            for (Channel ch : Channel.values()) {
                cache[port][ch.ordinal()] = motors[port] == null ? 0 : live(port, ch);
                readSinceBulk[port][ch.ordinal()] = false;
            }
        }
        cacheValid = true;
    }

    private double live(int port, Channel channel) {
        SimDcMotor m = motors[port];
        if (m == null) {
            return 0;
        }
        switch (channel) {
            case POSITION: return m.rawTicks();
            case VELOCITY: return m.measuredTicksPerSecondRaw();
            default: return m.busyRaw() ? 1 : 0;
        }
    }

    /** A write (setPower etc.) - always its own message. */
    void write() {
        messageCount++;
        SimHooks.charge(writeNs);
    }

    @Override
    public void clearBulkCache() {
        cacheValid = false;
    }

    @Override
    public double getCurrent(CurrentUnit unit) {
        messageCount++;
        SimHooks.charge(readNs);
        double amps = 0;
        for (SimDcMotor m : motors) {
            if (m != null) {
                amps += Math.abs(m.state.currentA);
            }
        }
        return unit.convert(amps, CurrentUnit.AMPS);
    }

    @Override
    public double getInputVoltage(VoltageUnit unit) {
        messageCount++;
        SimHooks.charge(voltageReadNs);
        return unit.convert(batteryVoltage.getAsDouble(), VoltageUnit.VOLTS);
    }

    // ---- VoltageSensor ----

    @Override
    public double getVoltage() {
        messageCount++;
        SimHooks.charge(voltageReadNs);
        return batteryVoltage.getAsDouble();
    }

    // ---- DcMotorController (forwards to the motor on that port) ----

    private SimDcMotor motor(int port) {
        SimDcMotor m = motors[port];
        if (m == null) {
            throw new IllegalArgumentException("No motor configured on " + name + " port " + port);
        }
        return m;
    }

    @Override
    public void setMotorType(int motor, MotorConfigurationType motorType) {
        motor(motor).setMotorType(motorType);
    }

    @Override
    public MotorConfigurationType getMotorType(int motor) {
        return motor(motor).getMotorType();
    }

    @Override
    public void setMotorMode(int motor, DcMotor.RunMode mode) {
        motor(motor).setMode(mode);
    }

    @Override
    public DcMotor.RunMode getMotorMode(int motor) {
        return motor(motor).getMode();
    }

    @Override
    public void setMotorPower(int motor, double power) {
        motor(motor).setPower(power);
    }

    @Override
    public double getMotorPower(int motor) {
        return motor(motor).getPower();
    }

    @Override
    public boolean isBusy(int motor) {
        return motor(motor).isBusy();
    }

    @Override
    public void setMotorZeroPowerBehavior(int motor, DcMotor.ZeroPowerBehavior zeroPowerBehavior) {
        motor(motor).setZeroPowerBehavior(zeroPowerBehavior);
    }

    @Override
    public DcMotor.ZeroPowerBehavior getMotorZeroPowerBehavior(int motor) {
        return motor(motor).getZeroPowerBehavior();
    }

    @Override
    public boolean getMotorPowerFloat(int motor) {
        return motor(motor).getPowerFloat();
    }

    @Override
    public void setMotorTargetPosition(int motor, int position) {
        motor(motor).setTargetPosition(position);
    }

    @Override
    public int getMotorTargetPosition(int motor) {
        return motor(motor).getTargetPosition();
    }

    @Override
    public int getMotorCurrentPosition(int motor) {
        return motor(motor).getCurrentPosition();
    }

    @Override
    public void resetDeviceConfigurationForOpMode(int motor) {
        motor(motor).resetDeviceConfigurationForOpMode();
    }

    // ---- ServoController ----

    @Override
    public void pwmEnable() {
        pwmStatus = PwmStatus.ENABLED;
    }

    @Override
    public void pwmDisable() {
        pwmStatus = PwmStatus.DISABLED;
    }

    @Override
    public PwmStatus getPwmStatus() {
        return pwmStatus;
    }

    @Override
    public void setServoPosition(int servo, double position) {
        servos[servo].setPosition(position);
    }

    @Override
    public double getServoPosition(int servo) {
        return servos[servo].getPosition();
    }

    @Override
    public void forgetLastKnownPosition(int servo) {
    }

    // ---- HardwareDevice ----

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return isControlHub ? "REV Control Hub" : "REV Expansion Hub";
    }

    @Override
    public String getConnectionInfo() {
        return name;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        pwmStatus = PwmStatus.ENABLED;
        bulkCachingMode = BulkCachingMode.OFF;
        cacheValid = false;
    }

    @Override
    public void close() {
    }
}
