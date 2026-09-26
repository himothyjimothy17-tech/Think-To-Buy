package com.qualcomm.robotcore.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/** A distance sensor (REV 2m distance sensor, or the distance part of a color sensor). */
public interface DistanceSensor extends HardwareDevice {
    /** Returned when nothing is in range. */
    double distanceOutOfRange = Double.MAX_VALUE;

    double getDistance(DistanceUnit unit);
}
