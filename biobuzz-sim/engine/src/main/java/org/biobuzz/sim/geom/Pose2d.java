package org.biobuzz.sim.geom;

import org.biobuzz.sim.util.Units;

/**
 * A position (x, y) in METERS and a heading in RADIANS on the field.
 * Heading 0 faces +x (toward the blue wall); counter-clockwise is positive.
 */
public final class Pose2d {
    public final double x;
    public final double y;
    public final double heading;

    public Pose2d(double x, double y, double heading) {
        this.x = x;
        this.y = y;
        this.heading = Units.wrapRadians(heading);
    }

    /**
     * The same spot for the OTHER alliance. BIOBUZZ's field is identical after
     * a 180-degree rotation around the center, so (x, y) becomes (-x, -y) and
     * the heading turns around.
     */
    public Pose2d rotate180() {
        return new Pose2d(-x, -y, heading + Math.PI);
    }

    @Override
    public String toString() {
        return String.format("(%.1f in, %.1f in, %.1f deg)",
                Units.mToIn(x), Units.mToIn(y), Math.toDegrees(heading));
    }
}
