package org.biobuzz.sim.robot;

import org.biobuzz.sim.physics.MotorState;

/**
 * Turns the four drive motors' speeds into robot motion.
 *
 * HOW MECANUM WORKS: each wheel's rollers sit at 45 degrees, so a spinning
 * wheel pushes both forward and sideways. Adding the four wheels together
 * cancels the sideways parts when all spin the same way (drive straight),
 * and cancels the forward parts when diagonal pairs spin opposite ways
 * (strafe). These are the standard "forward kinematics" equations.
 *
 * STAGE 1: wheels never slip; strafing is simply scaled by strafeEfficiency.
 * Stage 2 replaces this with forces, friction and real slip.
 */
public final class MecanumDrivetrain {
    public final MotorState frontLeft;
    public final MotorState backLeft;
    public final MotorState frontRight;
    public final MotorState backRight;

    /**
     * +1 or -1 for each motor: -1 when the motor is physically mirrored, so
     * positive motor rotation rolls that wheel BACKWARD.
     * Order: frontLeft, backLeft, frontRight, backRight.
     */
    public final double[] mountSign;

    private final double wheelRadius;
    /** Half the wheelbase plus half the track width (lx + ly), used for turning. */
    private final double turnLever;
    private final double strafeEfficiency;
    private final double responseSeconds;

    public MecanumDrivetrain(MotorState fl, MotorState bl, MotorState fr, MotorState br, double[] mountSign,
                             double wheelRadius, double trackWidth, double wheelBase,
                             double strafeEfficiency, double responseSeconds) {
        this.frontLeft = fl;
        this.backLeft = bl;
        this.frontRight = fr;
        this.backRight = br;
        this.mountSign = mountSign.clone();
        this.wheelRadius = wheelRadius;
        this.turnLever = trackWidth / 2 + wheelBase / 2;
        this.strafeEfficiency = strafeEfficiency;
        this.responseSeconds = responseSeconds;
    }

    public MotorState[] motors() {
        return new MotorState[] {frontLeft, backLeft, frontRight, backRight};
    }

    /** Wheel speed in rad/s, positive = rolling the robot forward. */
    public double wheelSpeed(int index) {
        return motors()[index].velocityRadPerSec * mountSign[index];
    }

    /** Advances the motors and moves the robot by one time step. */
    public void step(SimRobot robot, double dt) {
        for (MotorState m : motors()) {
            m.stepSimple(dt, responseSeconds);
        }
        double wFL = wheelSpeed(0);
        double wBL = wheelSpeed(1);
        double wFR = wheelSpeed(2);
        double wBR = wheelSpeed(3);

        // Robot-frame velocities (+x forward, +y left, +omega counter-clockwise).
        double forward = wheelRadius / 4.0 * (wFL + wFR + wBL + wBR);
        double left = wheelRadius / 4.0 * (-wFL + wFR + wBL - wBR) * strafeEfficiency;
        double omega = wheelRadius / (4.0 * turnLever) * (-wFL + wFR - wBL + wBR);

        // Rotate into the field frame.
        double c = Math.cos(robot.heading);
        double s = Math.sin(robot.heading);
        robot.vx = forward * c - left * s;
        robot.vy = forward * s + left * c;
        robot.omega = omega;

        robot.x += robot.vx * dt;
        robot.y += robot.vy * dt;
        robot.heading += robot.omega * dt;
    }
}
