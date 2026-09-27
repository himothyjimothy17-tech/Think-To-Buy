package org.biobuzz.sim.game;

import org.biobuzz.sim.util.FastMath;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.field.Field;
import org.biobuzz.sim.geom.Circle;
import org.biobuzz.sim.robot.ElementCarrier;
import org.biobuzz.sim.robot.SimRobot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.biobuzz.sim.util.Units.inToM;

/**
 * Watches every robot for the game rules the simulator can judge, and
 * records a {@link Foul} with the penalty the manual gives (§11).
 *
 * Only rules with a points penalty that isn't "if STRATEGIC" change the
 * score: G402, G407 (6+ elements, the manual's STRATEGIC example A), G410 and
 * G421. The rest are VERBAL WARNINGS - logged so we fix them before a
 * referee sees them.
 */
public final class RuleChecker {

    public enum Penalty { WARNING, MINOR, MAJOR }

    /** One rule violation. */
    public static final class Foul {
        public final double time;
        public final String robot;
        public final Alliance alliance;
        public final String rule;
        public final Penalty penalty;
        public final String description;

        Foul(double time, String robot, Alliance alliance, String rule, Penalty penalty, String description) {
            this.time = time;
            this.robot = robot;
            this.alliance = alliance;
            this.rule = rule;
            this.penalty = penalty;
            this.description = description;
        }

        @Override
        public String toString() {
            return String.format("%s %s (%s): %s", rule, penalty == Penalty.WARNING ? "VERBAL WARNING" : penalty + " FOUL",
                    robot, description);
        }
    }

    private final Field field;
    private final Cfg rules;
    private final int minorPts;
    private final int majorPts;
    private final double momentary;
    private final double pinSeconds;
    private final double pinSeparation;
    private final List<Foul> fouls = new ArrayList<>();
    private final List<Foul> fresh = new ArrayList<>();

    // Per-robot state
    private final Map<String, Double> overLimitSince = new HashMap<>();
    private final Set<String> once = new HashSet<>();
    private final Map<String, Double> pinStart = new HashMap<>();
    private final Map<String, Integer> pinFoulsGiven = new HashMap<>();
    private final Map<String, Double> pinSeparatedSince = new HashMap<>();

    public RuleChecker(Cfg game, Field field) {
        this.field = field;
        this.rules = game.obj("robotRules");
        this.minorPts = game.integer("points.minorFoul");
        this.majorPts = game.integer("points.majorFoul");
        this.momentary = rules.num("momentarySeconds");
        this.pinSeconds = rules.num("pinSeconds");
        this.pinSeparation = inToM(rules.num("pinSeparation"));
    }

    public List<Foul> all() {
        return fouls;
    }

    /** Fouls found since the last call. */
    public List<Foul> drainNew() {
        List<Foul> out = new ArrayList<>(fresh);
        fresh.clear();
        return out;
    }

    public int points(Foul f) {
        return f.penalty == Penalty.MAJOR ? majorPts : f.penalty == Penalty.MINOR ? minorPts : 0;
    }

    private void add(double t, SimRobot r, String rule, Penalty p, String desc) {
        Foul f = new Foul(t, r.id, r.alliance, rule, p, desc);
        fouls.add(f);
        fresh.add(f);
    }

    private void addOnce(String key, double t, SimRobot r, String rule, Penalty p, String desc) {
        if (once.add(key)) {
            add(t, r, rule, p, desc);
        }
    }

    // =====================================================================
    // G304: set up correctly (checked when the match starts)
    // =====================================================================

    /** Returns a list of problems with a robot's starting position (empty = legal). */
    public List<String> checkStart(SimRobot r, int preloads, double robotLength, double robotWidth, double robotHeight) {
        List<String> problems = new ArrayList<>();
        double cube = inToM(rules.num("startingCube"));
        if (robotLength > cube + 1e-6 || robotWidth > cube + 1e-6 || robotHeight > cube + 1e-6) {
            problems.add("G304.F / R102: bigger than the 18 in starting cube");
        }
        double sideSign = r.alliance == Alliance.RED ? -1 : 1;
        for (double[] c : r.corners()) {
            if (c[0] * sideSign < -1e-6) {
                problems.add("G304.A: not fully on its own alliance's side (columns " + (sideSign < 0 ? "A-C" : "D-F") + ")");
                break;
            }
        }
        boolean touching = false;
        for (double[] c : r.corners()) {
            if (field.half - Math.abs(c[0]) < 0.006 || field.half - Math.abs(c[1]) < 0.006) {
                touching = true;
            }
        }
        if (!touching) {
            problems.add("G304.C: not touching the FIELD perimeter wall");
        }
        if (ScoreKeeper.overlaps(r, field.loadingZone.get(r.alliance))) {
            problems.add("G304.E: in the LOADING ZONE");
        }
        for (Circle f : field.flowers) {
            if (distanceToRobot(r, f.x, f.y) < inToM(2.2)) {
                problems.add("G304.D: contacting a FLOWER's scoring volume");
            }
        }
        if (preloads != 4) {
            problems.add("G304.G: must start with exactly 4 pre-loaded POLLEN (has " + preloads + ")");
        }
        return problems;
    }

    // =====================================================================
    // Every step
    // =====================================================================

    /**
     * @param phase         current match phase name (AUTO, TRANSITION, TELEOP, POST_MATCH...)
     * @param secondsLeft   seconds left in the MATCH (for G410)
     * @param powered       per robot: is any actuator powered right now? (G403/G404)
     */
    public void step(double now, String phase, double phaseTime, List<SimRobot> robots,
                     Map<SimRobot, ElementCarrier> carriers, Map<SimRobot, Boolean> powered,
                     List<GameWorld.Event> events, double secondsLeftInMatch) {
        for (SimRobot r : robots) {
            ElementCarrier c = carriers.get(r);
            int held = c == null ? 0 : c.heldCount();

            // G407: no more than 4 at a time. 5+ for longer than MOMENTARY is a
            // warning; 6+ is the manual's STRATEGIC example A -> MAJOR.
            if (held >= 5) {
                double since = overLimitSince.computeIfAbsent(r.id, k -> now);
                if (now - since > momentary) {
                    if (held >= 6) {
                        addOnce("G407major" + r.id, now, r, "G407", Penalty.MAJOR, "controls " + held + " SCORING ELEMENTS");
                    } else {
                        addOnce("G407warn" + r.id, now, r, "G407", Penalty.WARNING, "controlled 5 SCORING ELEMENTS for over 3 s");
                    }
                }
            } else {
                overLimitSince.remove(r.id);
            }
            // G408: don't CONTROL opponent NECTAR.
            if (c != null) {
                for (Ball b : c.heldBalls()) {
                    if (b.kind.isNectar() && b.kind.alliance != r.alliance) {
                        addOnce("G408" + r.id + b.id, now, r, "G408", Penalty.WARNING, "controls opponent NECTAR");
                    }
                }
            }
            boolean moving = FastMath.hypot(r.vx, r.vy) > 0.03 || Math.abs(r.omega) > 0.2;
            boolean isPowered = Boolean.TRUE.equals(powered.get(r));
            // G403: motionless between AUTO and TELEOP (inertia is fine - allow 1 s to coast).
            if (phase.equals("TRANSITION") && phaseTime > 1.0 && isPowered) {
                addOnce("G403" + r.id, now, r, "G403", Penalty.WARNING, "powered movement during the AUTO-TELEOP transition");
            }
            // G404: motionless after the end of TELEOP.
            if (phase.equals("POST_MATCH") && phaseTime > 0.5 && isPowered && moving) {
                addOnce("G404" + r.id, now, r, "G404", Penalty.WARNING, "powered movement after the match ended");
            }
            // G402: during AUTO don't disrupt the other alliance (contact on their side).
            if (phase.equals("AUTO")) {
                double sideSign = r.alliance == Alliance.RED ? -1 : 1;
                boolean onTheirSide = false;
                for (double[] cr : r.corners()) {
                    if (cr[0] * sideSign < -0.01) {
                        onTheirSide = true;
                    }
                }
                if (onTheirSide) {
                    addOnce("G402cross" + r.id, now, r, "G402", Penalty.WARNING,
                            "entered the opponent's side during AUTO (risky - a MAJOR FOUL if it disrupts them)");
                    for (SimRobot o : robots) {
                        if (o.alliance != r.alliance && touching(r, o)) {
                            addOnce("G402" + r.id, now, r, "G402", Penalty.MAJOR, "contacted an opponent on their side during AUTO");
                        }
                    }
                }
            }
            // G417: don't meddle with the HIVE frame (bumping it).
            if (touchingHiveFrame(r)) {
                double speed = FastMath.hypot(r.vx, r.vy);
                addOnce("G417" + r.id + (int) (now / 5), now, r, "G417", Penalty.WARNING,
                        String.format("contacted the HIVE frame at %.0f in/s", speed / 0.0254));
            }
        }
        pins(now, robots);

        for (GameWorld.Event e : events) {
            if (e.type == GameWorld.Event.Type.FLOWER_ENTRY && e.ball.kind.isNectar() && e.ball.launchedBy != null
                    && secondsLeftInMatch > 60.0) {
                SimRobot r = byId(robots, e.ball.launchedBy);
                if (r != null) {
                    add(now, r, "G410", Penalty.MAJOR, "NECTAR entered a FLOWER before the last 60 seconds");
                }
            }
            if (e.type == GameWorld.Event.Type.CELL_ENTRY && e.ball.launchedByAlliance != null
                    && e.ball.launchedByAlliance != e.alliance) {
                SimRobot r = byId(robots, e.ball.launchedBy);
                if (r != null) {
                    add(now, r, "G417", Penalty.WARNING, "LAUNCHED into the opponent's HIVE");
                }
            }
            if (e.type == GameWorld.Event.Type.CAUGHT_FROM_HIVE) {
                SimRobot r = byId(robots, e.robotId);
                if (r != null) {
                    add(now, r, "G409", Penalty.WARNING, "caught/deflected a ball falling from a TIPPED HIVE");
                }
            }
        }
    }

    /** G421: a PIN lasts at most 3 s. A MAJOR FOUL, plus another every 3 s it continues. */
    private void pins(double now, List<SimRobot> robots) {
        for (SimRobot a : robots) {
            for (SimRobot b : robots) {
                if (a.alliance == b.alliance) {
                    continue;
                }
                String key = a.id + ">" + b.id;
                boolean contact = touching(a, b);
                // A pins B if A is pushing toward B and B can't move (against a wall or field element).
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double d = FastMath.hypot(dx, dy);
                boolean pushing = d > 1e-6 && (a.intentVx * dx + a.intentVy * dy) / d > 0.1;
                boolean stuck = FastMath.hypot(b.vx, b.vy) < 0.05 && (blockedByField(b) || blockedByOthers(b, a, robots));
                if (contact && pushing && stuck) {
                    pinSeparatedSince.remove(key);
                    double start = pinStart.computeIfAbsent(key, k -> now);
                    int due = (int) Math.floor((now - start) / pinSeconds);
                    int given = pinFoulsGiven.getOrDefault(key, 0);
                    if (due > given) {
                        pinFoulsGiven.put(key, due);
                        add(now, a, "G421", Penalty.MAJOR, String.format("PINNED %s for %.0f s", b.id, now - start));
                    }
                } else if (pinStart.containsKey(key)) {
                    // The count ends once they've been 2 ft apart for 3 s (G421.A).
                    if (d > pinSeparation + (a.length + b.length) / 2) {
                        double since = pinSeparatedSince.computeIfAbsent(key, k -> now);
                        if (now - since > pinSeconds) {
                            pinStart.remove(key);
                            pinFoulsGiven.remove(key);
                            pinSeparatedSince.remove(key);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ geometry

    private static SimRobot byId(List<SimRobot> robots, String id) {
        for (SimRobot r : robots) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** Two robots touching (their footprints within 1 cm). */
    static boolean touching(SimRobot a, SimRobot b) {
        double[][] axes = {
            {Math.cos(a.heading), Math.sin(a.heading)}, {-Math.sin(a.heading), Math.cos(a.heading)},
            {Math.cos(b.heading), Math.sin(b.heading)}, {-Math.sin(b.heading), Math.cos(b.heading)},
        };
        double[][] ca = a.corners();
        double[][] cb = b.corners();
        for (double[] ax : axes) {
            double a0 = Double.MAX_VALUE;
            double a1 = -Double.MAX_VALUE;
            double b0 = Double.MAX_VALUE;
            double b1 = -Double.MAX_VALUE;
            for (double[] c : ca) {
                double p = c[0] * ax[0] + c[1] * ax[1];
                a0 = Math.min(a0, p);
                a1 = Math.max(a1, p);
            }
            for (double[] c : cb) {
                double p = c[0] * ax[0] + c[1] * ax[1];
                b0 = Math.min(b0, p);
                b1 = Math.max(b1, p);
            }
            if (a1 + 0.01 < b0 || b1 + 0.01 < a0) {
                return false;
            }
        }
        return true;
    }

    private boolean blockedByField(SimRobot r) {
        for (double[] c : r.corners()) {
            if (field.half - Math.abs(c[0]) < 0.01 || field.half - Math.abs(c[1]) < 0.01) {
                return true;
            }
        }
        for (Circle o : field.obstacles) {
            if (distanceToRobot(r, o.x, o.y) < o.r + 0.01) {
                return true;
            }
        }
        return false;
    }

    private boolean blockedByOthers(SimRobot b, SimRobot pinner, List<SimRobot> robots) {
        for (SimRobot o : robots) {
            if (o != b && o != pinner && touching(b, o)) {
                return true; // transitive: pinned against another robot
            }
        }
        return false;
    }

    private boolean touchingHiveFrame(SimRobot r) {
        for (Circle o : field.obstacles) {
            if (o.label.startsWith("HIVE") && distanceToRobot(r, o.x, o.y) < o.r + 0.005) {
                return true;
            }
        }
        return false;
    }

    /** Distance from a point to the robot's footprint (0 if inside). */
    static double distanceToRobot(SimRobot r, double x, double y) {
        double c = Math.cos(r.heading);
        double s = Math.sin(r.heading);
        double lx = (x - r.x) * c + (y - r.y) * s;
        double ly = -(x - r.x) * s + (y - r.y) * c;
        double qx = Math.max(-r.length / 2, Math.min(r.length / 2, lx));
        double qy = Math.max(-r.width / 2, Math.min(r.width / 2, ly));
        return FastMath.hypot(lx - qx, ly - qy);
    }
}
