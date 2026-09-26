package com.qualcomm.robotcore.hardware;

/** A motor (or continuous rotation servo) you can only set power on. */
public interface DcMotorSimple extends HardwareDevice {

    enum Direction {
        FORWARD, REVERSE;

        public Direction inverted() {
            return this == FORWARD ? REVERSE : FORWARD;
        }
    }

    void setDirection(Direction direction);

    Direction getDirection();

    /** Power from -1.0 to 1.0. */
    void setPower(double power);

    double getPower();
}
