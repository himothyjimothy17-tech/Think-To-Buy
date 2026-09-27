package org.biobuzz.sim.robot;

import org.biobuzz.sim.physics.MotorState;

import java.util.Random;

/**
 * Force-based mecanum drivetrain physics.
 *
 * HOW A MECANUM WHEEL PUSHES (worth explaining to judges):
 * Each wheel is covered in free-spinning rollers at 45 degrees. A roller can
 * spin freely along its own axis, so the wheel can only push the floor at
 * 90 degrees to the roller ("traction direction"). On an X-pattern chassis
 * the four traction directions point diagonally; adding the four pushes gives
 * forward, sideways or turning motion depending on which wheels spin which way.
 *
 * WHAT THIS MODEL INCLUDES
 *  - Real motor torque curves and battery voltage (via MotorState).
 *  - Friction with the tiles along each wheel's traction direction. If a
 *    wheel spins faster than the ground under it moves, it SLIPS and the force
 *    is capped at mu x normal force (wheel spin on hard acceleration, pushing
 *    a wall).
 *  - Roller resistance: when the robot strafes, the rollers must spin, and
 *    their bearings resist. That's why real mecanum robots strafe slower and
 *    less straight than they drive forward.
 *  - Slightly different grip on each wheel (random per seed), so the robot
 *    drifts a little like a real one. Encoder-only driving is never perfect.
 *
 * The physics runs in small sub-steps because wheel slip reacts very fast.
 */
public final class MecanumDrivetrain {
    public final MotorState frontLeft;
    public final MotorState backLeft;
    public final MotorState frontRight;
    public final MotorState backRight;

    /** -1 when the motor is physically mirrored. Order: FL, BL, FR, BR. */
    public final double[] mountSign;

    /** Parameters (all SI units). */
    public static final class Params {
        public double massKg;
        public double lengthM;
        public double widthM;
        public double wheelRadiusM;
        public double trackWidthM;
        public double wheelBaseM;
        public double wheelInertia;       // kg*m^2, wheel + gearbox + rotor (reflected)
        public double frictionCoeff;      // tile grip along the traction direction
        public double rollerResistance;   // resistance along the roller axis, as a friction coefficient
        public double rollingResistance;  // tire/tile rolling drag coefficient
        public double slipSpeed;          // m/s: slip needed to reach full friction (smooths the math)
        public double gripVariation;      // e.g. 0.03 = each wheel's grip varies +/-3%
    }

    private static final int SUBSTEPS = 4;
    private static final double GRAVITY = 9.81;

    private final Params p;
    /** Wheel positions in the robot frame (x forward, y left). Order FL, BL, FR, BR. */
    private final double[][] wheelPos;
    /** Traction direction of each wheel's rollers (unit vectors, robot frame). */
    private final double[][] traction;
    /** Roller axis of each wheel (perpendicular to traction). */
    private final double[][] rollerAxis;
    private final double[] grip = new double[4];
    private final double inertiaYaw;
    /** Wheel angular speeds (rad/s, positive = rolling forward). */
    private final double[] wheelSpeed = new double[4];
    /** Last traction force per wheel (N) - handy for debugging and slip display. */
    public final double[] wheelSlip = new double[4];

    public MecanumDrivetrain(MotorState fl, MotorState bl, MotorState fr, MotorState br, double[] mountSign,
                             Params params, Random rng) {
        this.frontLeft = fl;
        this.backLeft = bl;
        this.frontRight = fr;
        this.backRight = br;
        this.mountSign = mountSign.clone();
        this.p = params;
        double hx = params.wheelBaseM / 2;
        double hy = params.trackWidthM / 2;
        wheelPos = new double[][] {{hx, hy}, {-hx, hy}, {hx, -hy}, {-hx, -hy}};
        double s = Math.sqrt(0.5);
        // Standard "X" layout: FL and BR push toward front-right when spinning forward;
        // BL and FR push toward front-left. (This is why strafe right = FL+, BL-, FR-, BR+.)
        traction = new double[][] {{s, -s}, {s, s}, {s, s}, {s, -s}};
        rollerAxis = new double[][] {{s, s}, {s, -s}, {s, -s}, {s, s}};
        for (int i = 0; i < 4; i++) {
            grip[i] = params.frictionCoeff * (1.0 + params.gripVariation * (2 * rng.nextDouble() - 1));
        }
        inertiaYaw = params.massKg * (params.lengthM * params.lengthM + params.widthM * params.widthM) / 12.0;
    }

    public MotorState[] motors() {
        return new MotorState[] {frontLeft, backLeft, frontRight, backRight};
    }

    /** Wheel speed in rad/s, positive = rolling the robot forward. */
    public double wheelSpeed(int index) {
        return wheelSpeed[index];
    }

    /** Advances the drivetrain and the robot by one time step. */
    public void step(SimRobot robot, double dt, double batteryVolts) {
        MotorState[] motors = motors();
        double h = dt / SUBSTEPS;
        double normal = p.massKg * GRAVITY / 4.0;
        for (int sub = 0; sub < SUBSTEPS; sub++) {
            double c = Math.cos(robot.heading);
            double s = Math.sin(robot.heading);
            // Robot velocity in its own frame.
            double u = robot.vx * c + robot.vy * s;
            double v = -robot.vx * s + robot.vy * c;
            double w = robot.omega;

            double fx = 0;
            double fy = 0;
            double torque = 0;
            for (int i = 0; i < 4; i++) {
                MotorState m = motors[i];
                // The wheel and motor turn together (through the mounting).
                m.velocityRadPerSec = wheelSpeed[i] * mountSign[i];
                double wheelTorque = m.torque(batteryVolts) * mountSign[i];

                double px = wheelPos[i][0];
                double py = wheelPos[i][1];
                // Velocity of the floor point under this wheel (robot frame).
                double gx = u - w * py;
                double gy = v + w * px;
                double[] t = traction[i];
                double[] a = rollerAxis[i];
                double surface = wheelSpeed[i] * p.wheelRadiusM; // wheel surface speed, forward
                // Slip along the traction direction: ground speed minus wheel push speed.
                double slip = (gx * t[0] + gy * t[1]) - surface * t[0];
                double ft = -grip[i] * normal * Math.tanh(slip / p.slipSpeed);
                // Rollers spinning (strafing) resist a little.
                double rollerSlide = (gx - surface) * a[0] + gy * a[1];
                double fa = -p.rollerResistance * normal * Math.tanh(rollerSlide / p.slipSpeed);
                // Rolling resistance opposes the robot's motion at this wheel.
                double speed = Math.hypot(gx, gy);
                double frx = speed > 1e-6 ? -p.rollingResistance * normal * gx / speed * Math.tanh(speed / p.slipSpeed) : 0;
                double fry = speed > 1e-6 ? -p.rollingResistance * normal * gy / speed * Math.tanh(speed / p.slipSpeed) : 0;

                double wfx = ft * t[0] + fa * a[0] + frx;
                double wfy = ft * t[1] + fa * a[1] + fry;
                fx += wfx;
                fy += wfy;
                torque += px * wfy - py * wfx;
                wheelSlip[i] = slip;

                // Newton's third law: the floor pushes the robot forward with ft, so it
                // pushes the wheel's rim backward just as hard (slowing the wheel).
                double contactTorque = -ft * t[0] * p.wheelRadiusM;
                wheelSpeed[i] += (wheelTorque + contactTorque) / p.wheelInertia * h;
                m.angleRad += wheelSpeed[i] * mountSign[i] * h;
            }
            // Forces back to the field frame, then Newton's second law.
            double ffx = fx * c - fy * s;
            double ffy = fx * s + fy * c;
            robot.vx += ffx / p.massKg * h;
            robot.vy += ffy / p.massKg * h;
            robot.omega += torque / inertiaYaw * h;
            robot.x += robot.vx * h;
            robot.y += robot.vy * h;
            robot.heading += robot.omega * h;
        }
        for (int i = 0; i < 4; i++) {
            motors[i].velocityRadPerSec = wheelSpeed[i] * mountSign[i];
        }
    }
}
