package com.qualcomm.robotcore.hardware;

/** A continuous rotation servo (set power instead of position). */
public interface CRServo extends DcMotorSimple {
    ServoController getController();

    int getPortNumber();
}
