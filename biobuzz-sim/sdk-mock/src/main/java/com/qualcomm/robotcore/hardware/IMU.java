package com.qualcomm.robotcore.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

/** The universal IMU interface (Control Hub's built-in IMU). */
public interface IMU extends HardwareDevice {

    /** Tells the IMU how the hub is mounted on the robot. */
    class Parameters {
        public ImuOrientationOnRobot imuOrientationOnRobot;

        public Parameters(ImuOrientationOnRobot imuOrientationOnRobot) {
            this.imuOrientationOnRobot = imuOrientationOnRobot;
        }

        public Parameters copy() {
            return new Parameters(imuOrientationOnRobot);
        }
    }

    boolean initialize(Parameters parameters);

    void resetYaw();

    YawPitchRollAngles getRobotYawPitchRollAngles();

    Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit);

    Quaternion getRobotOrientationAsQuaternion();

    AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit);
}
