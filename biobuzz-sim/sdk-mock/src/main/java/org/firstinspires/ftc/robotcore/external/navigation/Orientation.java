package org.firstinspires.ftc.robotcore.external.navigation;

/**
 * Three angles plus a description of how to interpret them.
 * The simulator only supports toAngleUnit(); converting between axis orders
 * isn't mocked (the SDK checker will tell you if our code needs it).
 */
public class Orientation {
    public AxesReference axesReference;
    public AxesOrder axesOrder;
    public AngleUnit angleUnit;
    public float firstAngle;
    public float secondAngle;
    public float thirdAngle;
    public long acquisitionTime;

    public Orientation() {
        this(AxesReference.EXTRINSIC, AxesOrder.XYZ, AngleUnit.RADIANS, 0, 0, 0, 0);
    }

    public Orientation(AxesReference axesReference, AxesOrder axesOrder, AngleUnit angleUnit,
                       float firstAngle, float secondAngle, float thirdAngle, long acquisitionTime) {
        this.axesReference = axesReference;
        this.axesOrder = axesOrder;
        this.angleUnit = angleUnit;
        this.firstAngle = firstAngle;
        this.secondAngle = secondAngle;
        this.thirdAngle = thirdAngle;
        this.acquisitionTime = acquisitionTime;
    }

    public Orientation toAngleUnit(AngleUnit unit) {
        if (unit == angleUnit) {
            return this;
        }
        return new Orientation(axesReference, axesOrder, unit,
                unit.fromUnit(angleUnit, firstAngle),
                unit.fromUnit(angleUnit, secondAngle),
                unit.fromUnit(angleUnit, thirdAngle),
                acquisitionTime);
    }

    @Override
    public String toString() {
        return String.format("{%s %s %.3f %.3f %.3f}", axesReference, axesOrder, firstAngle, secondAngle, thirdAngle);
    }
}
