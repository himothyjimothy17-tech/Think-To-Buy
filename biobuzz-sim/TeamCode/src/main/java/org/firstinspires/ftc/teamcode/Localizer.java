package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;

/**
 * Keeps track of where the robot is on the field (x, y in inches, heading).
 *
 * HOW:
 *  1. Wheel odometry: the 4 drive encoders tell how far each wheel turned.
 *     Mecanum math turns that into "moved forward / sideways" in the robot's
 *     frame; the IMU heading turns it into field x / y.
 *  2. The IMU gives the heading (much better than wheel math for turning).
 *  3. AprilTags: when the Limelight sees the tags under the HIVE and we're
 *     nearly stopped, we pull the estimate toward the camera's answer
 *     (MegaTag "botpose"). This removes the drift from wheel slip.
 *
 * Without dead wheels, mecanum odometry slips when we accelerate hard -
 * step 3 is what keeps the AUTO accurate. (A goBILDA Pinpoint would be better.)
 */
public class Localizer {

    // goBILDA 5203 435 RPM: 384.5 ticks/rev; 104 mm mecanum wheels.
    public static double TICKS_PER_REV = 384.5;
    public static double WHEEL_DIAMETER_IN = 104.0 / 25.4;
    /** Sideways distance per wheel turn vs forward (rollers slip a little). Tune by strafing 48 in. */
    public static double LATERAL_MULTIPLIER = 1.0;
    /** How hard to pull toward the camera's pose each update (0 = ignore camera, 1 = trust it fully). */
    public static double VISION_GAIN = 0.25;
    /** Only use the camera when slower than this (motion blur, latency). */
    public static double VISION_MAX_SPEED_IN_S = 12;
    public static double VISION_MAX_TURN_DEG_S = 30;

    private final DcMotorEx[] wheels; // FL, BL, FR, BR
    private final RobotHardware robot;
    private final int[] lastTicks = new int[4];
    private double x;
    private double y;
    private double headingOffset;
    private double heading;
    private double vx;
    private double vy;
    private double omega;
    /** ElapsedTime (not System.nanoTime) so the simulator's clock drives it too. */
    private final ElapsedTime clock = new ElapsedTime();
    private double lastSeconds;
    private int visionFixes;

    public Localizer(RobotHardware robot) {
        this.robot = robot;
        this.wheels = robot.driveMotors();
    }

    /** Tells the localizer where the robot starts (call once, right after START). */
    public void setPose(double xIn, double yIn, double headingRad) {
        for (int i = 0; i < 4; i++) {
            lastTicks[i] = wheels[i].getCurrentPosition();
        }
        x = xIn;
        y = yIn;
        headingOffset = headingRad - imuYaw();
        heading = headingRad;
        lastSeconds = clock.seconds();
    }

    private double imuYaw() {
        return robot.imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.RADIANS);
    }

    /** Call once per loop. */
    public void update() {
        double now = clock.seconds();
        double dt = Math.max(1e-3, now - lastSeconds);
        lastSeconds = now;
        int[] t = new int[4];
        double[] d = new double[4];
        double inPerTick = Math.PI * WHEEL_DIAMETER_IN / TICKS_PER_REV;
        for (int i = 0; i < 4; i++) {
            t[i] = wheels[i].getCurrentPosition();
            d[i] = (t[i] - lastTicks[i]) * inPerTick;
            lastTicks[i] = t[i];
        }
        // Mecanum forward kinematics (see MecanumDrive for the wheel formulas):
        double forward = (d[0] + d[1] + d[2] + d[3]) / 4.0;
        double right = (d[0] - d[1] - d[2] + d[3]) / 4.0 * LATERAL_MULTIPLIER;
        double newHeading = imuYaw() + headingOffset;
        double mid = heading + angleWrap(newHeading - heading) / 2; // use the average heading over the step
        double c = Math.cos(mid);
        double s = Math.sin(mid);
        double dx = forward * c + right * s;   // right = -left; left direction is (-sin, cos)
        double dy = forward * s - right * c;
        x += dx;
        y += dy;
        omega = angleWrap(newHeading - heading) / dt;
        heading = newHeading;
        vx = dx / dt;
        vy = dy / dt;
        clampToField();
    }

    /**
     * The robot can't be past a wall. If odometry says it is (wheels slipped
     * while we pushed into the wall), pull the estimate back. Pushing into a
     * wall on purpose therefore RE-ZEROES that axis - a classic FTC trick.
     */
    private void clampToField() {
        double c = Math.abs(Math.cos(heading));
        double s = Math.abs(Math.sin(heading));
        double ex = c * FieldConstants.ROBOT_LENGTH / 2 + s * FieldConstants.ROBOT_WIDTH / 2;
        double ey = s * FieldConstants.ROBOT_LENGTH / 2 + c * FieldConstants.ROBOT_WIDTH / 2;
        double lim = FieldConstants.HALF_FIELD;
        x = Math.max(-lim + ex, Math.min(lim - ex, x));
        y = Math.max(-lim + ey, Math.min(lim - ey, y));
    }

    /**
     * Blends in the Limelight's AprilTag pose if it's fresh and we're slow.
     * Returns true if it was used.
     */
    public boolean addVision(LLResult r) {
        if (r == null || !r.isValid() || r.getBotposeTagCount() == 0) {
            return false;
        }
        if (Math.hypot(vx, vy) > VISION_MAX_SPEED_IN_S || Math.abs(Math.toDegrees(omega)) > VISION_MAX_TURN_DEG_S) {
            return false;
        }
        if (r.getStaleness() > 100) {
            return false;
        }
        Pose3D p = r.getBotpose();
        if (p == null) {
            return false;
        }
        double cx = p.getPosition().x / 0.0254; // Limelight reports metres
        double cy = p.getPosition().y / 0.0254;
        if (Math.hypot(cx - x, cy - y) > 24) {
            return false; // wildly different: probably a bad detection
        }
        x += (cx - x) * VISION_GAIN;
        y += (cy - y) * VISION_GAIN;
        visionFixes++;
        return true;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getHeading() {
        return heading;
    }

    /** Field-frame speed in in/s. */
    public double getSpeed() {
        return Math.hypot(vx, vy);
    }

    public double getOmega() {
        return omega;
    }

    public int getVisionFixes() {
        return visionFixes;
    }

    public static double angleWrap(double a) {
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }
}
