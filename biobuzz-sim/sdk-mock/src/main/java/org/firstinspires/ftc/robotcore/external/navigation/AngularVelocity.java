package org.firstinspires.ftc.robotcore.external.navigation;

/** Rotation rates around the X, Y and Z axes. zRotationRate is the turning rate. */
public class AngularVelocity {
    public UnnormalizedAngleUnit angleUnit;
    public float xRotationRate;
    public float yRotationRate;
    public float zRotationRate;
    public long acquisitionTime;

    public AngularVelocity() {
        this(AngleUnit.DEGREES, 0, 0, 0, 0);
    }

    public AngularVelocity(UnnormalizedAngleUnit unit, float xRate, float yRate, float zRate, long acquisitionTime) {
        this.angleUnit = unit;
        this.xRotationRate = xRate;
        this.yRotationRate = yRate;
        this.zRotationRate = zRate;
        this.acquisitionTime = acquisitionTime;
    }

    public AngularVelocity(AngleUnit unit, float xRate, float yRate, float zRate, long acquisitionTime) {
        this(unit.getUnnormalized(), xRate, yRate, zRate, acquisitionTime);
    }

    public AngularVelocity toAngleUnit(AngleUnit unit) {
        UnnormalizedAngleUnit target = unit.getUnnormalized();
        if (target == angleUnit) {
            return this;
        }
        return new AngularVelocity(target,
                (float) target.fromRadians(angleUnit.toRadians(xRotationRate)),
                (float) target.fromRadians(angleUnit.toRadians(yRotationRate)),
                (float) target.fromRadians(angleUnit.toRadians(zRotationRate)),
                acquisitionTime);
    }

    @Override
    public String toString() {
        return String.format("{x=%.3f, y=%.3f, z=%.3f}", xRotationRate, yRotationRate, zRotationRate);
    }
}
