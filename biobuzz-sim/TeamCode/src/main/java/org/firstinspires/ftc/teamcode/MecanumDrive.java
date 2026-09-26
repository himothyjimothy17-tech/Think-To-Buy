package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.DcMotorEx;

/**
 * Turns "drive forward / strafe / turn" commands into four wheel powers.
 *
 * Sign conventions (robot's point of view):
 *   forward > 0  drives toward the intake (front) of the robot
 *   strafe  > 0  slides to the RIGHT
 *   turn    > 0  turns CLOCKWISE (to the right)
 */
public class MecanumDrive {

    private final DcMotorEx frontLeft;
    private final DcMotorEx backLeft;
    private final DcMotorEx frontRight;
    private final DcMotorEx backRight;

    /** The last powers sent, in the order frontLeft, backLeft, frontRight, backRight. */
    private final double[] lastPowers = new double[4];

    public MecanumDrive(RobotHardware robot) {
        this.frontLeft = robot.frontLeft;
        this.backLeft = robot.backLeft;
        this.frontRight = robot.frontRight;
        this.backRight = robot.backRight;
    }

    /**
     * Robot-centric drive: pushing the stick forward always drives the robot
     * toward its own front, whichever way it is facing.
     */
    public void drive(double forward, double strafe, double turn) {
        // Each mecanum wheel's rollers push at 45 degrees, so strafing uses
        // the diagonal pairs (frontLeft+backRight vs backLeft+frontRight).
        double fl = forward + strafe + turn;
        double bl = forward - strafe + turn;
        double fr = forward - strafe - turn;
        double br = forward + strafe - turn;

        // If any power is above 1, scale them all down together so the robot
        // still moves in the direction you asked for.
        double max = Math.max(1.0, Math.max(Math.max(Math.abs(fl), Math.abs(bl)),
                Math.max(Math.abs(fr), Math.abs(br))));
        setPowers(fl / max, bl / max, fr / max, br / max);
    }

    /**
     * Field-centric drive: pushing the stick forward always drives AWAY from
     * the driver, whichever way the robot is facing.
     *
     * @param headingRadians robot heading from the IMU (counter-clockwise positive)
     */
    public void driveFieldCentric(double forward, double strafe, double turn, double headingRadians) {
        // Rotate the joystick direction by the opposite of the robot's heading
        // to get the direction in the robot's own frame.
        double cos = Math.cos(-headingRadians);
        double sin = Math.sin(-headingRadians);
        double robotStrafe = strafe * cos - forward * sin;
        double robotForward = strafe * sin + forward * cos;
        drive(robotForward, robotStrafe, turn);
    }

    public void stop() {
        setPowers(0, 0, 0, 0);
    }

    private void setPowers(double fl, double bl, double fr, double br) {
        frontLeft.setPower(fl);
        backLeft.setPower(bl);
        frontRight.setPower(fr);
        backRight.setPower(br);
        lastPowers[0] = fl;
        lastPowers[1] = bl;
        lastPowers[2] = fr;
        lastPowers[3] = br;
    }

    /** Last powers sent (frontLeft, backLeft, frontRight, backRight) - handy for telemetry. */
    public double[] getLastPowers() {
        return lastPowers.clone();
    }
}
