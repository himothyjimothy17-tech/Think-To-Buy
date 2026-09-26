package org.firstinspires.ftc.robotcore.external.navigation;

/**
 * Degrees or radians. Angles are "normalized" into the range
 * (-180, +180] degrees or (-PI, +PI] radians.
 */
public enum AngleUnit {
    DEGREES(0), RADIANS(1);

    public final byte bVal;
    protected static final double TwoPi = 2 * Math.PI;
    public static final float Pif = (float) Math.PI;

    AngleUnit(int i) {
        bVal = (byte) i;
    }

    public double fromDegrees(double degrees) {
        return this == RADIANS ? normalizeRadians(Math.toRadians(degrees)) : normalizeDegrees(degrees);
    }

    public float fromDegrees(float degrees) {
        return (float) fromDegrees((double) degrees);
    }

    public double fromRadians(double radians) {
        return this == RADIANS ? normalizeRadians(radians) : normalizeDegrees(Math.toDegrees(radians));
    }

    public float fromRadians(float radians) {
        return (float) fromRadians((double) radians);
    }

    public double fromUnit(AngleUnit them, double theirs) {
        return them == DEGREES ? fromDegrees(theirs) : fromRadians(theirs);
    }

    public float fromUnit(AngleUnit them, float theirs) {
        return (float) fromUnit(them, (double) theirs);
    }

    public double toDegrees(double inOurUnits) {
        return this == DEGREES ? normalizeDegrees(inOurUnits) : normalizeDegrees(Math.toDegrees(inOurUnits));
    }

    public float toDegrees(float inOurUnits) {
        return (float) toDegrees((double) inOurUnits);
    }

    public double toRadians(double inOurUnits) {
        return this == RADIANS ? normalizeRadians(inOurUnits) : normalizeRadians(Math.toRadians(inOurUnits));
    }

    public float toRadians(float inOurUnits) {
        return (float) toRadians((double) inOurUnits);
    }

    public double normalize(double mine) {
        return this == DEGREES ? normalizeDegrees(mine) : normalizeRadians(mine);
    }

    public float normalize(float mine) {
        return (float) normalize((double) mine);
    }

    public static double normalizeDegrees(double degrees) {
        while (degrees >= 180.0) degrees -= 360.0;
        while (degrees < -180.0) degrees += 360.0;
        return degrees;
    }

    public static float normalizeDegrees(float degrees) {
        return (float) normalizeDegrees((double) degrees);
    }

    public static double normalizeRadians(double radians) {
        while (radians >= Math.PI) radians -= TwoPi;
        while (radians < -Math.PI) radians += TwoPi;
        return radians;
    }

    public static float normalizeRadians(float radians) {
        return (float) normalizeRadians((double) radians);
    }

    public UnnormalizedAngleUnit getUnnormalized() {
        return this == DEGREES ? UnnormalizedAngleUnit.DEGREES : UnnormalizedAngleUnit.RADIANS;
    }
}
