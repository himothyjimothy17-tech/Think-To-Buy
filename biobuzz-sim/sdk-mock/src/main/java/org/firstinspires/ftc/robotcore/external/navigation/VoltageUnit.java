package org.firstinspires.ftc.robotcore.external.navigation;

/** Volts or millivolts. */
public enum VoltageUnit {
    VOLTS, MILLIVOLTS;

    public double toVolts(double v) {
        return this == VOLTS ? v : v / 1000.0;
    }

    public double toMilliVolts(double v) {
        return this == MILLIVOLTS ? v : v * 1000.0;
    }

    /** Converts {@code value} (in {@code unit}) into this unit. */
    public double convert(double value, VoltageUnit unit) {
        double volts = unit.toVolts(value);
        return this == VOLTS ? volts : volts * 1000.0;
    }
}
