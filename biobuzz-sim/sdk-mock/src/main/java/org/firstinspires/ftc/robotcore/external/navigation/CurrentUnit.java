package org.firstinspires.ftc.robotcore.external.navigation;

/** Amps or milliamps (for motor current readings). */
public enum CurrentUnit {
    AMPS, MILLIAMPS;

    public double toAmps(double current) {
        return this == AMPS ? current : current / 1000.0;
    }

    public double toMilliAmps(double current) {
        return this == MILLIAMPS ? current : current * 1000.0;
    }

    /** Converts {@code value} (in {@code unit}) into this unit. */
    public double convert(double value, CurrentUnit unit) {
        double amps = unit.toAmps(value);
        return this == AMPS ? amps : amps * 1000.0;
    }
}
