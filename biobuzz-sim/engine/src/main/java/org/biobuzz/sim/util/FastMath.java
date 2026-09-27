package org.biobuzz.sim.util;

/**
 * Math.hypot is exact even for huge numbers but slow (it was the simulator's
 * #1 cost). Field-sized numbers can't overflow, so plain sqrt(x^2 + y^2) is fine.
 */
public final class FastMath {

    private FastMath() {
    }

    public static double hypot(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }
}
