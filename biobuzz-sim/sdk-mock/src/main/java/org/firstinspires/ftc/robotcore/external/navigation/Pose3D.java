package org.firstinspires.ftc.robotcore.external.navigation;

/** A position plus an orientation. */
public class Pose3D {
    protected final Position position;
    protected final YawPitchRollAngles orientation;

    public Pose3D(Position position, YawPitchRollAngles orientation) {
        this.position = position;
        this.orientation = orientation;
    }

    public Position getPosition() {
        return position;
    }

    public YawPitchRollAngles getOrientation() {
        return orientation;
    }

    @Override
    public String toString() {
        return "position=" + position + " orientation=" + orientation;
    }
}
