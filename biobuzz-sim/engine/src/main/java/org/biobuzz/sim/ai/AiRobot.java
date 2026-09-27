package org.biobuzz.sim.ai;

import org.biobuzz.sim.util.FastMath;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.core.MatchTimer;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.field.Field;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.Ballistics;
import org.biobuzz.sim.game.ElementKind;
import org.biobuzz.sim.game.Flower;
import org.biobuzz.sim.game.GameWorld;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.geom.Circle;
import org.biobuzz.sim.geom.Rect;
import org.biobuzz.sim.robot.ElementCarrier;
import org.biobuzz.sim.robot.SimRobot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.biobuzz.sim.util.Units.inToM;

/**
 * A computer-driven robot (our PARTNER or an OPPONENT).
 *
 * WHAT IT DOES - like a decent real team:
 *   AUTO:   wait a moment, drive to a spot in front of its own HIVE's
 *           up-facing CELL, shoot the 4 pre-loads, maybe collect GARDEN
 *           POLLEN on its own side, maybe PARK. Never crosses the center
 *           line (G402).
 *   TELEOP: collect POLLEN / its own NECTAR, shoot when full, maybe lob
 *           NECTAR into FLOWERS in the last 60 s, PARK near the end.
 *   Between periods and after the match it stops (G403, G404).
 *
 * HOW IT MOVES: a simple holonomic model - it accelerates toward the speed it
 * wants, limited by traction, and turns at a limited rate. Pushing works
 * because the collision code shares momentum between robots by mass.
 *
 * HOW IT SHOOTS: it solves the launch speed WITH air drag (same physics as
 * the balls), then adds its own aim/speed error. The ball physics decide
 * whether it actually goes in - there are no free points.
 *
 * Every number comes from ai.jsonc (all ESTIMATES), drawn per robot from
 * [min, max] ranges using the match seed.
 */
public final class AiRobot implements ElementCarrier {

    /** What the AI knows about the match right now. */
    public static final class Context {
        public double now;
        public MatchTimer.Phase phase;
        public double phaseTime;
        public double matchSecondsLeft;
        public double autoSecondsLeft;
        public GameWorld world;
        public Field field;
        public List<SimRobot> robots;
        /** Balls another AI robot is already going for. */
        public Map<Ball, AiRobot> claims = new HashMap<>();
        /** Shooting spots teammates are using {x, y}. */
        public Map<AiRobot, double[]> spots = new HashMap<>();
    }

    private enum Task { WAIT, SHOOT, COLLECT, PARK, FLOWER, OFF_WALL }

    public final SimRobot body;
    private final int slot; // 0 or 1 within its alliance (parking spot)
    private final Random rng;
    private final List<Ball> held = new ArrayList<>();
    private final double sideSign; // -1 red (x < 0), +1 blue

    // ---- personality (drawn from ai.jsonc ranges)
    private final double maxSpeed;
    private final double accel;
    private final double maxTurn;
    private final double reaction;
    private final int capacity;
    private final double pickupInterval;
    private final double mouthDepth;
    private final double mouthWidth;
    private final double launchHeight;
    private final double launchPitch;
    private final double maxLaunchSpeed;
    private final double speedSpread;
    private final double angleSpread;
    private final double spinUp;
    private final double shotInterval;
    private final double aimTolerance;
    private final boolean parkInAuto;
    private final boolean parkInTeleop;
    private final boolean collectInAuto;
    private final boolean flowerEndgame;
    private final double parkWhen;
    private final double autoParkWhen;

    // ---- state
    private Task task = Task.WAIT;
    private Ball target;
    private double targetSince;
    private final Map<Ball, Double> blacklist = new HashMap<>();
    private double spinReadyAt = -1;
    private double nextShotAt;
    private double nextPickupAt;
    private double stuckSince = -1;
    private double sidestepUntil = -1;
    private double sidestepSign = 1;
    private double desiredVx;
    private double desiredVy;
    /** Cached best shooting spot for the HIVE's current orientation. */
    private double[] hivePlan;
    private int hivePlanKey = Integer.MIN_VALUE;
    private boolean sharedSpot;
    public int shots;
    public int pickups;

    private final List<Circle> fieldObstacles;
    /** Spots our partner promised to leave free (empty for opponents). */
    private final List<Circle> keepOut = new ArrayList<>();
    private final Ballistics pollenFlight;
    private final Ballistics nectarFlight;

    public AiRobot(SimRobot body, int slot, boolean partnerOfUs, Cfg ai, Random rng, GameWorld world, double pollenR,
                   double pollenM, double nectarR, double nectarM) {
        this.body = body;
        this.slot = slot;
        this.rng = rng;
        this.sideSign = body.alliance == Alliance.RED ? -1 : 1;
        body.massKg = pick(ai, "massLb") * 0.4536;
        maxSpeed = inToM(pick(ai, "maxSpeedInPerSec"));
        accel = inToM(pick(ai, "accelInPerSec2"));
        maxTurn = Math.toRadians(pick(ai, "maxTurnDegPerSec"));
        reaction = pick(ai, "reactionSeconds");
        capacity = ai.integer("capacity");
        pickupInterval = pick(ai, "pickupIntervalSeconds");
        mouthDepth = inToM(ai.num("mouthDepthIn"));
        mouthWidth = inToM(ai.num("mouthWidthIn"));
        launchHeight = inToM(ai.num("launchHeightIn"));
        launchPitch = Math.toRadians(pick(ai, "launchAngleDeg"));
        maxLaunchSpeed = ai.num("maxLaunchSpeedMps");
        speedSpread = pick(ai, "speedSpread");
        angleSpread = Math.toRadians(pick(ai, "angleSpreadDeg"));
        spinUp = pick(ai, "spinUpSeconds");
        shotInterval = pick(ai, "shotIntervalSeconds");
        aimTolerance = Math.toRadians(ai.num("aimToleranceDeg"));
        parkInAuto = rng.nextDouble() < ai.num("parkInAutoChance");
        parkInTeleop = rng.nextDouble() < ai.num("parkInTeleopChance");
        collectInAuto = rng.nextDouble() < ai.num("collectInAutoChance");
        flowerEndgame = rng.nextDouble() < ai.num("flowerEndgameChance");
        parkWhen = pick(ai, "parkWhenSecondsLeft");
        autoParkWhen = pick(ai, "autoParkWhenSecondsLeft");
        fieldObstacles = world.field().obstacles;
        if (partnerOfUs && ai.has("partnerKeepOut")) {
            for (Cfg k : ai.objList("partnerKeepOut")) {
                double s = body.alliance == Alliance.RED ? 1 : -1; // BLUE: rotate 180 degrees
                keepOut.add(new Circle(inToM(k.num("x")) * s, inToM(k.num("y")) * s, inToM(k.num("r")), "keep-out"));
            }
        }
        pollenFlight = new Ballistics(world.dragK(), pollenR, pollenM);
        nectarFlight = new Ballistics(world.dragK(), nectarR, nectarM);
    }

    private double pick(Cfg ai, String key) {
        List<Double> r = ai.numList(key);
        return r.get(0) + rng.nextDouble() * (r.get(1) - r.get(0));
    }

    /** Pre-loaded balls (G304.G: 4 POLLEN). */
    public void preload(List<Ball> balls) {
        held.clear();
        held.addAll(balls);
    }

    @Override
    public int heldCount() {
        held.removeIf(b -> b.state != Ball.State.HELD || !body.id.equals(b.holder));
        return held.size();
    }

    @Override
    public List<Ball> heldBalls() {
        heldCount();
        return held;
    }

    /** What it's doing right now (for the UI and debugging). */
    public String status() {
        return task.name() + (task == Task.COLLECT && target != null ? " ball " + target.id : "") + " held " + held.size()
                + " shots " + shots + " picks " + pickups
                + (hivePlan != null ? String.format(" spot (%.0f, %.0f)", hivePlan[0] / 0.0254, hivePlan[1] / 0.0254) : " no spot");
    }

    /** One-line summary for the log / UI. */
    public String describe() {
        return String.format("%s: %.0f in/s, %.0f deg shooter, %s%s%s%s", body.id, maxSpeed / 0.0254,
                Math.toDegrees(launchPitch), parkInAuto ? "parks in AUTO, " : "", collectInAuto ? "collects in AUTO, " : "",
                flowerEndgame ? "FLOWER endgame, " : "", parkInTeleop ? "parks at the end" : "doesn't park");
    }

    // =====================================================================
    // Every physics step
    // =====================================================================

    public void step(Context c, double dt) {
        heldCount();
        for (Ball b : held) {
            b.setPosition(body.x, body.y, inToM(8));
        }
        boolean active = (c.phase == MatchTimer.Phase.AUTO && c.phaseTime > reaction) || c.phase == MatchTimer.Phase.TELEOP;
        if (!active) {
            desiredVx = 0;
            desiredVy = 0;
            releaseClaim(c);
            move(dt, body.heading);
            return;
        }
        decide(c);
        double wantHeading = body.heading;
        switch (task) {
            case SHOOT: wantHeading = doShoot(c); break;
            case COLLECT: wantHeading = doCollect(c); break;
            case PARK: wantHeading = doPark(c); break;
            case FLOWER: wantHeading = doFlower(c); break;
            case OFF_WALL: wantHeading = doOffWall(c); break;
            default:
                desiredVx = 0;
                desiredVy = 0;
        }
        tryPickup(c);
        move(dt, wantHeading);
        if (c.phase == MatchTimer.Phase.AUTO) {
            // G402: a careful driver never lets the robot be shoved over the center line in AUTO
            // (teammates bump each other). Treat the line as a wall for AI robots.
            double limit = halfDiagonal() + inToM(0.3);
            if (body.x * sideSign < limit) {
                body.x = sideSign * limit;
                if (body.vx * sideSign < 0) {
                    body.vx = 0;
                }
            }
        }
    }

    private void decide(Context c) {
        boolean auto = c.phase == MatchTimer.Phase.AUTO;
        Task next;
        if (auto && parkInAuto && c.autoSecondsLeft <= autoParkWhen) {
            next = Task.PARK;
        } else if (auto && c.autoSecondsLeft <= 2.5) {
            next = Task.OFF_WALL; // LEAVE only counts if we're not touching the wall when AUTO ends
        } else if (!auto && parkInTeleop && c.matchSecondsLeft <= parkWhen) {
            next = Task.PARK;
        } else if (!auto && flowerEndgame && c.matchSecondsLeft < 58 && holdsOwnNectar()) {
            next = Task.FLOWER;
        } else if (held.size() >= capacity || (held.size() > 0 && (auto || !ballNearby(c, inToM(30))))) {
            next = holdsOwnNectar() && flowerEndgame && c.matchSecondsLeft < 58 ? Task.FLOWER : Task.SHOOT;
        } else if (!auto || collectInAuto) {
            next = Task.COLLECT;
        } else {
            next = Task.WAIT;
        }
        if (next == Task.SHOOT && task != Task.SHOOT && spinReadyAt < 0) {
            spinReadyAt = c.now + spinUp;
        }
        if (next != Task.COLLECT) {
            releaseClaim(c);
        }
        task = next;
    }

    private boolean holdsOwnNectar() {
        for (Ball b : held) {
            if (b.kind.isNectar()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- shooting

    /** Best spot to shoot into our HIVE's up CELL: {x, y, distance}. Cached per HIVE orientation. */
    private double[] hivePlan(Context ctx, Hive h) {
        // Re-plan when the HIVE flips, or when a teammate took our spot.
        int key = h.upEnd() * 1000 + h.tips;
        for (Map.Entry<AiRobot, double[]> e : ctx.spots.entrySet()) {
            if (e.getKey() != this && e.getKey().body.alliance == body.alliance && hivePlan != null && !sharedSpot
                    && FastMath.hypot(e.getValue()[0] - hivePlan[0], e.getValue()[1] - hivePlan[1]) < inToM(22)) {
                key = Integer.MIN_VALUE + 1;
            }
        }
        if (key == hivePlanKey) {
            return hivePlan;
        }
        double[] c = h.upOpeningCenter();
        double[] n = h.upOpeningNormal();
        // The opening faces outward AND 30 deg up, so a ball goes in if it's moving into
        // that tilted face - even while still rising a little. Search our half of the
        // field for the spot whose shot arrives best lined up with the way in (-n).
        double[] plan = searchSpot(ctx, c, n, true);
        sharedSpot = false;
        if (plan == null) {
            plan = searchSpot(ctx, c, n, false); // share a spot rather than stand around
            sharedSpot = true;
        }
        hivePlanKey = h.upEnd() * 1000 + h.tips;
        hivePlan = plan;
        if (plan != null) {
            ctx.spots.put(this, plan);
        } else {
            ctx.spots.remove(this);
        }
        return plan;
    }

    private double[] searchSpot(Context ctx, double[] c, double[] n, boolean avoidTeammates) {
        double best = -2;
        double[] plan = null;
        for (double xIn = -64; xIn <= 64; xIn += 4) {
            for (double yIn = -64; yIn <= 64; yIn += 4) {
                double x = inToM(xIn);
                double y = inToM(yIn);
                if (!spotIsDrivable(x, y) || avoidTeammates && spotTakenByTeammate(ctx, x, y)) {
                    continue;
                }
                double hx = c[0] - x;
                double hy = c[1] - y;
                double d = FastMath.hypot(hx, hy);
                if (d < inToM(12) || (hx * n[0] + hy * n[1]) > 0) {
                    continue; // must be in front of the opening
                }
                Ballistics.Arrival a = solvedShot(d, c[2]);
                if (a == null) {
                    continue;
                }
                double vx = hx / d * a.vHoriz;
                double vy = hy / d * a.vHoriz;
                double len = Math.sqrt(vx * vx + vy * vy + a.vz * a.vz);
                double align = -(vx * n[0] + vy * n[1] + a.vz * n[2]) / len;
                if (align < 0.35) {
                    continue; // too glancing to fit through the opening
                }
                // Prefer good alignment, then shorter shots (aim errors grow with distance).
                double scoreIt = align - mToInches(d) * 0.002;
                if (scoreIt > best) {
                    best = scoreIt;
                    plan = new double[] {x, y, d};
                }
            }
        }
        return plan;
    }

    /** Flight solutions by (distance, target height), 0.5 in / 1 mm resolution: planning reuses them. */
    private final Map<Long, Ballistics.Arrival> shotCache = new HashMap<>();

    private Ballistics.Arrival solvedShot(double d, double zTarget) {
        long key = Math.round(mToInches(d) * 2) * 100000L + Math.round(zTarget * 1000);
        if (shotCache.containsKey(key)) {
            return shotCache.get(key);
        }
        double dq = inToM(Math.round(mToInches(d) * 2) / 2.0);
        double v = pollenFlight.speedFor(launchPitch, launchHeight, dq, zTarget, maxLaunchSpeed);
        Ballistics.Arrival a = Double.isNaN(v) ? null : pollenFlight.fly(v, launchPitch, launchHeight, dq);
        shotCache.put(key, a);
        return a;
    }

    private boolean spotTakenByTeammate(Context ctx, double x, double y) {
        for (Map.Entry<AiRobot, double[]> e : ctx.spots.entrySet()) {
            if (e.getKey() != this && e.getKey().body.alliance == body.alliance
                    && FastMath.hypot(e.getValue()[0] - x, e.getValue()[1] - y) < inToM(24)) {
                return true;
            }
        }
        return false;
    }

    /** Farthest a corner can be from the center, whatever the heading. */
    private double halfDiagonal() {
        return FastMath.hypot(body.length, body.width) / 2;
    }

    private static double mToInches(double m) {
        return m / 0.0254;
    }

    private boolean spotIsDrivable(double x, double y) {
        double margin = body.length / 2 + inToM(1);
        double half = inToM(72);
        if (Math.abs(x) > half - margin || Math.abs(y) > half - margin) {
            return false;
        }
        if (x * sideSign < halfDiagonal() + inToM(2)) {
            return false; // stay fully on our own side (G402 in AUTO; simpler everywhere)
        }
        for (Circle o : fieldObstacles) {
            if (FastMath.hypot(o.x - x, o.y - y) < o.r + body.length * 0.75) {
                return false;
            }
        }
        for (Circle k : keepOut) {
            if (FastMath.hypot(k.x - x, k.y - y) < k.r) {
                return false;
            }
        }
        return true;
    }

    private double doShoot(Context c) {
        Hive h = c.world.hives.get(body.alliance);
        double[] plan = hivePlan(c, h);
        if (plan == null) {
            desiredVx = 0;
            desiredVy = 0;
            return body.heading;
        }
        double[] tgt = h.upOpeningCenter();
        double aim = Math.atan2(tgt[1] - body.y, tgt[0] - body.x);
        double dist = FastMath.hypot(plan[0] - body.x, plan[1] - body.y);
        driveTo(c, plan[0], plan[1], dist < inToM(20) ? inToM(30) : maxSpeed);
        boolean inPlace = dist < inToM(3) && FastMath.hypot(body.vx, body.vy) < inToM(6);
        boolean aimed = Math.abs(angleDiff(aim, body.heading)) < aimTolerance;
        if (inPlace && aimed && !h.isTipping() && c.now >= spinReadyAt && c.now >= nextShotAt && !held.isEmpty()) {
            shoot(c, h, tgt);
        }
        return aim;
    }

    private void shoot(Context c, Hive h, double[] tgt) {
        Ball b = held.get(0);
        double d = FastMath.hypot(tgt[0] - body.x, tgt[1] - body.y);
        Ballistics f = b.kind.isNectar() ? nectarFlight : pollenFlight;
        double v = f.speedFor(launchPitch, launchHeight, d, tgt[2], maxLaunchSpeed);
        if (Double.isNaN(v)) {
            return;
        }
        launch(c, b, v, launchPitch, body.heading);
    }

    private void launch(Context c, Ball b, double v, double pitch, double yaw) {
        v *= 1 + rng.nextGaussian() * speedSpread;
        pitch += rng.nextGaussian() * angleSpread;
        yaw += rng.nextGaussian() * angleSpread;
        double horiz = v * Math.cos(pitch);
        held.remove(b);
        c.world.launch(b, body.x, body.y, launchHeight, horiz * Math.cos(yaw) + body.vx, horiz * Math.sin(yaw) + body.vy,
                v * Math.sin(pitch), body.id, body.alliance, c.now);
        nextShotAt = c.now + shotInterval;
        shots++;
        if (held.isEmpty()) {
            spinReadyAt = -1;
        }
    }

    // ---------------------------------------------------------------- FLOWERS (endgame)

    private double doFlower(Context c) {
        // Nearest FLOWER on our side of the field.
        Flower best = null;
        for (Flower f : c.world.flowers) {
            if (f.x * sideSign > 0 && (best == null
                    || FastMath.hypot(f.x - body.x, f.y - body.y) < FastMath.hypot(best.x - body.x, best.y - body.y))) {
                best = f;
            }
        }
        if (best == null) {
            task = Task.SHOOT;
            return body.heading;
        }
        // Stand ~24 in from it, on the field side, facing it.
        double ax = -Math.signum(best.x) * (Math.abs(best.x) > Math.abs(best.y) ? 1 : 0);
        double ay = -Math.signum(best.y) * (Math.abs(best.y) >= Math.abs(best.x) ? 1 : 0);
        double sx = best.x + ax * inToM(24);
        double sy = best.y + ay * inToM(24);
        double aim = Math.atan2(best.y - body.y, best.x - body.x);
        double dist = FastMath.hypot(sx - body.x, sy - body.y);
        driveTo(c, sx, sy, dist < inToM(20) ? inToM(30) : maxSpeed);
        boolean inPlace = dist < inToM(3) && FastMath.hypot(body.vx, body.vy) < inToM(6);
        if (inPlace && Math.abs(angleDiff(aim, body.heading)) < aimTolerance && c.now >= nextShotAt) {
            Ball nectar = null;
            for (Ball b : held) {
                if (b.kind.isNectar()) {
                    nectar = b;
                }
            }
            if (nectar != null) {
                double lob = Math.toRadians(62);
                double d = FastMath.hypot(best.x - body.x, best.y - body.y);
                double v = nectarFlight.speedFor(lob, launchHeight, d, best.topZ + inToM(0.5), maxLaunchSpeed);
                if (!Double.isNaN(v) && c.matchSecondsLeft < 59) {
                    launch(c, nectar, v, lob, body.heading);
                }
            }
        }
        return aim;
    }

    // ---------------------------------------------------------------- collecting

    private boolean eligible(Context c, Ball b) {
        if (b.state != Ball.State.FREE || b.z > b.radius * 2.2 || FastMath.hypot(b.vx, b.vy) > 1.5) {
            return false;
        }
        if (b.kind.isNectar() && b.kind.alliance != body.alliance) {
            return false; // G408: never CONTROL the opponent's NECTAR
        }
        Alliance them = body.alliance.opponent();
        if (c.field.garden.get(them).contains(b.x, b.y) || c.field.loadingZone.get(them).contains(b.x, b.y)) {
            return false; // leave the other alliance's zones alone
        }
        if (c.phase == MatchTimer.Phase.AUTO && b.x * sideSign < halfDiagonal() + inToM(6)) {
            return false; // G402: stay on our side in AUTO
        }
        Double until = blacklist.get(b);
        return until == null || c.now > until;
    }

    private boolean ballNearby(Context c, double within) {
        for (Ball b : c.world.balls) {
            if (eligible(c, b) && FastMath.hypot(b.x - body.x, b.y - body.y) < within) {
                return true;
            }
        }
        return false;
    }

    private double doCollect(Context c) {
        if (target != null && (!eligible(c, target) || c.now - targetSince > 6.0)) {
            if (target.state == Ball.State.FREE) {
                blacklist.put(target, c.now + 10); // couldn't get it: try something else for a while
            }
            releaseClaim(c);
        }
        if (target == null) {
            double bestD = Double.MAX_VALUE;
            for (Ball b : c.world.balls) {
                if (!eligible(c, b)) {
                    continue;
                }
                AiRobot owner = c.claims.get(b);
                if (owner != null && owner != this) {
                    continue;
                }
                double d = FastMath.hypot(b.x - body.x, b.y - body.y);
                if (d < bestD) {
                    bestD = d;
                    target = b;
                }
            }
            if (target == null) {
                desiredVx = 0;
                desiredVy = 0;
                return body.heading;
            }
            targetSince = c.now;
            c.claims.put(target, this);
        }
        double aim = Math.atan2(target.y - body.y, target.x - body.x);
        double d = FastMath.hypot(target.x - body.x, target.y - body.y);
        // Keep driving "through" the ball so the intake reaches it; slow down near it.
        double speed = d < inToM(16) ? inToM(24) : maxSpeed;
        if (Math.abs(angleDiff(aim, body.heading)) > Math.toRadians(35) && d < inToM(20)) {
            speed = inToM(6); // turn to face it before driving in
        }
        driveTo(c, target.x, target.y, speed);
        return aim;
    }

    private void releaseClaim(Context c) {
        if (target != null && c.claims.get(target) == this) {
            c.claims.remove(target);
        }
        target = null;
    }

    private void tryPickup(Context c) {
        if (c.now < nextPickupAt || held.size() >= capacity) {
            return;
        }
        double cs = Math.cos(body.heading);
        double sn = Math.sin(body.heading);
        for (Ball b : c.world.balls) {
            if (b.state != Ball.State.FREE || b.z > b.radius * 2.2 || !(b.kind == ElementKind.POLLEN
                    || b.kind.isNectar() && b.kind.alliance == body.alliance)) {
                continue;
            }
            if (b.launchedAt >= 0 && c.now - b.launchedAt < 1.0 || b.fromTippedHive) {
                continue; // don't catch shots or balls falling from a TIPPED HIVE (G409)
            }
            double dx = b.x - body.x;
            double dy = b.y - body.y;
            double fwd = dx * cs + dy * sn;
            double side = -dx * sn + dy * cs;
            if (fwd > body.length / 2 - b.radius && fwd < body.length / 2 + mouthDepth + b.radius
                    && Math.abs(side) < mouthWidth / 2) {
                c.world.pickUp(b, body.id);
                held.add(b);
                pickups++;
                nextPickupAt = c.now + pickupInterval;
                if (b == target) {
                    releaseClaim(c);
                }
                return;
            }
        }
    }

    private double doOffWall(Context c) {
        double lim = inToM(72) - body.length * 0.75 - inToM(3);
        double x = Math.max(-lim, Math.min(lim, body.x));
        double y = Math.max(-lim, Math.min(lim, body.y));
        if (FastMath.hypot(x - body.x, y - body.y) < inToM(0.5)) {
            desiredVx = 0;
            desiredVy = 0;
        } else {
            driveTo(c, x, y, inToM(30));
        }
        return body.heading;
    }

    // ---------------------------------------------------------------- parking

    private double doPark(Context c) {
        Rect lz = c.field.loadingZone.get(body.alliance);
        // Partly inside the LOADING ZONE, 2 in off the wall (touching the wall would cost LEAVE in AUTO).
        boolean redSide = body.alliance == Alliance.RED;
        double x = redSide ? lz.xMin + body.length / 2 + inToM(2) : lz.xMax - body.length / 2 - inToM(2);
        double yLow = redSide ? lz.yMin + inToM(3) : lz.yMax - inToM(3);
        double yHigh = redSide ? lz.yMax - inToM(3) : lz.yMin + inToM(3);
        double y = slot == 0 ? yLow : yHigh;
        double dist = FastMath.hypot(x - body.x, y - body.y);
        driveTo(c, x, y, dist < inToM(20) ? inToM(25) : maxSpeed);
        return redSide ? 0 : Math.PI;
    }

    // ---------------------------------------------------------------- driving

    /** Sets the desired velocity toward (x, y), steering around FLOWERS, HIVE legs and robots. */
    private void driveTo(Context c, double x, double y, double speedLimit) {
        double dx = x - body.x;
        double dy = y - body.y;
        double d = FastMath.hypot(dx, dy);
        if (d < inToM(0.5)) {
            desiredVx = 0;
            desiredVy = 0;
            stuckSince = -1;
            return;
        }
        double speed = Math.min(speedLimit, Math.sqrt(2 * accel * 0.6 * d));
        double ux = dx / d;
        double uy = dy / d;
        // Avoidance: push away from nearby obstacles, and slide around them.
        double ax = 0;
        double ay = 0;
        double reach = body.length * 0.75 + inToM(6);
        for (Circle o : c.field.obstacles) {
            double[] f = avoid(o.x, o.y, o.r + reach, ux, uy, d);
            ax += f[0] * 1.6;
            ay += f[1] * 1.6;
        }
        for (SimRobot r : c.robots) {
            if (r != body) {
                double[] f = avoid(r.x, r.y, r.length * 0.75 + reach, ux, uy, d);
                ax += f[0] * 1.2;
                ay += f[1] * 1.2;
            }
        }
        double vx = ux + ax;
        double vy = uy + ay;
        // G402: in AUTO, keep well clear of the center line.
        double guard = halfDiagonal() + inToM(6);
        if (c.phase == MatchTimer.Phase.AUTO && body.x * sideSign < guard) {
            vx += sideSign * 3 * (guard - body.x * sideSign) / guard;
        }
        // Stuck? Sidestep for a moment.
        boolean wantMove = speed > inToM(5);
        if (wantMove && FastMath.hypot(body.vx, body.vy) < inToM(3)) {
            if (stuckSince < 0) {
                stuckSince = c.now;
            } else if (c.now - stuckSince > 0.8 && c.now > sidestepUntil) {
                sidestepUntil = c.now + 0.6;
                sidestepSign = rng.nextBoolean() ? 1 : -1;
                stuckSince = c.now;
            }
        } else {
            stuckSince = -1;
        }
        if (c.now < sidestepUntil) {
            vx = -uy * sidestepSign - ux * 0.3;
            vy = ux * sidestepSign - uy * 0.3;
        }
        double n = FastMath.hypot(vx, vy);
        if (n > 1e-9) {
            desiredVx = vx / n * speed;
            desiredVy = vy / n * speed;
        }
        // Hard limit in AUTO (G402): never approach the center line faster than we can
        // stop before a corner could cross it: v <= sqrt(2 * a * gap).
        if (c.phase == MatchTimer.Phase.AUTO) {
            // (0.5 x accel: braking shares the traction budget with steering.)
            double gap = Math.max(0, body.x * sideSign - halfDiagonal() - inToM(2.5));
            double allowed = Math.sqrt(2 * accel * 0.5 * gap);
            if (desiredVx * sideSign < -allowed) {
                desiredVx = -allowed * sideSign;
            }
        }
    }

    /**
     * Steering away from one obstacle at (ox, oy) within {@code influence}:
     * a push straight away plus a slide sideways (around it, on the side that
     * keeps us heading toward the goal). Ignores things behind us or past the goal.
     */
    private double[] avoid(double ox, double oy, double influence, double ux, double uy, double goalDist) {
        double rx = body.x - ox;
        double ry = body.y - oy;
        double dist = FastMath.hypot(rx, ry);
        if (dist >= influence || dist < 1e-6) {
            return new double[] {0, 0};
        }
        double ahead = -(rx * ux + ry * uy); // how far the obstacle is in front of us
        if (ahead < -0.3 * dist || ahead > goalDist + inToM(6)) {
            return new double[] {0, 0};
        }
        rx /= dist;
        ry /= dist;
        double strength = (influence - dist) / influence;
        double tx = -ry;
        double ty = rx;
        if (tx * ux + ty * uy < 0) {
            tx = -tx;
            ty = -ty;
        }
        return new double[] {(rx + tx) * strength * 1.5, (ry + ty) * strength * 1.5};
    }

    /** Accelerates toward the desired velocity (traction-limited) and turns toward the wanted heading. */
    private void move(double dt, double wantHeading) {
        double ex = desiredVx - body.vx;
        double ey = desiredVy - body.vy;
        double e = FastMath.hypot(ex, ey);
        double maxDv = accel * dt;
        if (e > maxDv) {
            ex *= maxDv / e;
            ey *= maxDv / e;
        }
        body.vx += ex;
        body.vy += ey;
        body.intentVx = desiredVx;
        body.intentVy = desiredVy;
        double err = angleDiff(wantHeading, body.heading);
        double wantOmega = Math.max(-maxTurn, Math.min(maxTurn, err * 8));
        double dOmega = Math.max(-maxTurn * 6 * dt, Math.min(maxTurn * 6 * dt, wantOmega - body.omega));
        body.omega += dOmega;
        body.x += body.vx * dt;
        body.y += body.vy * dt;
        body.heading += body.omega * dt;
    }

    static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) {
            d -= 2 * Math.PI;
        }
        while (d < -Math.PI) {
            d += 2 * Math.PI;
        }
        return d;
    }
}
