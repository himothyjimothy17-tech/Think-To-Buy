package org.biobuzz.sim.game;

import org.biobuzz.sim.util.FastMath;

/**
 * Flies a ball with the same gravity and air drag as {@link GameWorld}
 * (no collisions) and solves "how fast must I launch to hit that point?".
 *
 * Used by the robot AI. It's deliberately the same math a team could put in
 * their own robot code.
 */
public final class Ballistics {

    private static final double DT = 0.003;

    /** Where the ball is when it first reaches the target's horizontal distance. */
    public static final class Arrival {
        public boolean reached;
        public double z;
        public double vHoriz;
        public double vz;
        public double time;
    }

    private final double dragPerMass; // dragK * area / mass

    public Ballistics(double dragK, double radius, double mass) {
        this.dragPerMass = dragK * Math.PI * radius * radius / mass;
    }

    /** Launch at {@code speed} and {@code pitch} (rad) from height z0; track until horizontal distance d. */
    public Arrival fly(double speed, double pitch, double z0, double d) {
        double vh = speed * Math.cos(pitch);
        double vz = speed * Math.sin(pitch);
        double x = 0;
        double z = z0;
        double t = 0;
        Arrival a = new Arrival();
        while (t < 4 && z > -0.5) {
            double v = FastMath.hypot(vh, vz);
            double k = Math.min(dragPerMass * v * DT, 1.0);
            vh -= vh * k;
            vz -= vz * k;
            vz -= GameWorld.GRAVITY * DT;
            double nx = x + vh * DT;
            if (nx >= d) {
                double f = (d - x) / (nx - x);
                a.reached = true;
                a.z = z + vz * DT * f;
                a.vHoriz = vh;
                a.vz = vz;
                a.time = t + DT * f;
                return a;
            }
            x = nx;
            z += vz * DT;
            t += DT;
        }
        return a;
    }

    /**
     * Launch speed that passes through (d, zTarget) at this pitch, or NaN if
     * none up to maxSpeed. Faster = higher at the target, so bisection works.
     */
    public double speedFor(double pitch, double z0, double d, double zTarget, double maxSpeed) {
        double lo = 0.5;
        double hi = maxSpeed;
        Arrival top = fly(hi, pitch, z0, d);
        if (!top.reached || top.z < zTarget) {
            return Double.NaN;
        }
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            Arrival a = fly(mid, pitch, z0, d);
            if (a.reached && a.z >= zTarget) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }
}
