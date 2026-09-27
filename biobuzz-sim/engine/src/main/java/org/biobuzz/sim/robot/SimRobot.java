package org.biobuzz.sim.robot;

import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.geom.Pose2d;

/**
 * One robot on the field: its size, where it is and how fast it's moving.
 * All values in METERS, RADIANS and SECONDS, in the FIELD frame.
 *
 * Our robot also has a {@link MecanumDrivetrain}; the other three robots get
 * simple AI in stage 6 (for now they stand still at their start positions).
 */
public final class SimRobot {
    public final String id;
    public final Alliance alliance;
    public final boolean ours;
    /** Front-to-back size (along the robot's +x). */
    public final double length;
    /** Side-to-side size. */
    public final double width;
    public final double height;

    public double x;
    public double y;
    public double heading;
    public double vx;
    public double vy;
    public double omega;
    /** Mass for robot-robot pushing (kg). */
    public double massKg = 13.6;
    /** An anchored robot can't be pushed (used when the AI is turned off: it just sits there). */
    public boolean anchored;
    /**
     * Where the robot is TRYING to go (field frame, m/s) - from its wheel speeds
     * or its AI. Differs from vx/vy when it is pushing against something (G421 pins).
     */
    public double intentVx;
    public double intentVy;

    /** Only set for our robot. */
    public MecanumDrivetrain drivetrain;

    public SimRobot(String id, Alliance alliance, boolean ours, double length, double width, double height, Pose2d start) {
        this.id = id;
        this.alliance = alliance;
        this.ours = ours;
        this.length = length;
        this.width = width;
        this.height = height;
        setPose(start);
    }

    public void setPose(Pose2d p) {
        x = p.x;
        y = p.y;
        heading = p.heading;
        vx = 0;
        vy = 0;
        omega = 0;
    }

    public Pose2d pose() {
        return new Pose2d(x, y, heading);
    }

    /** Field-frame X/Y of this robot's four corners: front-left, front-right, back-right, back-left. */
    public double[][] corners() {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        double hl = length / 2;
        double hw = width / 2;
        double[][] local = {{hl, hw}, {hl, -hw}, {-hl, -hw}, {-hl, hw}};
        double[][] out = new double[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = x + local[i][0] * c - local[i][1] * s;
            out[i][1] = y + local[i][0] * s + local[i][1] * c;
        }
        return out;
    }
}
