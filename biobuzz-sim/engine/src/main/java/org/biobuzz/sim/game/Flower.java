package org.biobuzz.sim.game;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.field.Alliance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.biobuzz.sim.util.Units.inToM;

/**
 * One FLOWER (§9.7, Fig 9-12): a vertical tube. Balls go in the TOP
 * (21.5 in up) and stack up. The MIDDLE ring lets POLLEN through into a
 * small pocket at the bottom (the Retrieval Opening), where robots can pull
 * it out (G418: only POLLEN, only from the bottom). NECTAR is too big to pass
 * the middle ring, so it stays in the tube.
 *
 * SCORING (§10.5.2): balls at least partly between the middle and top rings
 * are in the scoring volume. The alliance whose NECTAR is top-most OWNS the
 * FLOWER (2 pts per ball in it); the alliance whose NECTAR is bottom-most gets
 * the Bottom NECTAR Bonus (5 pts).
 *
 * We model the tube as an ordered stack instead of full 3D physics: balls in
 * a 4 in tube can only be in a line, so the order IS the physics.
 */
public final class Flower {
    public final int index;
    /** Center (meters). */
    public final double x;
    public final double y;
    /** Top ring height (meters). */
    public final double topZ;
    /** Top of the middle ring = bottom of the scoring volume (meters). */
    public final double middleZ;
    /** Top of the bottom ring = floor of the pocket (meters). */
    public final double pocketFloorZ;
    public final double openingRadius;
    public final double pipeRadius;

    /** Balls in the tube above the middle ring, bottom first. */
    private final List<Ball> tube = new ArrayList<>();
    /** The POLLEN sitting in the bottom pocket, or null. */
    private Ball pocket;
    /** When a POLLEN started dropping into the empty pocket (it takes a moment). */
    private double pocketRefillAt = -1;
    private static final double POCKET_DROP_SECONDS = 0.25; // ESTIMATE

    public Flower(int index, double x, double y, Cfg flower) {
        this.index = index;
        this.x = x;
        this.y = y;
        this.topZ = inToM(flower.num("topOpeningHeight"));
        this.pocketFloorZ = inToM(flower.num("bottomRingHeight"));
        this.middleZ = pocketFloorZ + inToM(flower.num("retrievalOpeningHeight"));
        this.openingRadius = inToM(flower.num("topOpeningDiameter")) / 2;
        this.pipeRadius = inToM(flower.num("collisionRadius"));
    }

    /** Height of the top of the stack (where the next ball would land). */
    private double stackTop() {
        double z = middleZ;
        for (Ball b : tube) {
            z += 2 * b.radius;
        }
        return z;
    }

    /**
     * A free ball near the top: does it drop in? It must be coming down
     * through the top ring close enough to the center, and the tube must not
     * be full to the top.
     */
    boolean tryEnter(Ball b, double prevZ, double slop) {
        if (b.vz >= 0 || prevZ < topZ || b.z > topZ + b.radius) {
            return false;
        }
        double off = Math.hypot(b.x - x, b.y - y);
        if (off > openingRadius - b.radius + slop) {
            return false;
        }
        if (stackTop() + 2 * b.radius > topZ + 2 * b.radius) {
            return false; // tube already full to the brim: it bounces off the top ball
        }
        b.state = Ball.State.IN_FLOWER;
        b.stop();
        tube.add(b);
        settle();
        return true;
    }

    /** Puts a ball in at the start of the match (§10.3.1: 4 POLLEN in each FLOWER). */
    void stage(Ball b) {
        b.state = Ball.State.IN_FLOWER;
        b.stop();
        tube.add(b);
        settle();
        if (pocketRefillAt >= 0) {
            finishPocketDrop();
        }
    }

    /** Lets the lowest POLLEN start falling into an empty pocket. */
    private void settle() {
        if (pocket == null && pocketRefillAt < 0 && !tube.isEmpty() && !tube.get(0).kind.isNectar()) {
            pocketRefillAt = 0; // start now; step() finishes it after the drop time
        }
        placeBalls();
    }

    private void finishPocketDrop() {
        if (pocket == null && !tube.isEmpty() && !tube.get(0).kind.isNectar()) {
            pocket = tube.remove(0);
        }
        pocketRefillAt = -1;
        placeBalls();
    }

    void step(double now) {
        if (pocketRefillAt == 0) {
            pocketRefillAt = now + POCKET_DROP_SECONDS;
        } else if (pocketRefillAt > 0 && now >= pocketRefillAt) {
            finishPocketDrop();
            settle();
        }
    }

    /** A robot's intake at the Retrieval Opening pulls the pocket POLLEN out. Returns it, or null. */
    Ball takeFromPocket() {
        Ball b = pocket;
        if (b != null) {
            pocket = null;
            settle();
        }
        return b;
    }

    public Ball pocket() {
        return pocket;
    }

    /** Positions for drawing and the camera. */
    private void placeBalls() {
        double z = middleZ;
        for (Ball b : tube) {
            b.setPosition(x, y, z + b.radius);
            z += 2 * b.radius;
        }
        if (pocket != null) {
            pocket.setPosition(x, y, pocketFloorZ + pocket.radius);
        }
    }

    // ------------------------------------------------------------ scoring

    /** Balls at least partially inside the scoring volume (middle ring to top ring). */
    public List<Ball> scoringBalls() {
        List<Ball> out = new ArrayList<>();
        double z = middleZ;
        for (Ball b : tube) {
            if (z < topZ) { // its bottom is below the top ring -> at least partly inside
                out.add(b);
            }
            z += 2 * b.radius;
        }
        return out;
    }

    /** Alliance whose NECTAR is top-most in the scoring volume, or null (§10.5.2 FLOWER Owner). */
    public Alliance owner() {
        List<Ball> s = scoringBalls();
        for (int i = s.size() - 1; i >= 0; i--) {
            if (s.get(i).kind.isNectar()) {
                return s.get(i).kind.alliance;
            }
        }
        return null;
    }

    /** Alliance whose NECTAR is bottom-most in the scoring volume, or null (Bottom NECTAR Bonus). */
    public Alliance bottomNectar() {
        for (Ball b : scoringBalls()) {
            if (b.kind.isNectar()) {
                return b.kind.alliance;
            }
        }
        return null;
    }

    public List<Ball> tube() {
        return Collections.unmodifiableList(tube);
    }
}
