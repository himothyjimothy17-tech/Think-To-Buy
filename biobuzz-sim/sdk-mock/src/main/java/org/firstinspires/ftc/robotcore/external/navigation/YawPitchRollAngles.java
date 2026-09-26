package org.firstinspires.ftc.robotcore.external.navigation;

/**
 * The robot's orientation as yaw (turning left/right), pitch and roll.
 * Yaw is positive when the robot turns counter-clockwise (left), seen from above.
 */
public class YawPitchRollAngles {
    private final AngleUnit angleUnit;
    private final double yaw;
    private final double pitch;
    private final double roll;
    private final long acquisitionTime;

    public YawPitchRollAngles(AngleUnit angleUnit, double yaw, double pitch, double roll, long acquisitionTime) {
        this.angleUnit = angleUnit;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.acquisitionTime = acquisitionTime;
    }

    /** @deprecated the unit is ambiguous; use getYaw(AngleUnit). */
    @Deprecated
    public double getYaw() {
        return yaw;
    }

    public double getYaw(AngleUnit unit) {
        return unit.fromUnit(angleUnit, yaw);
    }

    /** @deprecated use getPitch(AngleUnit). */
    @Deprecated
    public double getPitch() {
        return pitch;
    }

    public double getPitch(AngleUnit unit) {
        return unit.fromUnit(angleUnit, pitch);
    }

    /** @deprecated use getRoll(AngleUnit). */
    @Deprecated
    public double getRoll() {
        return roll;
    }

    public double getRoll(AngleUnit unit) {
        return unit.fromUnit(angleUnit, roll);
    }

    public long getAcquisitionTime() {
        return acquisitionTime;
    }

    @Override
    public String toString() {
        return String.format("{yaw=%.3f, pitch=%.3f, roll=%.3f}", yaw, pitch, roll);
    }
}
