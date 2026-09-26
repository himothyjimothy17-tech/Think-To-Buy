package org.firstinspires.ftc.robotcore.external.navigation;

/** The order in which the three rotations are applied. */
public enum AxesOrder {
    XZX(0, 2, 0), XYX(0, 1, 0), YXY(1, 0, 1), YZY(1, 2, 1), ZYZ(2, 1, 2), ZXZ(2, 0, 2),
    XZY(0, 2, 1), XYZ(0, 1, 2), YXZ(1, 0, 2), YZX(1, 2, 0), ZYX(2, 1, 0), ZXY(2, 0, 1);

    private final int[] indices;

    AxesOrder(int a, int b, int c) {
        this.indices = new int[] {a, b, c};
    }

    public int[] indices() {
        return indices.clone();
    }

    public Axis[] axes() {
        return new Axis[] {Axis.fromIndex(indices[0]), Axis.fromIndex(indices[1]), Axis.fromIndex(indices[2])};
    }

    public AxesOrder reverse() {
        for (AxesOrder o : values()) {
            if (o.indices[0] == indices[2] && o.indices[1] == indices[1] && o.indices[2] == indices[0]) {
                return o;
            }
        }
        return this;
    }
}
