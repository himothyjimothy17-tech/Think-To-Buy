package org.firstinspires.ftc.teamcode;

/**
 * Where things are on the BIOBUZZ field, for the RED alliance, in inches.
 *
 * COORDINATES (the FTC field frame): origin at the field center, +x toward
 * the BLUE alliance wall, +y away from the audience, heading 0 = facing +x,
 * counter-clockwise positive. (Manual §9.4.)
 *
 * BLUE is the same field turned 180 degrees, so we only write RED numbers
 * and {@link #forAlliance} rotates them: (x, y, h) -> (-x, -y, h + 180).
 *
 * VERIFY every number on the real field before an event - these come from
 * the manual's drawings, and a few are ESTIMATES (marked).
 */
public final class FieldConstants {

    private FieldConstants() {
    }

    /** A point with a heading (inches, radians). */
    public static final class Pose {
        public final double x;
        public final double y;
        public final double heading;

        public Pose(double x, double y, double headingRad) {
            this.x = x;
            this.y = y;
            this.heading = headingRad;
        }

        public static Pose deg(double x, double y, double headingDeg) {
            return new Pose(x, y, Math.toRadians(headingDeg));
        }

        public double distanceTo(Pose o) {
            return Math.hypot(o.x - x, o.y - y);
        }

        @Override
        public String toString() {
            return String.format("(%.1f, %.1f, %.0f deg)", x, y, Math.toDegrees(heading));
        }
    }

    /** RED numbers -> this alliance (BLUE = rotated 180 degrees about the field center). */
    public static Pose forAlliance(Pose red, boolean blue) {
        return blue ? new Pose(-red.x, -red.y, red.heading + Math.PI) : red;
    }

    public static double[] forAlliance(double[] redXyz, boolean blue) {
        return blue ? new double[] {-redXyz[0], -redXyz[1], redXyz[2]} : redXyz;
    }

    // ---- The field (§9.2): 144 in square.
    public static final double HALF_FIELD = 72.0;

    // ---- Our robot (must match the real one)
    public static final double ROBOT_LENGTH = 17.0;   // ESTIMATE until built
    public static final double ROBOT_WIDTH = 17.0;    // ESTIMATE
    /** Height where the ball leaves the shooter, and how far in front of the center. */
    public static double SHOT_EXIT_HEIGHT = 15.0;     // ESTIMATE - measure on the robot
    public static double SHOT_EXIT_FORWARD = -1.0;    // ESTIMATE

    // ---- The red HIVE (§9.6). Aim points are the middle of each CELL's opening,
    // worked out from Figures 9-8 to 9-10: pivot 43.95 in up, arm tilted 30 deg,
    // opening at the arm's outer end. The HIVE hangs at x = -12.75 (half of the
    // 25.5 in pivot spacing). When the audience CELL is up its opening is here:
    public static final double[] AUDIENCE_CELL_OPENING = {-12.75, -16.1, 58.9};
    /** ...and after a TIP the far CELL is up, mirrored across the crossbar. */
    public static final double[] FAR_CELL_OPENING = {-12.75, 16.1, 58.9};

    // ---- Zones (§9.3)
    /** LOADING ZONE: x -72..-61, y 24..48 (PARK here). */
    public static final double LOADING_ZONE_X_MIN = -72;
    public static final double LOADING_ZONE_X_MAX = -61;
    public static final double LOADING_ZONE_Y_MIN = 24;
    public static final double LOADING_ZONE_Y_MAX = 48;
    /** GARDEN: along the audience wall, x -72..-49 (4 POLLEN staged in a line, §10.3.1). */
    public static final double GARDEN_X_MIN = -72;
    public static final double GARDEN_X_MAX = -49;

    // ---- FLOWERS on our side (§9.7, Fig 9-4): centers ~3 in off the wall (ESTIMATE).
    public static final double[] FAR_FLOWER = {-24, 69};
    public static final double[] WALL_FLOWER = {-69, -24};

    // ---- HIVE frame legs (§9.6.1: 49.46 x 38.95 in frame) - keep clear when driving.
    public static final double[][] HIVE_LEGS = {{-24.73, -19.48}, {-24.73, 19.48}, {24.73, -19.48}, {24.73, 19.48}};
}
