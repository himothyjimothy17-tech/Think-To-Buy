package org.biobuzz.sim.hardware;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorController;
import com.qualcomm.robotcore.hardware.ServoController;
import com.qualcomm.robotcore.hardware.VoltageSensor;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.biobuzz.simhooks.SimHooks;

import java.util.function.DoubleSupplier;

/**
 * A simulated REV Control Hub or Expansion Hub. Like the real hub, it is at
 * the same time a motor controller, a servo controller and a battery
 * voltage sensor (hardwareMap.voltageSensor lists one entry per hub).
 */
public final class SimHub implements DcMotorController, ServoController, VoltageSensor {

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

    public SimHub(String name, boolean isControlHub, HubTiming timing, DoubleSupplier batteryVoltage) {
        this.name = name;
        this.isControlHub = isControlHub;
        this.readNs = isControlHub ? timing.controlHubReadNs : timing.expansionHubReadNs;
        this.writeNs = isControlHub ? timing.controlHubWriteNs : timing.expansionHubWriteNs;
        this.voltageReadNs = timing.voltageReadNs;
        this.batteryVoltage = batteryVoltage;
    }

    // ---- VoltageSensor ----

    @Override
    public double getVoltage() {
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
    }

    @Override
    public void close() {
    }
}
