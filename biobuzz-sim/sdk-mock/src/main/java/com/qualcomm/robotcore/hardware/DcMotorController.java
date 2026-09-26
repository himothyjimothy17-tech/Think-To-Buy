package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

/** The hub that motors are plugged into. */
public interface DcMotorController extends HardwareDevice {
    void setMotorType(int motor, MotorConfigurationType motorType);

    MotorConfigurationType getMotorType(int motor);

    void setMotorMode(int motor, DcMotor.RunMode mode);

    DcMotor.RunMode getMotorMode(int motor);

    void setMotorPower(int motor, double power);

    double getMotorPower(int motor);

    boolean isBusy(int motor);

    void setMotorZeroPowerBehavior(int motor, DcMotor.ZeroPowerBehavior zeroPowerBehavior);

    DcMotor.ZeroPowerBehavior getMotorZeroPowerBehavior(int motor);

    boolean getMotorPowerFloat(int motor);

    void setMotorTargetPosition(int motor, int position);

    int getMotorTargetPosition(int motor);

    int getMotorCurrentPosition(int motor);

    void resetDeviceConfigurationForOpMode(int motor);
}
