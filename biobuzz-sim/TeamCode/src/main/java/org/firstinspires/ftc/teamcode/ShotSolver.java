package org.firstinspires.ftc.teamcode;

/**
 * Works out the flywheel RPM for a shot, INCLUDING air drag.
 *
 * WHY NOT THE TEXTBOOK FORMULA? The simple projectile formula ignores air,
 * and our balls have holes (lots of drag): at 5 m/s drag is about 30% of the
 * ball's weight. So we fly the ball in small time steps (2 ms), exactly like
 * a physics simulation, and search for the launch speed that passes through
 * the target point (bisection: too low -> go faster, too high -> slower).
 * It takes well under a millisecond, so it can run every loop.
 *
 * Then: launch speed = EXIT_SPEED_FACTOR x wheel surface speed, which gives
 * the RPM. EXIT_SPEED_FACTOR and the drag numbers are ESTIMATES until we
 * measure them on the real robot (shoot, film at 240 fps, measure speed).
 */
public final class ShotSolver {

    // Ball numbers (manual §9.8 for size; mass and drag are ESTIMATES).
    public static double POLLEN_DIAMETER_IN = 2.8;
    public static double POLLEN_MASS_KG = 0.023;
    public static double DRAG_COEFFICIENT = 0.5;
    /** NECTAR (§9.8: 3.6 in; mass ESTIMATE). Heavier for its size -> less slowed by air -> flies farther. */
    public static double NECTAR_DIAMETER_IN = 3.6;
    public static double NECTAR_MASS_KG = 0.045;
    private static final double AIR_DENSITY = 1.2;
    private static final double G = 9.81;
    private static final double IN = 0.0254;

    private ShotSolver() {
    }

    /**
     * Launch speed (m/s) to pass through a point {@code distanceIn} away
     * horizontally and {@code riseIn} above the exit, at {@code angleDeg}.
     * NaN if it can't be reached.
     */
    public static double launchSpeed(double distanceIn, double riseIn, double angleDeg) {
        return launchSpeed(distanceIn, riseIn, angleDeg, false);
    }

    /** Same, for a NECTAR ({@code nectar = true}) or a POLLEN. */
    public static double launchSpeed(double distanceIn, double riseIn, double angleDeg, boolean nectar) {
        double d = distanceIn * IN;
        double rise = riseIn * IN;
        double angle = Math.toRadians(angleDeg);
        double lo = 0.5;
        double hi = 15.0;
        if (heightAt(hi, angle, d, nectar) < rise) {
            return Double.NaN;
        }
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (heightAt(mid, angle, d, nectar) >= rise) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    /** Flywheel RPM for a shot (uses Shooter's wheel size and exit speed factor). */
    public static double rpm(double distanceIn, double riseIn, double angleDeg) {
        return rpm(distanceIn, riseIn, angleDeg, false);
    }

    /** Flywheel RPM for a NECTAR ({@code nectar = true}) or a POLLEN. */
    public static double rpm(double distanceIn, double riseIn, double angleDeg, boolean nectar) {
        double v = launchSpeed(distanceIn, riseIn, angleDeg, nectar);
        double wheelRadius = Shooter.WHEEL_DIAMETER_MM / 1000.0 / 2.0;
        return v / Shooter.EXIT_SPEED_FACTOR / wheelRadius * 60.0 / (2 * Math.PI);
    }

    /** Height of the ball (m, relative to the exit) when it has gone d metres sideways. */
    private static double heightAt(double speed, double angle, double d, boolean nectar) {
        double r = (nectar ? NECTAR_DIAMETER_IN : POLLEN_DIAMETER_IN) * IN / 2;
        double k = 0.5 * AIR_DENSITY * DRAG_COEFFICIENT * Math.PI * r * r / (nectar ? NECTAR_MASS_KG : POLLEN_MASS_KG);
        double vx = speed * Math.cos(angle);
        double vz = speed * Math.sin(angle);
        double x = 0;
        double z = 0;
        double dt = 0.002;
        for (int i = 0; i < 2000; i++) {
            double v = Math.sqrt(vx * vx + vz * vz);
            double f = Math.min(k * v * dt, 1.0);
            vx -= vx * f;
            vz -= vz * f + G * dt;
            double nx = x + vx * dt;
            if (nx >= d) {
                return z + vz * dt * (d - x) / (nx - x);
            }
            x = nx;
            z += vz * dt;
            if (z < -3) {
                break;
            }
        }
        return -1e9; // never got there
    }
}
