package org.biobuzz.sim.game;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.field.Field;
import org.biobuzz.sim.geom.Circle;
import org.biobuzz.sim.geom.Rect;
import org.biobuzz.sim.robot.SimRobot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.biobuzz.sim.util.Units.inToM;

/**
 * Every POLLEN and NECTAR ball, both HIVES and all four FLOWERS.
 *
 * Loose balls get simple 3D physics: gravity, air drag (these balls have
 * holes, so drag matters), bouncing on the tiles/walls/robots/pipes, rolling
 * friction, and ball-to-ball contact. Balls resting still "sleep" until
 * something touches them, which keeps the simulation fast.
 *
 * Everything that matters for scoring or rules is reported as an
 * {@link Event} (a CELL catch, a HIVE tip, a FLOWER entry, a ball leaving
 * the field), so the scorekeeper and rule checker can react.
 */
public final class GameWorld {

    private static final double GRAVITY = 9.81;
    private static final double AIR_DENSITY = 1.2;
    private static final double SLEEP_SPEED = 0.01;
    /** A launched ball doesn't collide with the robot that launched it for this long (it's leaving the shooter). */
    private static final double OWN_LAUNCH_GRACE_S = 0.25;

    /** Something happened that scoring or the rules care about. */
    public static final class Event {
        public enum Type { CELL_ENTRY, HIVE_TIP, FLOWER_ENTRY, LEFT_FIELD, NECTAR_ENTERED }

        public final Type type;
        public final double time;
        public final Ball ball;
        public final Alliance alliance; // HIVE / FLOWER owner or NECTAR alliance
        public final int index;         // FLOWER index, or tip count

        Event(Type type, double time, Ball ball, Alliance alliance, int index) {
            this.type = type;
            this.time = time;
            this.ball = ball;
            this.alliance = alliance;
            this.index = index;
        }
    }

    public final List<Ball> balls = new ArrayList<>();
    public final Map<Alliance, Hive> hives = new EnumMap<>(Alliance.class);
    public final List<Flower> flowers = new ArrayList<>();
    private final Field field;
    private final Cfg game;
    private final double wallHeight;
    private final double bounceFloor;
    private final double bounceHard;
    private final double rollingDecel;
    private final double flowerSlop;
    private final double cellSlop;
    private final double pollenMass;
    private final double nectarMass;
    private final double dragK; // 0.5 * rho * Cd (multiply by area)
    private final double humanDelay;
    private final double returnDelay;
    private final Random rng;
    private final List<Event> events = new ArrayList<>();
    /** Legs of the HIVE frame and the crossbar (x1, y1, z1, x2, y2, z2, radius). */
    private final List<double[]> frameBeams = new ArrayList<>();

    // Human player NECTAR (G426/G427): how many each alliance may enter, and when.
    private final Map<Alliance, Integer> nectarAllowed = new EnumMap<>(Alliance.class);
    private final Map<Alliance, Double> nextNectarTime = new EnumMap<>(Alliance.class);
    private boolean endgameNectarReleased;

    public GameWorld(Cfg game, Field field, Random rng) {
        this.game = game;
        this.field = field;
        this.rng = rng;
        Cfg phys = game.obj("elementPhysics");
        wallHeight = inToM(game.num("field.wallHeight"));
        bounceFloor = phys.num("bounceFloor");
        bounceHard = phys.num("bounceHard");
        rollingDecel = phys.num("rollingDecel");
        flowerSlop = inToM(phys.num("flowerEntrySlop"));
        cellSlop = phys.num("cellEntrySlop");
        pollenMass = phys.num("pollenMassG") / 1000.0;
        nectarMass = phys.num("nectarMassG") / 1000.0;
        dragK = 0.5 * AIR_DENSITY * phys.num("dragCoefficient");
        humanDelay = phys.num("humanPlayerDelaySeconds");
        returnDelay = phys.num("outOfFieldReturnSeconds");
        for (Alliance a : Alliance.values()) {
            hives.put(a, new Hive(a, game.obj("hive"), phys));
            nectarAllowed.put(a, 0);
            nextNectarTime.put(a, Double.MAX_VALUE);
        }
        int i = 0;
        for (Circle c : field.flowers) {
            flowers.add(new Flower(i++, c.x, c.y, game.obj("flower")));
        }
        buildFrame(game.obj("hive"));
    }

    private void buildFrame(Cfg hive) {
        double hx = inToM(hive.num("frameWidth")) / 2;
        double hy = inToM(hive.num("frameDepth")) / 2;
        double top = inToM(hive.num("pivotHeight"));
        double r = inToM(hive.num("legRadius"));
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                frameBeams.add(new double[] {sx * hx, sy * hy, 0, sx * hx, 0, top, r});
            }
        }
        frameBeams.add(new double[] {-hx, 0, top, hx, 0, top, r * 1.2});
    }

    // =====================================================================
    // Staging (§10.3.1)
    // =====================================================================

    /** Creates every ball and puts it where the field staff would before a match. */
    public void stage(List<SimRobot> robots, Map<SimRobot, List<Ball>> preloads) {
        Cfg el = game.obj("elements");
        Cfg st = game.obj("staging");
        double pollenR = inToM(el.num("pollen.diameter")) / 2;
        double nectarR = inToM(el.num("nectar.diameter")) / 2;
        int id = 0;
        // A.i: 4 POLLEN in each FLOWER.
        for (Flower f : flowers) {
            for (int k = 0; k < st.integer("pollenInEachFlower"); k++) {
                Ball b = new Ball(id++, ElementKind.POLLEN, pollenR, pollenMass);
                balls.add(b);
                f.stage(b);
            }
        }
        // A.ii/iii: 4 POLLEN in a line in each GARDEN, from the corner nearest the ALLIANCE AREA.
        for (Alliance a : Alliance.values()) {
            Rect g = field.garden.get(a);
            boolean redSide = a == Alliance.RED;
            for (int k = 0; k < st.integer("pollenInEachGarden"); k++) {
                Ball b = new Ball(id++, ElementKind.POLLEN, pollenR, pollenMass);
                double along = pollenR + k * 2 * pollenR + jitter(0.005);
                double x = redSide ? g.xMin + along : g.xMax - along;
                double y = redSide ? g.yMin + pollenR : g.yMax - pollenR;
                b.setPosition(x, y, pollenR);
                b.asleep = true;
                balls.add(b);
            }
        }
        // A.iv: 4 POLLEN pre-loaded in each ROBOT.
        for (SimRobot r : robots) {
            List<Ball> held = new ArrayList<>();
            for (int k = 0; k < st.integer("pollenPreloadPerRobot"); k++) {
                Ball b = new Ball(id++, ElementKind.POLLEN, pollenR, pollenMass);
                b.state = Ball.State.HELD;
                b.holder = r.id;
                b.setPosition(r.x, r.y, 0.2);
                balls.add(b);
                held.add(b);
            }
            preloads.put(r, held);
        }
        // B.i: 3 NECTAR in each alliance's up-facing CELL; B.ii: 5 in each ALLIANCE AREA.
        for (Alliance a : Alliance.values()) {
            for (int k = 0; k < st.integer("nectarInUpCell"); k++) {
                Ball b = new Ball(id++, ElementKind.nectarOf(a), nectarR, nectarMass);
                balls.add(b);
                hives.get(a).stage(b);
            }
            for (int k = 0; k < st.integer("nectarInAllianceArea"); k++) {
                Ball b = new Ball(id++, ElementKind.nectarOf(a), nectarR, nectarMass);
                b.state = Ball.State.STAGED;
                Rect area = field.allianceArea.get(a);
                b.setPosition((area.xMin + area.xMax) / 2 + (a == Alliance.RED ? 0.3 : -0.3), k * 0.1 - 0.2, nectarR);
                balls.add(b);
            }
        }
    }

    private double jitter(double meters) {
        return (rng.nextDouble() * 2 - 1) * meters; // "some minor variance in placement" (§10.3.1)
    }

    // =====================================================================
    // Physics step
    // =====================================================================

    /** Advances every loose ball, the HIVES and the FLOWERS by dt. */
    public void step(double now, double dt, List<SimRobot> robots) {
        for (Hive h : hives.values()) {
            List<Ball> spilled = h.step(now);
            if (spilled != null) {
                events.add(new Event(Event.Type.HIVE_TIP, now, null, h.alliance, h.tips));
                // G426: each tip lets the human player enter one more NECTAR.
                nectarAllowed.merge(h.alliance, 1, Integer::sum);
                if (nextNectarTime.get(h.alliance) == Double.MAX_VALUE) {
                    nextNectarTime.put(h.alliance, now + humanDelay);
                }
            }
        }
        for (Flower f : flowers) {
            f.step(now);
        }
        humanPlayer(now);

        for (Ball b : balls) {
            if (b.state == Ball.State.OUT_OF_FIELD && !b.kind.isNectar() && now - b.outOfFieldAt > returnDelay) {
                returnToField(b);
            }
            if (b.state != Ball.State.FREE) {
                continue;
            }
            if (b.asleep) {
                // Sleeping balls skip physics, but a robot driving into one wakes it up.
                for (SimRobot r : robots) {
                    bounceOffRobot(b, r);
                }
                if (b.asleep) {
                    continue;
                }
            }
            double px = b.x;
            double py = b.y;
            double pz = b.z;
            integrate(b, dt);
            collideFieldAndRobots(b, px, py, pz, robots, now);
        }
        collideBalls();
    }

    private void integrate(Ball b, double dt) {
        // Air drag: F = 0.5 * rho * Cd * area * v^2, opposite to the motion.
        double v = b.speed();
        if (v > 1e-6) {
            double area = Math.PI * b.radius * b.radius;
            double decel = dragK * area * v * v / b.mass;
            double k = Math.min(decel * dt / v, 1.0);
            b.vx -= b.vx * k;
            b.vy -= b.vy * k;
            b.vz -= b.vz * k;
        }
        if (b.onGround() && Math.abs(b.vz) < 0.05) {
            // Rolling on the tiles.
            b.vz = 0;
            b.z = b.radius;
            double hv = Math.hypot(b.vx, b.vy);
            if (hv > 1e-9) {
                double dv = Math.min(rollingDecel * dt, hv);
                b.vx -= b.vx / hv * dv;
                b.vy -= b.vy / hv * dv;
            }
        } else {
            b.vz -= GRAVITY * dt;
        }
        b.x += b.vx * dt;
        b.y += b.vy * dt;
        b.z += b.vz * dt;
        if (b.z < b.radius) {
            b.z = b.radius;
            if (b.vz < 0) {
                b.vz = -b.vz * bounceFloor;
                if (b.vz < 0.15) {
                    b.vz = 0;
                }
                // Touching the floor: a tipped-HIVE ball is fair game again (G409).
                b.fromTippedHive = false;
            }
        }
        if (b.onGround() && b.vz == 0 && Math.hypot(b.vx, b.vy) < SLEEP_SPEED) {
            b.stop();
            b.asleep = true;
        }
    }

    private void collideFieldAndRobots(Ball b, double px, double py, double pz, List<SimRobot> robots, double now) {
        double half = field.half;
        // Walls (only up to the wall's height; above it the ball can leave the field).
        if (b.z < wallHeight + b.radius) {
            if (b.x > half - b.radius) {
                b.x = half - b.radius;
                b.vx = -Math.abs(b.vx) * bounceHard;
                b.fromTippedHive = false;
            } else if (b.x < -half + b.radius) {
                b.x = -half + b.radius;
                b.vx = Math.abs(b.vx) * bounceHard;
                b.fromTippedHive = false;
            }
            if (b.y > half - b.radius) {
                b.y = half - b.radius;
                b.vy = -Math.abs(b.vy) * bounceHard;
                b.fromTippedHive = false;
            } else if (b.y < -half + b.radius) {
                b.y = -half + b.radius;
                b.vy = Math.abs(b.vy) * bounceHard;
                b.fromTippedHive = false;
            }
        } else if (Math.abs(b.x) > half + b.radius || Math.abs(b.y) > half + b.radius) {
            b.state = Ball.State.OUT_OF_FIELD;
            b.outOfFieldAt = now;
            b.stop();
            events.add(new Event(Event.Type.LEFT_FIELD, now, b, b.kind.alliance, -1));
            if (b.kind.isNectar()) {
                // The drive team may re-enter their own NECTAR through the LOADING ZONE (G426 note).
                nectarAllowed.merge(b.kind.alliance, 1, Integer::sum);
                nextNectarTime.merge(b.kind.alliance, now + humanDelay, Math::min);
            }
            return;
        }

        // FLOWERS: drop in through the top, or bounce off the pipes.
        for (Flower f : flowers) {
            if (f.tryEnter(b, pz, flowerSlop)) {
                events.add(new Event(Event.Type.FLOWER_ENTRY, now, b, null, f.index));
                return;
            }
            if (b.z < f.topZ + b.radius) {
                bounceOffCircle(b, f.x, f.y, f.pipeRadius);
            }
        }
        // HIVE CELLS: into the up-facing one, or off the walls.
        for (Hive h : hives.values()) {
            if (h.interact(b, px, py, pz, bounceHard * 0.6, cellSlop)) {
                events.add(new Event(Event.Type.CELL_ENTRY, now, b, h.alliance, -1));
                return;
            }
        }
        // HIVE frame legs and crossbar.
        for (double[] beam : frameBeams) {
            bounceOffBeam(b, beam);
        }
        // Robots (bulldozing, deflecting). A just-launched ball passes out of its own shooter.
        for (SimRobot r : robots) {
            if (r.id.equals(b.launchedBy) && now - b.launchedAt < OWN_LAUNCH_GRACE_S) {
                continue;
            }
            bounceOffRobot(b, r);
        }
    }

    private void bounceOffCircle(Ball b, double cx, double cy, double cr) {
        double dx = b.x - cx;
        double dy = b.y - cy;
        double d = Math.hypot(dx, dy);
        double min = cr + b.radius;
        if (d >= min || d < 1e-9) {
            return;
        }
        double nx = dx / d;
        double ny = dy / d;
        b.x = cx + nx * min;
        b.y = cy + ny * min;
        double vn = b.vx * nx + b.vy * ny;
        if (vn < 0) {
            b.vx -= (1 + bounceHard) * vn * nx;
            b.vy -= (1 + bounceHard) * vn * ny;
        }
        b.fromTippedHive = false;
    }

    private void bounceOffBeam(Ball b, double[] s) {
        double ax = s[0];
        double ay = s[1];
        double az = s[2];
        double bx = s[3] - ax;
        double by = s[4] - ay;
        double bz = s[5] - az;
        double len2 = bx * bx + by * by + bz * bz;
        double t = Math.max(0, Math.min(1, ((b.x - ax) * bx + (b.y - ay) * by + (b.z - az) * bz) / len2));
        double cx = ax + bx * t;
        double cy = ay + by * t;
        double cz = az + bz * t;
        double dx = b.x - cx;
        double dy = b.y - cy;
        double dz = b.z - cz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double min = s[6] + b.radius;
        if (d >= min || d < 1e-9) {
            return;
        }
        double nx = dx / d;
        double ny = dy / d;
        double nz = dz / d;
        b.x = cx + nx * min;
        b.y = cy + ny * min;
        b.z = Math.max(b.radius, cz + nz * min);
        double vn = b.vx * nx + b.vy * ny + b.vz * nz;
        if (vn < 0) {
            b.vx -= (1 + bounceHard) * vn * nx;
            b.vy -= (1 + bounceHard) * vn * ny;
            b.vz -= (1 + bounceHard) * vn * nz;
        }
        b.fromTippedHive = false;
    }

    /** A ball against a robot's body: pushed out, and it picks up the robot's motion. */
    private void bounceOffRobot(Ball b, SimRobot r) {
        if (b.z - b.radius > r.height) {
            return;
        }
        double c = Math.cos(r.heading);
        double s = Math.sin(r.heading);
        double dx = b.x - r.x;
        double dy = b.y - r.y;
        double lx = dx * c + dy * s;
        double ly = -dx * s + dy * c;
        double hl = r.length / 2;
        double hw = r.width / 2;
        double qx = Math.max(-hl, Math.min(hl, lx));
        double qy = Math.max(-hw, Math.min(hw, ly));
        double ex = lx - qx;
        double ey = ly - qy;
        double d = Math.hypot(ex, ey);
        if (d >= b.radius) {
            return;
        }
        double nx;
        double ny;
        double push;
        if (d > 1e-9) {
            nx = ex / d;
            ny = ey / d;
            push = b.radius - d;
        } else {
            // Center inside the robot (e.g. spawned there): push out the nearest side.
            if (hl - Math.abs(lx) < hw - Math.abs(ly)) {
                nx = Math.signum(lx);
                ny = 0;
                push = hl - Math.abs(lx) + b.radius;
            } else {
                nx = 0;
                ny = Math.signum(ly);
                push = hw - Math.abs(ly) + b.radius;
            }
        }
        double fnx = nx * c - ny * s;
        double fny = nx * s + ny * c;
        b.x += fnx * push;
        b.y += fny * push;
        // Velocity of the robot's surface at the contact point.
        double cpx = r.x + qx * c - qy * s;
        double cpy = r.y + qx * s + qy * c;
        double svx = r.vx - r.omega * (cpy - r.y);
        double svy = r.vy + r.omega * (cpx - r.x);
        double rvx = b.vx - svx;
        double rvy = b.vy - svy;
        double vn = rvx * fnx + rvy * fny;
        if (vn < 0) {
            rvx -= (1 + bounceHard) * vn * fnx;
            rvy -= (1 + bounceHard) * vn * fny;
        }
        b.vx = svx + rvx;
        b.vy = svy + rvy;
        b.asleep = false;
    }

    /** Ball-to-ball contact (loose balls only). Sleeping balls are woken when hit. */
    private void collideBalls() {
        int n = balls.size();
        for (int i = 0; i < n; i++) {
            Ball a = balls.get(i);
            if (a.state != Ball.State.FREE) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                Ball b = balls.get(j);
                if (b.state != Ball.State.FREE || (a.asleep && b.asleep)) {
                    continue;
                }
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double dz = b.z - a.z;
                double min = a.radius + b.radius;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= min * min || d2 < 1e-12) {
                    continue;
                }
                double d = Math.sqrt(d2);
                double nx = dx / d;
                double ny = dy / d;
                double nz = dz / d;
                double overlap = min - d;
                double wa = b.mass / (a.mass + b.mass);
                double wb = a.mass / (a.mass + b.mass);
                a.x -= nx * overlap * wa;
                a.y -= ny * overlap * wa;
                a.z = Math.max(a.radius, a.z - nz * overlap * wa);
                b.x += nx * overlap * wb;
                b.y += ny * overlap * wb;
                b.z = Math.max(b.radius, b.z + nz * overlap * wb);
                double rv = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny + (b.vz - a.vz) * nz;
                if (rv < 0) {
                    double j2 = -(1 + bounceHard) * rv / (1 / a.mass + 1 / b.mass);
                    a.vx -= j2 * nx / a.mass;
                    a.vy -= j2 * ny / a.mass;
                    a.vz -= j2 * nz / a.mass;
                    b.vx += j2 * nx / b.mass;
                    b.vy += j2 * ny / b.mass;
                    b.vz += j2 * nz / b.mass;
                }
                a.asleep = false;
                b.asleep = false;
                a.fromTippedHive = false;
                b.fromTippedHive = false;
            }
        }
    }

    // =====================================================================
    // Human player and field staff
    // =====================================================================

    /** Called by the match when 60 s remain: all remaining NECTAR may be entered (G426.B). */
    public void releaseEndgameNectar(double now) {
        if (endgameNectarReleased) {
            return;
        }
        endgameNectarReleased = true;
        for (Alliance a : Alliance.values()) {
            nectarAllowed.put(a, 99);
            nextNectarTime.merge(a, now + humanDelay, Math::min);
        }
    }

    /** The human player drops one NECTAR into the LOADING ZONE when allowed (G427). */
    private void humanPlayer(double now) {
        for (Alliance a : Alliance.values()) {
            if (now < nextNectarTime.get(a) || nectarAllowed.get(a) <= 0) {
                continue;
            }
            Ball next = null;
            for (Ball b : balls) {
                if (b.state == Ball.State.STAGED && b.kind.alliance == a
                        || b.state == Ball.State.OUT_OF_FIELD && b.kind.alliance == a) {
                    next = b;
                    break;
                }
            }
            if (next == null) {
                nextNectarTime.put(a, Double.MAX_VALUE);
                continue;
            }
            Rect lz = field.loadingZone.get(a);
            boolean red = a == Alliance.RED;
            double x = red ? lz.xMin + next.radius + 0.01 : lz.xMax - next.radius - 0.01;
            double y = lz.yMin + next.radius + rng.nextDouble() * (lz.yMax - lz.yMin - 2 * next.radius);
            next.state = Ball.State.FREE;
            next.setPosition(x, y, next.radius + 0.05);
            next.vx = (red ? 1 : -1) * 0.2;
            next.vy = 0;
            next.vz = 0;
            next.asleep = false;
            nectarAllowed.merge(a, -1, Integer::sum);
            nextNectarTime.put(a, nectarAllowed.get(a) > 0 ? now + 1.5 : Double.MAX_VALUE);
            events.add(new Event(Event.Type.NECTAR_ENTERED, now, next, a, -1));
        }
    }

    /** Field staff put a POLLEN that left the field back in (§10.8 - exact spot is a TODO). */
    private void returnToField(Ball b) {
        double lim = field.half - b.radius;
        b.state = Ball.State.FREE;
        b.setPosition(Math.max(-lim, Math.min(lim, b.x)) * 0.95, Math.max(-lim, Math.min(lim, b.y)) * 0.95, b.radius);
        b.stop();
        b.asleep = false;
    }

    // =====================================================================
    // For robots: grabbing and launching
    // =====================================================================

    /** A robot LAUNCHES a ball from a point with a velocity. */
    public void launch(Ball b, double x, double y, double z, double vx, double vy, double vz,
                       String robotId, Alliance alliance, double now) {
        b.state = Ball.State.FREE;
        b.holder = null;
        b.setPosition(x, y, z);
        b.vx = vx;
        b.vy = vy;
        b.vz = vz;
        b.asleep = false;
        b.launchedBy = robotId;
        b.launchedByAlliance = alliance;
        b.launchedAt = now;
        b.fromTippedHive = false;
    }

    /** A robot's intake takes a ball. */
    public void pickUp(Ball b, String robotId) {
        b.state = Ball.State.HELD;
        b.holder = robotId;
        b.stop();
        b.asleep = false;
    }

    /** A robot drops/spits a ball out onto the field. */
    public void release(Ball b, double x, double y, double vx, double vy) {
        b.state = Ball.State.FREE;
        b.holder = null;
        b.setPosition(x, y, b.radius);
        b.vx = vx;
        b.vy = vy;
        b.vz = 0;
        b.asleep = false;
    }

    /** POLLEN from a FLOWER's bottom pocket (G418.B), or null if the pocket is empty. */
    public Ball takeFromFlower(Flower f, String robotId) {
        Ball b = f.takeFromPocket();
        if (b != null) {
            pickUp(b, robotId);
        }
        return b;
    }

    /** Events since the last call (the caller takes ownership). */
    public List<Event> drainEvents() {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    public Field field() {
        return field;
    }
}
