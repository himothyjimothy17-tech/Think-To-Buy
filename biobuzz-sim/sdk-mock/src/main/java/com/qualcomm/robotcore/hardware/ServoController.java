package com.qualcomm.robotcore.hardware;

/** The hub that servos are plugged into. */
public interface ServoController extends HardwareDevice {

    enum PwmStatus { ENABLED, DISABLED, MIXED }

    void pwmEnable();

    void pwmDisable();

    PwmStatus getPwmStatus();

    void setServoPosition(int servo, double position);

    double getServoPosition(int servo);

    void forgetLastKnownPosition(int servo);
}
