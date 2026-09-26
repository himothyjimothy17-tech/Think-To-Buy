package org.biobuzz.sim.field;

/** Red or blue. Red positions are listed in the config; blue ones are rotated 180 degrees. */
public enum Alliance {
    RED, BLUE;

    public Alliance opponent() {
        return this == RED ? BLUE : RED;
    }

    public static Alliance parse(String s) {
        return s.trim().equalsIgnoreCase("blue") ? BLUE : RED;
    }
}
