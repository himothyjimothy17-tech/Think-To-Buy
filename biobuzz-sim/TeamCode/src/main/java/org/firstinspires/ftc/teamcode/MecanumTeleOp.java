package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/**
 * Driver-controlled OpMode for our mecanum robot.
 *
 * GAMEPAD 1 (Logitech F310, X mode):
 *   Left stick      drive (forward/back) and strafe (left/right)
 *   Right stick X   turn
 *   Right bumper    hold for slow mode (precise lining up)
 *   Y               switch between field-centric and robot-centric
 *   Back            reset the heading (face away from the drivers, then press)
 *   Left trigger    intake IN (auto-clears jams)
 *   Left bumper     intake OUT
 *   A               flywheel on / off
 *   D-pad up/down   flywheel target +/- 100 RPM
 *   Right trigger   FIRE (opens the gate whenever the flywheel is at speed)
 */
@TeleOp(name = "Mecanum TeleOp", group = "Competition")
public class MecanumTeleOp extends LinearOpMode {

    /** Speed multiplier while the right bumper is held. */
    private static final double SLOW_MODE_SCALE = 0.4;

    /** Stick values smaller than this are treated as 0 (sticks rarely rest exactly at 0). */
    private static final double STICK_DEADBAND = 0.05;

    /** Starting flywheel speed; adjust with the d-pad. */
    private static final double DEFAULT_RPM = 3300;
    private static final double RPM_STEP = 100;

    @Override
    public void runOpMode() {
        RobotHardware robot = new RobotHardware();
        robot.init(hardwareMap);
        MecanumDrive drive = new MecanumDrive(robot);
        Intake intake = new Intake(robot);
        Shooter shooter = new Shooter(robot);
        boolean flywheelOn = false;
        double targetRpm = DEFAULT_RPM;

        boolean fieldCentric = true;
        ElapsedTime loopTimer = new ElapsedTime();

        telemetry.addLine("Ready. Press START.");
        telemetry.update();
        waitForStart();
        robot.imu.resetYaw();

        while (opModeIsActive()) {
            robot.clearBulkCache(); // fresh encoder values for this loop

            // Sticks: pushing UP gives a NEGATIVE y value, so we flip it.
            double forward = deadband(-gamepad1.left_stick_y);
            double strafe = deadband(gamepad1.left_stick_x);
            double turn = deadband(gamepad1.right_stick_x);

            if (gamepad1.right_bumper) {
                forward *= SLOW_MODE_SCALE;
                strafe *= SLOW_MODE_SCALE;
                turn *= SLOW_MODE_SCALE;
            }
            if (gamepad1.yWasPressed()) {
                fieldCentric = !fieldCentric;
            }
            if (gamepad1.backWasPressed()) {
                robot.imu.resetYaw();
            }

            // ---- intake ----
            if (gamepad1.left_trigger > 0.3) {
                intake.intake();
            } else if (gamepad1.left_bumper) {
                intake.outtake();
            } else {
                intake.stop();
            }

            // ---- shooter ----
            if (gamepad1.aWasPressed()) {
                flywheelOn = !flywheelOn;
            }
            if (gamepad1.dpadUpWasPressed()) {
                targetRpm += RPM_STEP;
            }
            if (gamepad1.dpadDownWasPressed()) {
                targetRpm -= RPM_STEP;
            }
            shooter.setTargetRpm(flywheelOn ? targetRpm : 0);
            if (gamepad1.right_trigger > 0.3 && flywheelOn) {
                shooter.fire();
            } else {
                shooter.closeGate();
            }

            double heading = robot.imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.RADIANS);
            if (fieldCentric) {
                drive.driveFieldCentric(forward, strafe, turn, heading);
            } else {
                drive.drive(forward, strafe, turn);
            }

            double[] p = drive.getLastPowers();
            telemetry.addData("Mode", fieldCentric ? "FIELD-centric (Y to switch)" : "ROBOT-centric (Y to switch)");
            telemetry.addData("Heading", "%.1f deg", Math.toDegrees(heading));
            telemetry.addData("Powers FL BL FR BR", "%.2f %.2f %.2f %.2f", p[0], p[1], p[2], p[3]);
            telemetry.addData("Encoders FL BL FR BR", "%d %d %d %d",
                    robot.frontLeft.getCurrentPosition(), robot.backLeft.getCurrentPosition(),
                    robot.frontRight.getCurrentPosition(), robot.backRight.getCurrentPosition());
            telemetry.addData("Flywheel", "%s  %.0f / %.0f RPM%s", flywheelOn ? "ON" : "off",
                    shooter.getRpm(), targetRpm, shooter.atSpeed() ? "  READY" : "");
            telemetry.addData("Intake", intake.isUnjamming() ? "UNJAMMING" : "ok (jams cleared: " + intake.getJamCount() + ")");
            telemetry.addData("Battery", "%.2f V", robot.batteryVoltage.getVoltage());
            telemetry.addData("Loop", "%.1f ms", loopTimer.milliseconds());
            loopTimer.reset();
            telemetry.update();
        }
        drive.stop();
        intake.stop();
        shooter.setTargetRpm(0);
    }

    private static double deadband(double value) {
        return Math.abs(value) < STICK_DEADBAND ? 0.0 : value;
    }
}
