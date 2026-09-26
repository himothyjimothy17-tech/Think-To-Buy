package org.firstinspires.ftc.robotcore.external.navigation;

/** Whether rotations are about fixed (EXTRINSIC) or moving (INTRINSIC) axes. */
public enum AxesReference {
    EXTRINSIC, INTRINSIC;

    public AxesReference reverse() {
        return this == EXTRINSIC ? INTRINSIC : EXTRINSIC;
    }
}
