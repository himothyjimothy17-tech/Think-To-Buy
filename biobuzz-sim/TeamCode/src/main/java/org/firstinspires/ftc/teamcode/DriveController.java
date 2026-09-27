package org.firstinspires.ftc.teamcode;

/**
 * Drives the robot to a target pose (x, y, heading) on the field.
 *
 * The idea (a "go-to-point" controller, simpler than full path following):
 *  - Aim the robot's velocity straight at the target.
 *  - Speed = the most we can still stop from: v = sqrt(2 * decel * distance),
 *    capped at maxSpeed. That's a motion profile without planning one.
 *  - Feedforward: power = wanted speed / top speed (plus a small proportional
 *    fix for the leftover error). Mecanum strafes slower than it drives
 *    forward, so sideways power is scaled up by 1 / STRAFE_EFFICIENCY.
 *  - Heading: proportional control toward the target heading.
 */
public class DriveController {

    /** Top speed at full power (in/s). Measured in the sim: ~95 forward. ESTIMATE for the real robot. */
    public static double MAX_SPEED_IN_S = 90;
    /** Sideways speed / forward speed at the same power (sim: ~0.81). */
    public static double STRAFE_EFFICIENCY = 0.8;
    /** How hard we plan to brake (in/s^2). Lower = gentler, less wheel slip. */
    public static double DECEL_IN_S2 = 110;
    public static double KP_TRANSLATE = 0.06;   // power per inch of error
    public static double KP_HEADING = 1.6;      // power per radian of error
    public static double MAX_TURN_POWER = 0.8;
    public static double MIN_POWER = 0.06;      // overcomes static friction near the end
    /** The proportional part only trims the last few inches; it must never override the speed limit. */
    public static double MAX_P_POWER = 0.12;
    /**
     * How fast the commanded velocity may change (in/s^2). Asking for more than
     * the tires can give just spins the wheels - and spinning wheels ruin wheel
     * odometry (the encoders count distance the robot never moved).
     */
    public static double MAX_ACCEL_IN_S2 = 140;

    private final MecanumDrive drive;
    private final Localizer loc;
    private final com.qualcomm.robotcore.util.ElapsedTime clock = new com.qualcomm.robotcore.util.ElapsedTime();
    private double lastT;
    private double cmdVx;
    private double cmdVy;

    public DriveController(MecanumDrive drive, Localizer loc) {
        this.drive = drive;
        this.loc = loc;
    }

    /**
     * One control step toward (tx, ty, th). Returns the remaining distance (in).
     * maxSpeed limits the top speed (in/s) - e.g. slow down while intaking.
     */
    public double step(double tx, double ty, double th, double maxSpeed) {
        double ex = tx - loc.getX();
        double ey = ty - loc.getY();
        double dist = Math.hypot(ex, ey);
        double speed = Math.min(maxSpeed, Math.sqrt(2 * DECEL_IN_S2 * dist));
        double vxWant = dist > 1e-6 ? ex / dist * speed : 0;
        double vyWant = dist > 1e-6 ? ey / dist * speed : 0;
        // Slew-rate limit (acceleration only; braking comes from the sqrt profile above).
        double now = clock.seconds();
        double dt = Math.min(0.1, Math.max(1e-3, now - lastT));
        lastT = now;
        double dvx = vxWant - cmdVx;
        double dvy = vyWant - cmdVy;
        double dv = Math.hypot(dvx, dvy);
        double maxDv = MAX_ACCEL_IN_S2 * dt;
        boolean speedingUp = Math.hypot(vxWant, vyWant) > Math.hypot(cmdVx, cmdVy);
        if (speedingUp && dv > maxDv) {
            dvx *= maxDv / dv;
            dvy *= maxDv / dv;
        }
        cmdVx += dvx;
        cmdVy += dvy;
        double vxField = cmdVx;
        double vyField = cmdVy;
        // Field frame -> robot frame.
        double h = loc.getHeading();
        double c = Math.cos(h);
        double s = Math.sin(h);
        double vForward = vxField * c + vyField * s;
        double vLeft = -vxField * s + vyField * c;
        double eForward = ex * c + ey * s;
        double eLeft = -ex * s + ey * c;
        double pF = clamp(KP_TRANSLATE * eForward, MAX_P_POWER);
        double pL = clamp(KP_TRANSLATE * eLeft, MAX_P_POWER);
        double forward = vForward / MAX_SPEED_IN_S + pF;
        double left = (vLeft / MAX_SPEED_IN_S + pL) / STRAFE_EFFICIENCY;
        double herr = Localizer.angleWrap(th - h);
        double turnCcw = Math.max(-MAX_TURN_POWER, Math.min(MAX_TURN_POWER, KP_HEADING * herr));
        // A little minimum power so it doesn't stall a fraction of an inch short.
        double mag = Math.hypot(forward, left);
        if (dist > 0.5 && mag < MIN_POWER && mag > 1e-9) {
            forward *= MIN_POWER / mag;
            left *= MIN_POWER / mag;
        }
        // MecanumDrive: strafe > 0 = right, turn > 0 = clockwise.
        drive.drive(forward, -left, -turnCcw);
        return dist;
    }

    private static double clamp(double v, double lim) {
        return Math.max(-lim, Math.min(lim, v));
    }

    /**
     * Drives TOWARD a waypoint at a steady speed without braking for it - for
     * passing through waypoints on the way somewhere else. (step() brakes to
     * stop at its target, which wastes time at every intermediate waypoint.)
     */
    public double stepThrough(double tx, double ty, double th, double speed) {
        double ex = tx - loc.getX();
        double ey = ty - loc.getY();
        double dist = Math.hypot(ex, ey);
        // Put a virtual target far beyond the waypoint on the same line: no braking.
        double k = dist > 1e-6 ? (dist + 1000) / dist : 0;
        double saved = DECEL_IN_S2;
        DECEL_IN_S2 = 1e9;
        step(loc.getX() + ex * k, loc.getY() + ey * k, th, speed);
        DECEL_IN_S2 = saved;
        return dist;
    }

    /** Turn in place / hold position while aiming at heading th. */
    public double headingError(double th) {
        return Localizer.angleWrap(th - loc.getHeading());
    }

    public void stop() {
        drive.stop();
        cmdVx = 0;
        cmdVy = 0;
    }
}
