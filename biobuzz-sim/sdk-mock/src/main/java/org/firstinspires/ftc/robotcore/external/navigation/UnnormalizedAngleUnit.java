package org.firstinspires.ftc.robotcore.external.navigation;

/** Like AngleUnit but values are NOT wrapped into +/-180 (used for rotation rates). */
public enum UnnormalizedAngleUnit {
    DEGREES, RADIANS;

    public double toDegrees(double inOurUnits) {
        return this == DEGREES ? inOurUnits : Math.toDegrees(inOurUnits);
    }

    public double toRadians(double inOurUnits) {
        return this == RADIANS ? inOurUnits : Math.toRadians(inOurUnits);
    }

    public double fromDegrees(double degrees) {
        return this == DEGREES ? degrees : Math.toRadians(degrees);
    }

    public double fromRadians(double radians) {
        return this == RADIANS ? radians : Math.toDegrees(radians);
    }

    public AngleUnit getNormalized() {
        return this == DEGREES ? AngleUnit.DEGREES : AngleUnit.RADIANS;
    }
}
