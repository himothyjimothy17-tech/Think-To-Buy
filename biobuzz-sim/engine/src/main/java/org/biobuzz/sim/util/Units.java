package org.biobuzz.sim.util;

/**
 * Unit conversions.
 *
 * RULE OF THUMB for this code base: the config files and the browser use
 * INCHES and DEGREES (like the game manual); the physics inside the engine
 * uses METERS, KILOGRAMS, SECONDS and RADIANS (so physics formulas like
 * F = m * a work without conversion factors).
 */
public final class Units {
    public static final double METERS_PER_INCH = 0.0254;
    public static final double KG_PER_POUND = 0.45359237;
    public static final double NANOS_PER_SECOND = 1e9;

    private Units() {
    }

    public static double inToM(double inches) {
        return inches * METERS_PER_INCH;
    }

    public static double mToIn(double meters) {
        return meters / METERS_PER_INCH;
    }

    public static double mmToM(double mm) {
        return mm / 1000.0;
    }

    public static double lbToKg(double pounds) {
        return pounds * KG_PER_POUND;
    }

    public static double rpmToRadPerSec(double rpm) {
        return rpm * 2.0 * Math.PI / 60.0;
    }

    public static double radPerSecToRpm(double radPerSec) {
        return radPerSec * 60.0 / (2.0 * Math.PI);
    }

    /** Wraps an angle into [-PI, PI). */
    public static double wrapRadians(double a) {
        while (a >= Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }
}
