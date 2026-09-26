package org.biobuzz.sim.geom;

/** A round obstacle (FLOWER, HIVE leg...) in METERS. */
public final class Circle {
    public final double x;
    public final double y;
    public final double r;
    public final String label;

    public Circle(double x, double y, double r, String label) {
        this.x = x;
        this.y = y;
        this.r = r;
        this.label = label;
    }
}
