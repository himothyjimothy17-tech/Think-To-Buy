package org.firstinspires.ftc.robotcore.external.navigation;

/** Meters, centimeters, millimeters or inches. */
public enum DistanceUnit {
    METER(0), CM(1), MM(2), INCH(3);

    public final byte bVal;
    public static final double infinity = Double.MAX_VALUE;
    public static final double mmPerInch = 25.4;
    public static final double mPerInch = mmPerInch * 0.001;

    DistanceUnit(int i) {
        bVal = (byte) i;
    }

    private double metersPerUnit() {
        switch (this) {
            case METER: return 1.0;
            case CM: return 0.01;
            case MM: return 0.001;
            default: return mPerInch;
        }
    }

    public double fromMeters(double meters) {
        return meters == infinity ? infinity : meters / metersPerUnit();
    }

    public double fromInches(double inches) {
        return inches == infinity ? infinity : fromMeters(inches * mPerInch);
    }

    public double fromCm(double cm) {
        return cm == infinity ? infinity : fromMeters(cm * 0.01);
    }

    public double fromMm(double mm) {
        return mm == infinity ? infinity : fromMeters(mm * 0.001);
    }

    public double fromUnit(DistanceUnit him, double his) {
        return his == infinity ? infinity : fromMeters(his * him.metersPerUnit());
    }

    public double toMeters(double inOurUnits) {
        return inOurUnits == infinity ? infinity : inOurUnits * metersPerUnit();
    }

    public double toInches(double inOurUnits) {
        return inOurUnits == infinity ? infinity : toMeters(inOurUnits) / mPerInch;
    }

    public double toCm(double inOurUnits) {
        return inOurUnits == infinity ? infinity : toMeters(inOurUnits) * 100.0;
    }

    public double toMm(double inOurUnits) {
        return inOurUnits == infinity ? infinity : toMeters(inOurUnits) * 1000.0;
    }

    public String toString(double inOurUnits) {
        switch (this) {
            case METER: return String.format("%.3fm", inOurUnits);
            case CM: return String.format("%.1fcm", inOurUnits);
            case MM: return String.format("%.0fmm", inOurUnits);
            default: return String.format("%.2fin", inOurUnits);
        }
    }

    @Override
    public String toString() {
        switch (this) {
            case METER: return "m";
            case CM: return "cm";
            case MM: return "mm";
            default: return "in";
        }
    }
}
