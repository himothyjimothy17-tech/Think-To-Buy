package org.biobuzz.sim.game;

import org.biobuzz.sim.field.Alliance;

/**
 * One POLLEN or NECTAR ball. Position and velocity in METERS (field frame,
 * z = height of the ball's CENTER above the tiles).
 */
public final class Ball {

    /** Where the ball is right now. */
    public enum State {
        /** Loose: rolling, flying or resting on the field. Physics moves it. */
        FREE,
        /** Inside a robot (intake storage or jammed in the intake). */
        HELD,
        /** Resting in a HIVE CELL. */
        IN_CELL,
        /** Inside a FLOWER (tube or bottom pocket). */
        IN_FLOWER,
        /** NECTAR waiting in an ALLIANCE AREA for the human player (off the field). */
        STAGED,
        /** Left the field; waiting to be put back. */
        OUT_OF_FIELD
    }

    public final int id;
    public final ElementKind kind;
    public final double radius;
    public final double mass;

    public State state = State.FREE;
    public double x;
    public double y;
    public double z;
    public double vx;
    public double vy;
    public double vz;
    /** True while resting still on the floor (skipped by physics until something touches it). */
    public boolean asleep;

    /** Robot id holding it (state HELD), else null. */
    public String holder;
    /** Robot that LAUNCHED it last, and when (for G417 and scoring stats). */
    public String launchedBy;
    public Alliance launchedByAlliance;
    public double launchedAt = -1;
    /** Set when a tipped HIVE drops it; cleared when it touches anything but a robot (G409). */
    public boolean fromTippedHive;
    /** When it left the field (for putting it back). */
    public double outOfFieldAt;

    public Ball(int id, ElementKind kind, double radius, double mass) {
        this.id = id;
        this.kind = kind;
        this.radius = radius;
        this.mass = mass;
    }

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void stop() {
        vx = 0;
        vy = 0;
        vz = 0;
    }

    public double speed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    public boolean onGround() {
        return z <= radius + 1e-4;
    }
}
