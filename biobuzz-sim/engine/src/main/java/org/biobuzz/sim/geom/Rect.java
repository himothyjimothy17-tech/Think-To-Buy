package org.biobuzz.sim.geom;

/** An axis-aligned rectangle on the field, in METERS. Used for zones and areas. */
public final class Rect {
    public final double xMin;
    public final double xMax;
    public final double yMin;
    public final double yMax;

    public Rect(double xMin, double xMax, double yMin, double yMax) {
        this.xMin = Math.min(xMin, xMax);
        this.xMax = Math.max(xMin, xMax);
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
    }

    /** The same zone for the other alliance (180-degree rotation around the center). */
    public Rect rotate180() {
        return new Rect(-xMax, -xMin, -yMax, -yMin);
    }

    public boolean contains(double x, double y) {
        return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
    }
}
