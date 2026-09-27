package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/**
 * The simplest legal AUTO: drive off the wall and stop (LEAVE, 3 points).
 * It's the baseline every smarter auto is compared against.
 */
@Autonomous(name = "Leave Auto", group = "baseline")
public class LeaveAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        RobotHardware robot = new RobotHardware();
        robot.init(hardwareMap);
        waitForStart();
        for (DcMotorEx m : robot.driveMotors()) {
            m.setPower(0.3);
        }
        sleep(500);
        for (DcMotorEx m : robot.driveMotors()) {
            m.setPower(0);
        }
    }
}
