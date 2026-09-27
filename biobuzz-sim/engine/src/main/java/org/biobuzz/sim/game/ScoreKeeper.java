package org.biobuzz.sim.game;

import org.biobuzz.sim.util.FastMath;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.field.Field;
import org.biobuzz.sim.geom.Rect;
import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.sim.util.JsonOut;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the score exactly as §10.5 / Table 10-2 describe.
 *
 *   AUTO (assessed at the end of AUTO): LEAVE 3, PARK 5, HIVE TIP 20
 *   TELEOP: HIVE TIP 20; and assessed at the END of the match when
 *     everything is at rest: balls in an up-facing CELL 2, Bottom NECTAR
 *     Bonus 5, balls in an owned FLOWER 2, balls in the GARDEN 1, PARK 5
 *   FOULS: points go to the OTHER alliance (MINOR 5, MAJOR 20).
 *
 * "Live" numbers for the end-of-match items show what they WOULD be if the
 * match ended now, so drivers can see where they stand.
 */
public final class ScoreKeeper {

    /** One alliance's points by category. */
    public static final class Breakdown {
        public int leave;
        public int autoPark;
        public int autoTips;
        public int teleopTips;
        public int cellBalls;
        public int bottomNectar;
        public int flowerBalls;
        public int garden;
        public int teleopPark;
        /** Points received because the OTHER alliance committed fouls. */
        public int foulPoints;

        public int autoTotal(Cfg p) {
            return leave * p.integer("leaveAuto") + autoPark * p.integer("parkAuto") + autoTips * p.integer("hiveTip");
        }

        public int teleopTotal(Cfg p) {
            return teleopTips * p.integer("hiveTip") + cellBalls * p.integer("elementInCellAtEnd")
                    + bottomNectar * p.integer("bottomNectarBonus") + flowerBalls * p.integer("elementInOwnedFlower")
                    + garden * p.integer("elementInGarden") + teleopPark * p.integer("parkTeleop");
        }

        public int total(Cfg p) {
            return autoTotal(p) + teleopTotal(p) + foulPoints;
        }

        public int tips() {
            return autoTips + teleopTips;
        }

        Breakdown copyEndItemsFrom(Breakdown o) {
            cellBalls = o.cellBalls;
            bottomNectar = o.bottomNectar;
            flowerBalls = o.flowerBalls;
            garden = o.garden;
            teleopPark = o.teleopPark;
            return this;
        }
    }

    private final Cfg points;
    private final Cfg rp;
    private final Field field;
    private final double wallContact;
    private final Map<Alliance, Breakdown> score = new EnumMap<>(Alliance.class);
    private boolean autoAssessed;
    private boolean finalAssessed;

    public ScoreKeeper(Cfg game, Field field) {
        this.points = game.obj("points");
        this.rp = game.obj("rankingPoints");
        this.field = field;
        this.wallContact = 0.006; // 6 mm: closer than this counts as "contacting the perimeter wall"
        for (Alliance a : Alliance.values()) {
            score.put(a, new Breakdown());
        }
    }

    public Breakdown get(Alliance a) {
        return score.get(a);
    }

    public int total(Alliance a) {
        return score.get(a).total(points);
    }

    public boolean isFinal() {
        return finalAssessed;
    }

    /** A HIVE finished tipping. §10.5 B: tips completed before TELEOP starts count as AUTO. */
    public void onTip(Alliance hive, boolean beforeTeleop) {
        if (beforeTeleop) {
            score.get(hive).autoTips++;
        } else {
            score.get(hive).teleopTips++;
        }
    }

    /** A foul by {@code offender}: the points go to the other alliance. */
    public void onFoul(Alliance offender, int pts) {
        score.get(offender.opponent()).foulPoints += pts;
    }

    /** §10.5 F: LEAVE and AUTO PARK are assessed at the end of AUTO. */
    public void assessAuto(List<SimRobot> robots) {
        if (autoAssessed) {
            return;
        }
        autoAssessed = true;
        for (SimRobot r : robots) {
            Breakdown b = score.get(r.alliance);
            if (!touchingWall(r)) {
                b.leave++;
            }
            if (overlaps(r, field.loadingZone.get(r.alliance))) {
                b.autoPark++;
            }
        }
    }

    /** Recomputes the end-of-match items (live), or freezes them when {@code isFinal}. */
    public void updateEndItems(GameWorld world, List<SimRobot> robots, boolean isFinal) {
        if (finalAssessed) {
            return;
        }
        Map<Alliance, Breakdown> fresh = new EnumMap<>(Alliance.class);
        for (Alliance a : Alliance.values()) {
            fresh.put(a, new Breakdown());
        }
        // Balls left in an up-facing CELL (§10.5.1).
        for (Hive h : world.hives.values()) {
            fresh.get(h.alliance).cellBalls += h.upCellBalls().size();
        }
        // FLOWERS: owner gets 2 per ball in the scoring volume; bottom NECTAR gets 5 (§10.5.2).
        for (Flower f : world.flowers) {
            Alliance owner = f.owner();
            if (owner != null) {
                fresh.get(owner).flowerBalls += f.scoringBalls().size();
            }
            Alliance bottom = f.bottomNectar();
            if (bottom != null) {
                fresh.get(bottom).bottomNectar++;
            }
        }
        // GARDENS: any loose ball at least partially inside counts for the GARDEN's alliance (§10.5.3).
        for (Ball b : world.balls) {
            if (b.state != Ball.State.FREE) {
                continue;
            }
            for (Alliance a : Alliance.values()) {
                if (circleOverlapsRect(b.x, b.y, b.radius, field.garden.get(a))) {
                    fresh.get(a).garden++;
                }
            }
        }
        // TELEOP PARK: at least partially in the alliance's own LOADING ZONE (§10.5.4).
        for (SimRobot r : robots) {
            if (overlaps(r, field.loadingZone.get(r.alliance))) {
                fresh.get(r.alliance).teleopPark++;
            }
        }
        for (Alliance a : Alliance.values()) {
            score.get(a).copyEndItemsFrom(fresh.get(a));
        }
        if (isFinal) {
            finalAssessed = true;
        }
    }

    // ------------------------------------------------------------ ranking points

    /** Ranking points for an alliance (Table 10-2 / 10-3, "All Other Events"). */
    public int rankingPoints(Alliance a) {
        Breakdown b = score.get(a);
        int rps = 0;
        int leavePark = b.leave * points.integer("leaveAuto") + b.autoPark * points.integer("parkAuto")
                + b.teleopPark * points.integer("parkTeleop");
        if (leavePark >= rp.integer("swarmThresholdPoints")) {
            rps++;
        }
        if (b.tips() >= rp.integer("pollinator1TipThreshold")) {
            rps++;
        }
        if (b.tips() >= rp.integer("pollinator2TipThreshold")) {
            rps++;
        }
        int us = total(a);
        int them = total(a.opponent());
        rps += us > them ? rp.integer("win") : (us == them ? rp.integer("tie") : 0);
        return rps;
    }

    // ------------------------------------------------------------ geometry

    /** Is any part of the robot within a few mm of a perimeter wall? */
    public boolean touchingWall(SimRobot r) {
        for (double[] c : r.corners()) {
            if (field.half - Math.abs(c[0]) < wallContact || field.half - Math.abs(c[1]) < wallContact) {
                return true;
            }
        }
        return false;
    }

    /** Does the robot's footprint overlap an (infinitely tall) zone? Separating-axis test. */
    public static boolean overlaps(SimRobot r, Rect z) {
        double[][] cs = r.corners();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] c : cs) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]);
            maxY = Math.max(maxY, c[1]);
        }
        if (maxX < z.xMin || minX > z.xMax || maxY < z.yMin || minY > z.yMax) {
            return false;
        }
        // Robot's own axes.
        double[][] axes = {{Math.cos(r.heading), Math.sin(r.heading)}, {-Math.sin(r.heading), Math.cos(r.heading)}};
        double[][] rect = {{z.xMin, z.yMin}, {z.xMax, z.yMin}, {z.xMax, z.yMax}, {z.xMin, z.yMax}};
        for (double[] ax : axes) {
            double a0 = Double.MAX_VALUE;
            double a1 = -Double.MAX_VALUE;
            double b0 = Double.MAX_VALUE;
            double b1 = -Double.MAX_VALUE;
            for (double[] c : cs) {
                double p = c[0] * ax[0] + c[1] * ax[1];
                a0 = Math.min(a0, p);
                a1 = Math.max(a1, p);
            }
            for (double[] c : rect) {
                double p = c[0] * ax[0] + c[1] * ax[1];
                b0 = Math.min(b0, p);
                b1 = Math.max(b1, p);
            }
            if (a1 < b0 || b1 < a0) {
                return false;
            }
        }
        return true;
    }

    public static boolean circleOverlapsRect(double x, double y, double r, Rect z) {
        double cx = Math.max(z.xMin, Math.min(z.xMax, x));
        double cy = Math.max(z.yMin, Math.min(z.yMax, y));
        return FastMath.hypot(x - cx, y - cy) < r;
    }

    // ------------------------------------------------------------ output

    public void writeJson(JsonOut j) {
        j.beginObject();
        for (Alliance a : Alliance.values()) {
            Breakdown b = score.get(a);
            j.name(a.name().toLowerCase()).beginObject()
                    .field("total", b.total(points))
                    .field("auto", b.autoTotal(points))
                    .field("teleop", b.teleopTotal(points))
                    .field("fouls", b.foulPoints)
                    .field("leave", b.leave)
                    .field("autoPark", b.autoPark)
                    .field("autoTips", b.autoTips)
                    .field("teleopTips", b.teleopTips)
                    .field("cellBalls", b.cellBalls)
                    .field("bottomNectar", b.bottomNectar)
                    .field("flowerBalls", b.flowerBalls)
                    .field("garden", b.garden)
                    .field("teleopPark", b.teleopPark)
                    .field("rankingPoints", rankingPoints(a))
                    .endObject();
        }
        j.field("final", finalAssessed);
        j.endObject();
    }
}
