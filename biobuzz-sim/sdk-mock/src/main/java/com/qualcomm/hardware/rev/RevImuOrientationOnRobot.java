package com.qualcomm.hardware.rev;

import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

/** Base class for REV IMU mounting descriptions. */
public abstract class RevImuOrientationOnRobot implements ImuOrientationOnRobot {
    private final Quaternion rotation;

    protected RevImuOrientationOnRobot(Quaternion rotation) {
        this.rotation = rotation;
    }

    @Override
    public Quaternion imuCoordinateSystemOrientationFromPerspectiveOfRobot() {
        return rotation;
    }

    @Override
    public Quaternion imuRotationOffset() {
        return rotation.inverse();
    }

    @Override
    public Quaternion angularVelocityTransform() {
        return rotation;
    }
}
