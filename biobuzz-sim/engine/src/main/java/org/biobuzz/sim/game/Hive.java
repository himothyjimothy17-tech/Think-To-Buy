package org.biobuzz.sim.game;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.field.Alliance;

import java.util.ArrayList;
import java.util.List;

import static org.biobuzz.sim.util.Units.inToM;

/**
 * One HIVE (§9.6): an arm on a pivot with a CELL at each end. One CELL faces
 * up at any time (tilted 30 degrees, Fig 9-10). LAUNCHING enough balls into
 * the up-facing CELL tips the HIVE: it swings over, the full CELL turns
 * down and spills, and the empty one faces up.
 *
 * GEOMETRY ("hive frame", meters): origin at the pivot,
 *   X = along the frame's crossbar (field x),
 *   Y = along the arm (the +Y end is the far/"scoring" side of the field),
 *   Z = perpendicular to the arm, "up" when the arm is level.
 * The arm is rotated about X by the tilt angle; +tilt raises the +Y end.
 * Each CELL is a pentagon (Fig 9-11: 20 in wide, 14 in to the peak) swept
 * along the arm from the inner end to the OPENING at the outer end.
 */
public final class Hive {

    public final Alliance alliance;
    /** Pivot position (meters, field frame). */
    public final double pivotX;
    public final double pivotZ;

    // Cell shape (meters, hive frame).
    final double halfWidth;
    final double base;          // Z of the CELL floor
    final double rectTop;       // Z where the pentagon's sides start to slope
    final double peak;          // Z of the peak
    final double inner;         // |Y| of the CELL's closed inner end
    final double outer;         // |Y| of the OPENING
    private final double tiltRad;
    private final double tipSeconds;
    public final double tipMassKg;

    /** Which end faces up: +1 = far (+Y) end, -1 = audience (-Y) end. */
    private int upEnd;
    /** Current arm angle (radians); animates during a tip. */
    private double angle;
    private double tipStartTime = -1;
    /** Balls resting in each end's CELL (index 0 = audience end, 1 = far end). */
    private final List<List<Ball>> cells = new ArrayList<>();
    public int tips;
    /** Tips completed before TELEOP started (count as AUTO, §10.5 B). */
    public int autoTips;

    public Hive(Alliance alliance, Cfg hive, Cfg physics) {
        this.alliance = alliance;
        double centerToCenter = inToM(hive.num("hiveCenterToCenter"));
        this.pivotX = (alliance == Alliance.RED ? -1 : 1) * centerToCenter / 2;
        this.pivotZ = inToM(hive.num("pivotHeight"));
        this.halfWidth = inToM(hive.num("cellOpeningWidth")) / 2;
        this.base = inToM(physics.num("cellBaseOffset"));
        this.rectTop = base + inToM(hive.num("cellOpeningRectHeight"));
        this.peak = base + inToM(hive.num("cellOpeningHeight"));
        this.inner = inToM(hive.num("cellGap")) / 2;
        this.outer = inner + inToM(hive.num("cellDepth"));
        this.tiltRad = Math.toRadians(hive.num("tiltDegrees"));
        this.tipSeconds = physics.num("tipSeconds");
        this.tipMassKg = physics.num("tipMassG") / 1000.0;
        // §10.3.1: red's audience-side CELL starts up; blue is the 180-degree rotation.
        boolean redAudienceUp = hive.str("redInitialUpCell").equals("audience");
        this.upEnd = (alliance == Alliance.RED) == redAudienceUp ? -1 : 1;
        this.angle = upEnd * tiltRad;
        cells.add(new ArrayList<>());
        cells.add(new ArrayList<>());
    }

    // ------------------------------------------------------------------ state

    /** +1 if the far (+y) CELL faces up, -1 if the audience (-y) one does. */
    public int upEnd() {
        return upEnd;
    }

    public double angle() {
        return angle;
    }

    public boolean isTipping() {
        return tipStartTime >= 0;
    }

    public List<Ball> upCellBalls() {
        return cells.get(upEnd > 0 ? 1 : 0);
    }

    /** Mass (kg) resting in the up-facing CELL. */
    public double upCellMass() {
        double m = 0;
        for (Ball b : upCellBalls()) {
            m += b.mass;
        }
        return m;
    }

    // --------------------------------------------------------- coordinates

    /** Field point -> hive frame (at the current angle). */
    double[] toLocal(double x, double y, double z) {
        double dx = x - pivotX;
        double dy = y;
        double dz = z - pivotZ;
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        // Inverse of a rotation about X.
        return new double[] {dx, dy * c + dz * s, -dy * s + dz * c};
    }

    /** Hive frame point -> field point (at the current angle). */
    public double[] toField(double lx, double ly, double lz) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return new double[] {pivotX + lx, ly * c - lz * s, pivotZ + ly * s + lz * c};
    }

    /** Hive frame direction -> field direction. */
    double[] dirToField(double lx, double ly, double lz) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return new double[] {lx, ly * c - lz * s, ly * s + lz * c};
    }

    /** Is (X, Z) inside the CELL's pentagon, shrunk by {@code margin} on every side? */
    boolean insidePentagon(double lx, double lz, double margin) {
        if (lz < base + margin || Math.abs(lx) > halfWidth - margin) {
            return false;
        }
        if (lz <= rectTop) {
            return true;
        }
        // Sloped roof: from (+-halfWidth, rectTop) up to (0, peak).
        double roof = rectTop + (peak - rectTop) * (1 - Math.abs(lx) / halfWidth);
        return lz <= roof - margin * 1.2;
    }

    /** Center of the up-facing CELL's opening, in field coordinates (where to aim). */
    public double[] upOpeningCenter() {
        return toField(0, upEnd * outer, base + (peak - base) * 0.45);
    }

    /** Unit vector pointing OUT of the up-facing CELL's opening (field frame). */
    public double[] upOpeningNormal() {
        return dirToField(0, upEnd, 0);
    }

    // ---------------------------------------------------------- ball contact

    /**
     * Checks a free ball against this HIVE's CELLS. Returns true if the ball
     * went INTO the up-facing CELL (it is then stored there). Otherwise it
     * bounces off any CELL wall it hits.
     */
    boolean interact(Ball b, double prevX, double prevY, double prevZ, double restitution, double slop) {
        double[] now = toLocal(b.x, b.y, b.z);
        double r = b.radius;
        // Quick reject: far from both CELLS.
        if (Math.abs(now[0]) > halfWidth + r || Math.abs(now[1]) > outer + r || Math.abs(now[1]) < inner - r
                || now[2] < base - r || now[2] > peak + r) {
            return false;
        }
        int end = now[1] > 0 ? 1 : -1;
        boolean openFace = end == upEnd && !isTipping();
        if (openFace && insidePentagon(now[0], now[2], r * (1 - slop))) {
            // Lined up with the opening of the up-facing CELL.
            if (end * now[1] <= outer) {
                capture(b, end); // its center crossed into the CELL
                return true;
            }
            return false; // still on its way in through the open face: nothing to hit
        }
        // Outside the prism? Then no contact.
        boolean inside = Math.abs(now[0]) < halfWidth + r && now[2] > base - r && now[2] < peak + r
                && end * now[1] > inner - r && end * now[1] < outer + r;
        if (!inside) {
            return false;
        }
        // Bounce off the nearest outer face of the CELL.
        double dSide = halfWidth + r - Math.abs(now[0]);
        double dBottom = now[2] - (base - r);
        double dTop = (peak + r) - now[2];
        double dEnd = Math.min(end * now[1] - (inner - r), (outer + r) - end * now[1]);
        double min = Math.min(Math.min(dSide, dBottom), Math.min(dTop, dEnd));
        double[] n;
        if (min == dSide) {
            n = new double[] {Math.signum(now[0]), 0, 0};
            now[0] = Math.signum(now[0]) * (halfWidth + r);
        } else if (min == dBottom) {
            n = new double[] {0, 0, -1};
            now[2] = base - r;
        } else if (min == dTop) {
            n = new double[] {0, 0, 1};
            now[2] = peak + r;
        } else if (end * now[1] - (inner - r) < (outer + r) - end * now[1]) {
            n = new double[] {0, -end, 0};
            now[1] = end * (inner - r);
        } else {
            n = new double[] {0, end, 0};
            now[1] = end * (outer + r);
        }
        double[] p = toField(now[0], now[1], now[2]);
        b.setPosition(p[0], p[1], p[2]);
        double[] nf = dirToField(n[0], n[1], n[2]);
        double vn = b.vx * nf[0] + b.vy * nf[1] + b.vz * nf[2];
        if (vn < 0) {
            b.vx -= (1 + restitution) * vn * nf[0];
            b.vy -= (1 + restitution) * vn * nf[1];
            b.vz -= (1 + restitution) * vn * nf[2];
        }
        b.asleep = false;
        return false;
    }

    private void capture(Ball b, int end) {
        b.state = Ball.State.IN_CELL;
        b.stop();
        cells.get(end > 0 ? 1 : 0).add(b);
        placeCellBalls(end);
    }

    /** Arranges resting balls on the CELL floor, from the closed end outward (for drawing and the camera). */
    private void placeCellBalls(int end) {
        List<Ball> list = cells.get(end > 0 ? 1 : 0);
        double x = -halfWidth;
        double yInner = inner;
        double row = 0;
        double layer = 0;
        for (Ball b : list) {
            double d = 2 * b.radius;
            if (x + d > halfWidth) {
                x = -halfWidth;
                row += d;
                if (yInner + row + d > outer) {
                    row = 0;
                    layer += d;
                }
            }
            double lx = x + b.radius;
            double ly = end * (yInner + row + b.radius);
            double lz = base + layer + b.radius;
            double[] p = toField(lx, ly, lz);
            b.setPosition(p[0], p[1], p[2]);
            x += d;
        }
    }

    /**
     * Advances the HIVE. Starts a tip when the up CELL is heavy enough, and
     * finishes it after tipSeconds. Returns the balls spilled by a finished
     * tip (so the world can make them free again), or null.
     */
    List<Ball> step(double now) {
        if (!isTipping()) {
            if (upCellMass() >= tipMassKg) {
                tipStartTime = now;
            }
            return null;
        }
        double t = (now - tipStartTime) / tipSeconds;
        if (t < 1) {
            // Swing from +upEnd*tilt to -upEnd*tilt, easing in and out.
            double e = 0.5 - 0.5 * Math.cos(Math.PI * t);
            angle = upEnd * tiltRad * (1 - 2 * e);
            placeCellBalls(upEnd);
            return null;
        }
        // Tip complete: the damper touches the frame (§10.5.1). The full CELL is now down.
        int fullEnd = upEnd;
        upEnd = -upEnd;
        angle = upEnd * tiltRad;
        tipStartTime = -1;
        tips++;
        List<Ball> spilled = new ArrayList<>(cells.get(fullEnd > 0 ? 1 : 0));
        cells.get(fullEnd > 0 ? 1 : 0).clear();
        // Release them just outside the (now downward-facing) opening, falling.
        int i = 0;
        for (Ball b : spilled) {
            double lx = -halfWidth * 0.6 + (i % 4) * halfWidth * 0.4;
            double ly = fullEnd * (outer + b.radius * 1.2);
            double lz = base + b.radius + (i / 4) * b.radius * 2;
            double[] p = toField(lx, ly, lz);
            b.setPosition(p[0], p[1], p[2]);
            double[] out = dirToField(0, fullEnd * 0.3, -0.2);
            b.vx = out[0];
            b.vy = out[1];
            b.vz = out[2];
            b.state = Ball.State.FREE;
            b.asleep = false;
            b.fromTippedHive = true;
            i++;
        }
        return spilled;
    }

    /** Puts a ball in the up-facing CELL at the start (the 3 staged NECTAR, §10.3.1 B.i). */
    void stage(Ball b) {
        capture(b, upEnd);
    }

    /** Field-frame corners of this HIVE's CELLS as quick boxes (for camera occlusion). */
    public double[][] cellCenters() {
        double mid = (inner + outer) / 2;
        double midZ = (base + peak) / 2;
        return new double[][] {toField(0, -mid, midZ), toField(0, mid, midZ)};
    }

    /**
     * AprilTag positions and the direction each faces (field frame). §9.9: a
     * cluster of 4 tags on the BOTTOM face of each CELL, facing down, IDs
     * listed from the audience-side end; spacing from Fig 9-15.
     */
    public List<double[]> aprilTags(Cfg tags) {
        List<double[]> out = new ArrayList<>();
        String prefix = alliance == Alliance.RED ? "red" : "blue";
        double innerOffset = inToM(tags.num("clusterSpacingInner"));
        double outerOffset = inToM(tags.num("clusterSpacingOuter"));
        double[] offsets = {-outerOffset, -innerOffset, innerOffset, outerOffset};
        double mid = (inner + outer) / 2;
        for (int end : new int[] {-1, 1}) {
            List<Double> ids = tags.numList(prefix + (end < 0 ? "AudienceCellIds" : "FarCellIds"));
            for (int i = 0; i < 4; i++) {
                // Tags run across the CELL's width; red's cluster reads 33..30 left to right
                // from the audience (Fig 9-17), so order them by x.
                double lx = offsets[i] * (alliance == Alliance.RED ? -1 : 1);
                double[] p = toField(lx, end * mid, base - 0.005);
                double[] n = dirToField(0, 0, -1);
                out.add(new double[] {ids.get(i), p[0], p[1], p[2], n[0], n[1], n[2]});
            }
        }
        return out;
    }
}
