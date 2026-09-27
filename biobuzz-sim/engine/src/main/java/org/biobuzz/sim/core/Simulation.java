package org.biobuzz.sim.core;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.field.Field;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.Flower;
import org.biobuzz.sim.game.GameWorld;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.game.RuleChecker;
import org.biobuzz.sim.game.ScoreKeeper;
import org.biobuzz.sim.robot.ElementCarrier;
import org.biobuzz.sim.robot.BallMechanisms;
import org.biobuzz.sim.geom.Circle;
import org.biobuzz.sim.geom.Pose2d;
import org.biobuzz.sim.geom.Rect;
import org.biobuzz.sim.hardware.RobotHardwareSim;
import org.biobuzz.sim.hardware.SimDcMotor;
import org.biobuzz.sim.hardware.SimTelemetry;
import org.biobuzz.sim.opmode.OpModeRegistry;
import org.biobuzz.sim.opmode.OpModeRunner;
import org.biobuzz.sim.physics.Battery;
import org.biobuzz.sim.physics.Collisions;
import org.biobuzz.sim.physics.MotorState;
import org.biobuzz.sim.robot.MecanumDrivetrain;
import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.sim.util.JsonOut;
import org.biobuzz.sim.util.SimRandom;
import org.biobuzz.sim.util.Units;
import org.biobuzz.simhooks.SimHooks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.biobuzz.sim.util.Units.inToM;
import static org.biobuzz.sim.util.Units.mToIn;

/**
 * The whole simulation: field, robots, our robot's hardware, the OpMode, and
 * the clock.
 *
 * THREADING: everything in here runs on ONE "physics thread" (see
 * {@link #runRealTime()}). Commands from the browser are queued and applied
 * between physics steps, so the world never changes halfway through a step.
 */
public final class Simulation {

    /** Physics step: 1 ms of simulated time (fixed, so runs are repeatable). */
    public static final long STEP_NS = 1_000_000L;
    private static final double STEP_S = STEP_NS / 1e9;
    /** How long a "Step" button press advances the simulation. */
    private static final long STEP_BUTTON_NS = 20_000_000L;
    /** Warn if the OpMode doesn't hand back control within this much real time. */
    private static final long STUCK_WARN_MS = 1500;
    /** Kill the OpMode if it stays stuck this long (real time). */
    private static final long STUCK_KILL_MS = 5000;
    /** Browser update rate (real time). */
    private static final long PUBLISH_INTERVAL_NS = 16_000_000L;
    /** Never simulate more than this much per real-time frame (prevents a "death spiral" on slow PCs). */
    private static final long MAX_SIM_NS_PER_FRAME = 250_000_000L;

    private final List<String> variantNames;
    private SimConfig cfg;
    private final Lockstep lockstep = new Lockstep();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> pendingLog = new ConcurrentLinkedQueue<>();
    private Consumer<String> broadcaster = s -> { };

    // ---- the world (rebuilt on Reset) ----
    private Field field;
    private final List<SimRobot> robots = new ArrayList<>();
    private SimRobot ours;
    private GameWorld world;
    private BallMechanisms mech;
    private final java.util.Map<SimRobot, List<Ball>> preloads = new java.util.HashMap<>();
    private final java.util.Map<SimRobot, ElementCarrier> carriers = new java.util.LinkedHashMap<>();
    private ScoreKeeper score;
    private RuleChecker rules;

    // ---- full match flow ----
    private OpModeRegistry.Entry matchAuto;
    private OpModeRegistry.Entry matchTeleop;
    private double matchStartAt = -1;
    private double teleopInitAt = -1;
    private double postMatchAt = -1;
    private boolean endgameReleased;
    private String report;
    private final List<String> startProblems = new ArrayList<>();
    private int ourCellEntries;
    private int ourFlowerEntries;
    private long stepCount;
    private double runWallStart;
    private RobotHardwareSim hardware;
    private final SimTelemetry telemetry = new SimTelemetry();
    private OpModeRunner runner;
    private MatchTimer timer;
    private Battery battery;
    private long seed = 1;
    private SimRandom random = new SimRandom(1);
    private final OpModeRegistry registry = new OpModeRegistry();
    private final List<String> warnings = new ArrayList<>();

    // ---- settings chosen in the browser ----
    private Alliance ourAlliance = Alliance.RED;
    private String startPoseName;

    // ---- run control ----
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile double speed = 1.0;
    private long stepBudgetNs;
    private boolean stuckWarned;
    private volatile Gamepad incoming1 = new Gamepad();
    private volatile Gamepad incoming2 = new Gamepad();
    private Gamepad applied1;
    private Gamepad applied2;

    public Simulation(SimConfig cfg, List<String> variantNames) {
        this.cfg = cfg;
        this.variantNames = variantNames;
        this.startPoseName = cfg.robot.str("defaultStartPose");
        SimHooks.install(lockstep);
        buildWorld();
    }

    public void setBroadcaster(Consumer<String> broadcaster) {
        this.broadcaster = broadcaster;
    }

    // =====================================================================
    // Building the world
    // =====================================================================

    private void buildWorld() {
        warnings.clear();
        robots.clear();
        Cfg chassis = cfg.robot.obj("chassis");
        double length = inToM(chassis.num("length"));
        double width = inToM(chassis.num("width"));
        double height = inToM(chassis.num("height"));
        field = new Field(cfg.game, height);
        timer = new MatchTimer(cfg.game);
        checkStartingSize(chassis);

        // Start poses: ours uses the chosen pose; our partner uses the other one.
        Cfg poses = cfg.robot.obj("startPoses");
        List<String> names = new ArrayList<>();
        poses.keys().forEach(names::add);
        if (!names.contains(startPoseName)) {
            startPoseName = names.get(0);
        }
        String partnerPose = names.stream().filter(n -> !n.equals(startPoseName)).findFirst().orElse(startPoseName);

        ours = new SimRobot("US", ourAlliance, true, length, width, height, allianceStart(poses.obj(startPoseName), ourAlliance));
        SimRobot partner = new SimRobot("PARTNER", ourAlliance, false, length, width, height,
                allianceStart(poses.obj(partnerPose), ourAlliance));
        Alliance them = ourAlliance.opponent();
        SimRobot opp1 = new SimRobot("OPP 1", them, false, length, width, height, allianceStart(poses.obj(startPoseName), them));
        SimRobot opp2 = new SimRobot("OPP 2", them, false, length, width, height, allianceStart(poses.obj(partnerPose), them));
        robots.add(ours);
        robots.add(partner);
        robots.add(opp1);
        robots.add(opp2);

        random = new SimRandom(seed);
        // Game elements staged exactly as §10.3.1 says (with small random placement variation).
        world = new GameWorld(cfg.game, field, random.stream("staging"));
        preloads.clear();
        world.stage(robots, preloads);

        Cfg bat = cfg.robot.obj("battery");
        battery = new Battery(bat.num("restVoltage"), bat.num("internalResistanceOhm"), bat.num("electronicsCurrentA"));
        hardware = new RobotHardwareSim(cfg, ours, this::batteryVoltage, random, world, () -> robots);
        warnings.addAll(hardware.warnings);
        ours.drivetrain = buildDrivetrain();
        mech = buildMechanisms();
        mech.preload(preloads.get(ours));
        carriers.clear();
        for (SimRobot r : robots) {
            if (r == ours) {
                carriers.put(r, mech);
            } else {
                // Until the robot AI (stage 6) takes over, other robots just hold their pre-loads.
                List<Ball> held = new ArrayList<>(preloads.get(r));
                carriers.put(r, new ElementCarrier() {
                    public int heldCount() {
                        held.removeIf(b -> b.state != Ball.State.HELD);
                        return held.size();
                    }

                    public List<Ball> heldBalls() {
                        heldCount();
                        return held;
                    }
                });
            }
        }
        score = new ScoreKeeper(cfg.game, field);
        rules = new RuleChecker(cfg.game, field);
        matchAuto = null;
        matchTeleop = null;
        matchStartAt = -1;
        teleopInitAt = -1;
        postMatchAt = -1;
        endgameReleased = false;
        report = null;
        startProblems.clear();
        ourCellEntries = 0;
        ourFlowerEntries = 0;
        if (hardware.possessionSensor != null) {
            hardware.possessionSensor.ballPresent = () -> mech.ballAtGate(nowSeconds());
        }
        runner = new OpModeRunner(lockstep, hardware, telemetry, this::log);
        lockstep.resetClock();
        applied1 = null;
        applied2 = null;
        for (String w : warnings) {
            log("WARNING: " + w);
        }
    }

    private MecanumDrivetrain buildDrivetrain() {
        String[] roles = {"drive.frontLeft", "drive.backLeft", "drive.frontRight", "drive.backRight"};
        MotorState[] m = new MotorState[4];
        double[] sign = new double[4];
        for (int i = 0; i < 4; i++) {
            m[i] = hardware.motorsByRole.get(roles[i]);
            if (m[i] == null) {
                throw new IllegalArgumentException("hubs.jsonc has no motor with role \"" + roles[i] + "\"");
            }
            sign[i] = hardware.mountSignByRole.get(roles[i]);
        }
        Cfg c = cfg.robot.obj("chassis");
        Cfg d = cfg.robot.obj("drivetrain");
        MecanumDrivetrain.Params p = new MecanumDrivetrain.Params();
        p.massKg = Units.lbToKg(c.num("massLb"));
        p.lengthM = inToM(c.num("length"));
        p.widthM = inToM(c.num("width"));
        p.wheelRadiusM = Units.mmToM(c.num("wheelDiameterMm")) / 2.0;
        p.trackWidthM = inToM(c.num("trackWidth"));
        p.wheelBaseM = inToM(c.num("wheelBase"));
        p.wheelInertia = d.num("wheelInertia");
        p.frictionCoeff = d.num("frictionCoeff");
        p.rollerResistance = d.num("rollerResistance");
        p.rollingResistance = d.num("rollingResistance");
        p.slipSpeed = d.num("slipSpeed");
        p.gripVariation = d.num("gripVariation");
        return new MecanumDrivetrain(m[0], m[1], m[2], m[3], sign, p, random.stream("drivetrain"));
    }

    private BallMechanisms buildMechanisms() {
        for (String role : new String[] {"intake.left", "intake.right", "shooter.left", "shooter.right"}) {
            if (!hardware.motorsByRole.containsKey(role)) {
                throw new IllegalArgumentException("hubs.jsonc has no motor with role \"" + role + "\"");
            }
        }
        return new BallMechanisms(ours, world, cfg.robot,
                hardware.motorsByRole.get("intake.left"), hardware.mountSignByRole.get("intake.left"),
                hardware.motorsByRole.get("intake.right"), hardware.mountSignByRole.get("intake.right"),
                hardware.motorsByRole.get("shooter.left"), hardware.mountSignByRole.get("shooter.left"),
                hardware.motorsByRole.get("shooter.right"), hardware.mountSignByRole.get("shooter.right"),
                hardware.servosByRole.get("shooter.servo"), random.stream("shooter"));
    }

    private static Pose2d allianceStart(Cfg p, Alliance a) {
        Pose2d red = new Pose2d(inToM(p.num("x")), inToM(p.num("y")), Math.toRadians(p.num("headingDeg")));
        return a == Alliance.RED ? red : red.rotate180();
    }

    /** R102: the robot must fit in the 18 in starting cube. */
    private void checkStartingSize(Cfg chassis) {
        double cube = cfg.game.num("robotRules.startingCube");
        for (String dim : new String[] {"length", "width", "height"}) {
            if (chassis.num(dim) > cube) {
                warnings.add(String.format("RULE R102: robot %s is %.1f in, but it must start inside a %.0f in cube.",
                        dim, chassis.num(dim), cube));
            }
        }
    }

    /** Battery voltage at the hubs (sags with current draw). */
    private double batteryVoltage() {
        return battery.voltage();
    }

    /** Sets the match seed (takes effect on the next reset). */
    public void setSeed(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    // =====================================================================
    // Commands from the browser (queued, applied on the physics thread)
    // =====================================================================

    /** Handles one JSON message from the browser. Safe to call from any thread. */
    @SuppressWarnings("unchecked")
    public void onClientMessage(Map<String, Object> msg) {
        String type = String.valueOf(msg.get("type"));
        if (type.equals("gamepad")) {
            Gamepad g = gamepadFromJson(msg);
            if (((Double) msg.getOrDefault("index", 1.0)).intValue() == 2) {
                incoming2 = g;
            } else {
                incoming1 = g;
            }
            return;
        }
        if (!type.equals("cmd")) {
            return;
        }
        String cmd = String.valueOf(msg.get("cmd"));
        switch (cmd) {
            case "pause": paused = true; break;
            case "resume": paused = false; break;
            case "speed":
                speed = Math.max(0.25, Math.min(4.0, ((Double) msg.get("value"))));
                break;
            default:
                commands.add(() -> applyCommand(cmd, msg));
        }
    }

    private void applyCommand(String cmd, Map<String, Object> msg) {
        switch (cmd) {
            case "init": {
                OpModeRegistry.Entry e = registry.find(String.valueOf(msg.get("opmode")));
                if (e == null) {
                    log("No OpMode named " + msg.get("opmode"));
                    return;
                }
                if (timer.mode() == MatchTimer.Mode.MATCH && timer.isRunning()) {
                    log("A match is running - press Reset first");
                    return;
                }
                timer.reset();
                runner.init(e);
                break;
            }
            case "start":
                if (runner.state() == OpModeRunner.State.INIT && timer.mode() == MatchTimer.Mode.PRACTICE) {
                    runner.start();
                    timer.startPractice(runner.entry().autonomous ? MatchTimer.Phase.AUTO : MatchTimer.Phase.TELEOP,
                            nowSeconds());
                }
                break;
            case "startMatch":
                startMatch(str(msg.get("auto")), str(msg.get("teleop")));
                break;
            case "seed":
                seed = ((Double) msg.get("value")).longValue();
                reset(false);
                break;
            case "stop":
                runner.stop();
                break;
            case "step":
                paused = true;
                stepBudgetNs += STEP_BUTTON_NS;
                break;
            case "reset":
                reset(false);
                break;
            case "reload":
                reset(true);
                break;
            case "alliance":
                ourAlliance = Alliance.parse(String.valueOf(msg.get("value")));
                reset(false);
                break;
            case "startPose":
                startPoseName = String.valueOf(msg.get("value"));
                reset(false);
                break;
            default:
                log("Unknown command " + cmd);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * Starts a full match: fresh field, INIT the auto OpMode (the drive team
     * does this before the match), then AUTO -> TRANSITION -> TELEOP.
     * Either OpMode name may be empty.
     */
    public void startMatch(String autoName, String teleopName) {
        reset(false);
        matchAuto = autoName.isEmpty() ? null : registry.find(autoName);
        matchTeleop = teleopName.isEmpty() ? null : registry.find(teleopName);
        if (!autoName.isEmpty() && matchAuto == null) {
            log("No OpMode named " + autoName);
        }
        if (matchAuto != null) {
            runner.init(matchAuto);
        }
        // Give INIT a moment (drive teams INIT well before the match starts).
        matchStartAt = nowSeconds() + (matchAuto != null ? 1.0 : 0.05);
        log("Match queued: AUTO = " + (matchAuto == null ? "none" : matchAuto.name)
                + ", TELEOP = " + (matchTeleop == null ? "none" : matchTeleop.name) + ", seed " + seed);
    }

    /** Puts every robot back at its start. With reloadConfig, re-reads the config files first. */
    private void reset(boolean reloadConfig) {
        runner.abort();
        if (reloadConfig) {
            try {
                cfg = SimConfig.load(cfg.configDir, variantNames);
                log("Config reloaded from " + cfg.configDir);
            } catch (IOException | RuntimeException e) {
                log("Config reload FAILED (keeping the old config): " + e.getMessage());
            }
        }
        telemetry.resetForNewOpMode();
        buildWorld();
        broadcaster.accept(fieldMessage());
        log("Reset: " + ourAlliance + " alliance, start pose " + startPoseName);
    }

    private static Gamepad gamepadFromJson(Map<String, Object> m) {
        Gamepad g = new Gamepad();
        g.left_stick_x = f(m, "lx");
        g.left_stick_y = f(m, "ly");
        g.right_stick_x = f(m, "rx");
        g.right_stick_y = f(m, "ry");
        g.left_trigger = f(m, "lt");
        g.right_trigger = f(m, "rt");
        g.a = b(m, "a");
        g.b = b(m, "b");
        g.x = b(m, "x");
        g.y = b(m, "y");
        g.left_bumper = b(m, "lb");
        g.right_bumper = b(m, "rb");
        g.back = b(m, "back");
        g.start = b(m, "start");
        g.guide = b(m, "guide");
        g.left_stick_button = b(m, "ls");
        g.right_stick_button = b(m, "rs");
        g.dpad_up = b(m, "up");
        g.dpad_down = b(m, "down");
        g.dpad_left = b(m, "left");
        g.dpad_right = b(m, "right");
        return g;
    }

    private static float f(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Double ? (float) Math.max(-1, Math.min(1, (Double) v)) : 0f;
    }

    private static boolean b(Map<String, Object> m, String k) {
        return Boolean.TRUE.equals(m.get(k));
    }

    // =====================================================================
    // Physics
    // =====================================================================

    /** Advances the whole world by one fixed 1 ms step. */
    void stepOnce() {
        Runnable c;
        while ((c = commands.poll()) != null) {
            c.run();
        }
        // New controller data reaches the OpMode between its turns, like the SDK.
        if (incoming1 != applied1) {
            applied1 = incoming1;
            runner.gamepad1.copy(applied1);
        }
        if (incoming2 != applied2) {
            applied2 = incoming2;
            runner.gamepad2.copy(applied2);
        }

        // 1) Let the OpMode run until it is ahead of the physics clock.
        long stuckSinceWall = 0;
        while (!lockstep.letOpModeCatchUp(STUCK_WARN_MS)) {
            if (stuckSinceWall == 0) {
                stuckSinceWall = System.nanoTime();
            } else if (System.nanoTime() - stuckSinceWall > STUCK_KILL_MS * 1_000_000L) {
                runner.killStuck("OpMode made no SDK calls for " + STUCK_KILL_MS / 1000
                        + " s of real time (endless loop without hardware calls, or Thread.sleep()?)");
                break;
            }
            if (!stuckWarned) {
                stuckWarned = true;
                log("WARNING: the OpMode hasn't called any SDK method for over " + STUCK_WARN_MS / 1000.0
                        + " s of real time. Is it in an endless loop, or using Thread.sleep() instead of sleep()?");
                publish();
            }
            Runnable r;
            while ((r = commands.poll()) != null) {
                r.run(); // still allow Stop/Reset while stuck
            }
            if (!running) {
                return;
            }
        }
        stuckWarned = false;

        // 2) Hub firmware (motor PID, velocity measurement), then physics.
        hardware.step(STEP_S);
        ours.drivetrain.step(ours, STEP_S, battery.voltage());
        Collisions.resolve(ours, field, robots);
        mech.step(nowSeconds(), STEP_S, battery.voltage());
        world.step(nowSeconds(), STEP_S, robots);
        if (hardware.limelight != null) {
            hardware.limelight.step(nowSeconds());
        }
        battery.update(hardware.allMotors());

        // 3) Advance the clock and the match.
        lockstep.advancePhysics(STEP_NS);
        runner.afterStep();
        matchFlow(nowSeconds());
        stepCount++;
    }

    // =====================================================================
    // Match flow, scoring and rules
    // =====================================================================

    private void matchFlow(double now) {
        if (matchStartAt >= 0 && now >= matchStartAt) {
            matchStartAt = -1;
            checkStartingPositions();
            timer.startMatch(now);
            if (runner.state() == OpModeRunner.State.INIT) {
                runner.start();
            }
            runWallStart = System.nanoTime();
            log("MATCH START - AUTO");
        }
        MatchTimer.Phase started = timer.update(now);
        if (started != null) {
            onPhaseStart(started, now);
        }
        if (teleopInitAt >= 0 && now >= teleopInitAt) {
            teleopInitAt = -1;
            if (matchTeleop != null) {
                runner.init(matchTeleop); // drive team presses INIT during the transition
            }
        }
        if (timer.mode() == MatchTimer.Mode.MATCH && timer.phase() == MatchTimer.Phase.TELEOP && !endgameReleased
                && timer.matchSecondsLeft(now) <= 60.0) {
            endgameReleased = true;
            world.releaseEndgameNectar(now);
            log("60 s left: all remaining NECTAR may be entered, NECTAR may go into FLOWERS (G410, G426)");
        }

        List<GameWorld.Event> events = world.drainEvents();
        for (GameWorld.Event e : events) {
            switch (e.type) {
                case HIVE_TIP:
                    boolean autoTip = timer.phase() != MatchTimer.Phase.TELEOP && timer.phase() != MatchTimer.Phase.POST_MATCH;
                    score.onTip(e.alliance, autoTip);
                    log(e.alliance + " HIVE TIPPED (#" + e.index + ", " + (autoTip ? "AUTO" : "TELEOP") + ") - +20");
                    break;
                case CELL_ENTRY:
                    if (ours.id.equals(e.ball.launchedBy) && e.alliance == ours.alliance) {
                        ourCellEntries++;
                    }
                    break;
                case FLOWER_ENTRY:
                    if (ours.id.equals(e.ball.launchedBy)) {
                        ourFlowerEntries++;
                    }
                    log(e.ball.kind.label() + " into FLOWER " + e.index);
                    break;
                case NECTAR_ENTERED:
                    log("Human player entered " + e.alliance + " NECTAR");
                    break;
                case LEFT_FIELD:
                    log(e.ball.kind.label() + " left the field");
                    break;
                default:
                    break;
            }
        }
        java.util.Map<SimRobot, Boolean> powered = new java.util.HashMap<>();
        for (SimRobot r : robots) {
            powered.put(r, r == ours ? ourRobotPowered() : Math.hypot(r.intentVx, r.intentVy) > 0.05);
        }
        rules.step(now, timer.phase().name(), timer.phaseTime(now), robots, carriers, powered, events,
                timer.mode() == MatchTimer.Mode.MATCH ? timer.matchSecondsLeft(now)
                        : (timer.phase() == MatchTimer.Phase.TELEOP ? timer.secondsLeft(now) : 999));
        for (RuleChecker.Foul f : rules.drainNew()) {
            score.onFoul(f.alliance, rules.points(f));
            log("RULE " + f);
        }
        if (stepCount % 100 == 0) {
            score.updateEndItems(world, robots, false);
        }
        if (postMatchAt >= 0 && now >= postMatchAt) {
            postMatchAt = -1;
            score.updateEndItems(world, robots, true);
            report = buildReport(now);
            log(String.format("FINAL SCORE  RED %d - BLUE %d   (our alliance %s: %d RP)",
                    score.total(Alliance.RED), score.total(Alliance.BLUE), ourAlliance, score.rankingPoints(ourAlliance)));
        }
    }

    private void onPhaseStart(MatchTimer.Phase p, double now) {
        switch (p) {
            case TRANSITION:
                runner.stop(); // the Driver Station's 30 s AUTO timer stops the OpMode
                score.assessAuto(robots);
                log(String.format("AUTO over. LEAVE: red %d, blue %d; AUTO PARK: red %d, blue %d",
                        score.get(Alliance.RED).leave, score.get(Alliance.BLUE).leave,
                        score.get(Alliance.RED).autoPark, score.get(Alliance.BLUE).autoPark));
                teleopInitAt = now + 1.0;
                break;
            case TELEOP:
                if (runner.state() == OpModeRunner.State.INIT) {
                    runner.start();
                }
                log("TELEOP");
                break;
            case POST_MATCH:
                if (timer.mode() == MatchTimer.Mode.PRACTICE && runner.entry() != null && runner.entry().autonomous) {
                    score.assessAuto(robots);
                }
                runner.stop();
                postMatchAt = now + 3.0; // final scoring once everything comes to rest
                log("Time's up - waiting for everything to come to rest");
                break;
            default:
                break;
        }
    }

    /** Is any of our actuators powered, or is a servo still moving? (G403 / G404) */
    private boolean ourRobotPowered() {
        for (MotorState m : hardware.allMotors()) {
            if (Math.abs(m.appliedPower) > 0.01) {
                return true;
            }
        }
        org.biobuzz.sim.hardware.SimServo sv = hardware.servosByRole.get("shooter.servo");
        return sv != null && !Double.isNaN(sv.commandedRaw()) && Math.abs(sv.commandedRaw() - mech.servoActual()) > 0.005;
    }

    /** G304: every robot must start legally. A real match wouldn't start otherwise. */
    private void checkStartingPositions() {
        startProblems.clear();
        for (SimRobot r : robots) {
            for (String p : rules.checkStart(r, carriers.get(r).heldCount(), r.length, r.width, r.height)) {
                startProblems.add(r.id + ": " + p);
                log("RULE " + r.id + " start: " + p);
            }
        }
        if (Math.hypot(ours.vx, ours.vy) > 0.01 || ourRobotPowered()) {
            startProblems.add(ours.id + ": G304.H: not motionless after INIT");
            log("RULE US start: G304.H: not motionless after OpMode INIT finished");
        }
    }

    /** Everything about the match, as JSON (used by headless runs and saved runs). */
    public String buildReport(double now) {
        JsonOut j = new JsonOut().beginObject();
        j.field("seed", seed);
        j.name("variants").any(new ArrayList<Object>(variantNames));
        j.field("alliance", ourAlliance.name()).field("startPose", startPoseName);
        j.field("autoOpMode", matchAuto == null ? "" : matchAuto.name);
        j.field("teleopOpMode", matchTeleop == null ? "" : matchTeleop.name);
        j.name("score");
        score.writeJson(j);
        j.field("ourScore", score.total(ourAlliance)).field("ourAutoScore", score.get(ourAlliance).autoTotal(cfg.game.obj("points")));
        j.field("theirScore", score.total(ourAlliance.opponent()));
        j.name("ourRobot").beginObject()
                .field("shots", mech.shots).field("pickups", mech.pickups).field("jams", mech.jams)
                .field("cellEntries", ourCellEntries).field("flowerEntries", ourFlowerEntries)
                .field("minBatteryV", battery.minVoltage())
                .field("opModeError", runner.error())
                .endObject();
        j.name("fouls").beginArray();
        for (RuleChecker.Foul f : rules.all()) {
            j.beginObject().field("t", f.time).field("robot", f.robot).field("alliance", f.alliance.name())
                    .field("rule", f.rule).field("penalty", f.penalty.name()).field("points", rules.points(f))
                    .field("what", f.description).endObject();
        }
        j.endArray();
        j.name("startProblems").any(new ArrayList<Object>(startProblems));
        j.field("simSeconds", now);
        j.field("wallSeconds", runWallStart > 0 ? (System.nanoTime() - runWallStart) / 1e9 : 0);
        return j.endObject().toString();
    }

    public double nowSeconds() {
        return lockstep.physicsTimeNs() / 1e9;
    }

    /**
     * Runs the simulation paced to the real clock (times the speed setting)
     * until {@link #shutdown()} is called. This is the visual mode.
     */
    public void runRealTime() {
        long wallAnchor = System.nanoTime();
        long simAnchor = lockstep.physicsTimeNs();
        double anchoredSpeed = speed;
        long lastPublish = 0;
        boolean wasPaused = paused;
        while (running) {
            long wallNow = System.nanoTime();
            if (paused != wasPaused || anchoredSpeed != speed) {
                // Speed changed or pause toggled: re-anchor so time doesn't jump.
                wallAnchor = wallNow;
                simAnchor = lockstep.physicsTimeNs();
                anchoredSpeed = speed;
                wasPaused = paused;
            }
            if (paused) {
                long target = lockstep.physicsTimeNs() + stepBudgetNs;
                stepBudgetNs = 0;
                while (lockstep.physicsTimeNs() < target && running) {
                    stepOnce();
                }
                Runnable c;
                while ((c = commands.poll()) != null) {
                    c.run();
                }
                simAnchor = lockstep.physicsTimeNs();
                wallAnchor = wallNow;
            } else {
                long target = simAnchor + (long) ((wallNow - wallAnchor) * anchoredSpeed);
                long limit = lockstep.physicsTimeNs() + MAX_SIM_NS_PER_FRAME;
                while (lockstep.physicsTimeNs() < Math.min(target, limit) && running && !paused) {
                    stepOnce();
                }
                if (target > limit) {
                    // Computer can't keep up: drop the backlog instead of racing to catch up.
                    simAnchor = lockstep.physicsTimeNs();
                    wallAnchor = System.nanoTime();
                }
            }
            if (wallNow - lastPublish >= PUBLISH_INTERVAL_NS) {
                publish();
                lastPublish = wallNow;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void shutdown() {
        running = false;
        runner.abort();
        SimHooks.uninstall();
    }

    // =====================================================================
    // Messages to the browser
    // =====================================================================

    private void log(String text) {
        pendingLog.add(String.format("%6.2fs  %s", nowSeconds(), text));
        System.out.println("[sim] " + text);
    }

    private void publish() {
        broadcaster.accept(stateMessage());
    }

    /** Sent once when a browser connects (and after Reset): the static field layout and settings. */
    public String fieldMessage() {
        JsonOut j = new JsonOut().beginObject().field("type", "field");
        j.name("game").any(cfg.game.raw());
        j.name("zones").beginObject();
        for (Alliance a : Alliance.values()) {
            j.name(a.name().toLowerCase()).beginObject();
            rect(j.name("loadingZone"), field.loadingZone.get(a));
            rect(j.name("garden"), field.garden.get(a));
            rect(j.name("allianceArea"), field.allianceArea.get(a));
            j.endObject();
        }
        j.endObject();
        j.name("flowers").beginArray();
        for (Circle c : field.flowers) {
            j.beginObject().field("x", mToIn(c.x)).field("y", mToIn(c.y)).field("r", mToIn(c.r)).endObject();
        }
        j.endArray();
        j.name("robot").any(cfg.robot.raw());
        j.field("alliance", ourAlliance.name());
        j.field("startPose", startPoseName);
        j.name("startPoses").beginArray();
        cfg.robot.obj("startPoses").keys().forEach(j::value);
        j.endArray();
        j.name("variants").any(new ArrayList<Object>(variantNames));
        j.name("opmodes").beginArray();
        for (OpModeRegistry.Entry e : registry.entries()) {
            j.beginObject().field("name", e.name).field("group", e.group).field("autonomous", e.autonomous).endObject();
        }
        j.endArray();
        j.name("warnings").any(new ArrayList<Object>(warnings));
        return j.endObject().toString();
    }

    private static void rect(JsonOut j, Rect r) {
        j.beginObject().field("xMin", mToIn(r.xMin)).field("xMax", mToIn(r.xMax))
                .field("yMin", mToIn(r.yMin)).field("yMax", mToIn(r.yMax)).endObject();
    }

    /** Sent about 60 times a second: everything that moves. Positions in inches, angles in degrees. */
    public String stateMessage() {
        double now = nowSeconds();
        JsonOut j = new JsonOut().beginObject().field("type", "state");
        j.field("t", now).field("paused", paused).field("speed", speed);
        j.name("match").beginObject()
                .field("phase", timer.phase().name())
                .field("mode", timer.mode().name())
                .field("timeLeft", timer.secondsLeft(now))
                .field("matchTimeLeft", timer.matchSecondsLeft(now))
                .endObject();
        j.name("score");
        score.writeJson(j);
        j.field("seed", seed);
        j.name("opmode").beginObject()
                .field("name", runner.entry() == null ? "" : runner.entry().name)
                .field("state", runner.state().name())
                .field("error", runner.error())
                .endObject();
        j.name("robots").beginArray();
        for (SimRobot r : robots) {
            j.beginObject().field("id", r.id).field("alliance", r.alliance.name()).field("ours", r.ours)
                    .field("x", mToIn(r.x)).field("y", mToIn(r.y)).field("h", Math.toDegrees(Units.wrapRadians(r.heading)))
                    .field("l", mToIn(r.length)).field("w", mToIn(r.width)).field("ht", mToIn(r.height))
                    .endObject();
        }
        j.endArray();

        j.name("ours").beginObject();
        j.field("vx", mToIn(ours.vx)).field("vy", mToIn(ours.vy)).field("omega", Math.toDegrees(ours.omega));
        j.field("voltage", batteryVoltage());
        j.field("batteryAmps", battery.totalCurrentA());
        j.field("minVoltage", battery.minVoltage());
        j.name("motors").beginArray();
        for (Map.Entry<String, SimDcMotor> e : hardware.motorPortsByRole.entrySet()) {
            MotorState s = e.getValue().state;
            j.beginObject()
                    .field("name", e.getValue().configName())
                    .field("role", e.getKey())
                    .field("power", s.appliedPower)
                    .field("rpm", Units.radPerSecToRpm(s.velocityRadPerSec))
                    .field("amps", Math.abs(s.currentA))
                    .field("ticks", Math.floor(s.angleRad * s.spec.ticksPerRadian()))
                    .endObject();
        }
        j.endArray();
        j.name("mech").beginObject()
                .field("held", mech.heldCount())
                .field("intake", mech.intakeState().name())
                .field("flywheelRpm", mech.flywheelRpm())
                .field("servoActual", mech.servoActual())
                .field("launchAngle", mech.currentLaunchAngleDeg())
                .field("shots", mech.shots)
                .field("pickups", mech.pickups)
                .field("jams", mech.jams)
                .field("ballAtGate", mech.ballAtGate(now))
                .endObject();
        j.name("servos").beginArray();
        hardware.servosByRole.forEach((role, s) ->
                j.beginObject().field("name", s.configName()).field("role", role).field("position", s.getPosition()).endObject());
        j.endArray();
        j.endObject();

        // Balls: [id, kind, state, x, y, z] in inches (compact).
        j.name("balls").beginArray();
        for (Ball b : world.balls) {
            if (b.state == Ball.State.OUT_OF_FIELD) {
                continue;
            }
            j.beginArray().value(b.id).value(b.kind.ordinal()).value(b.state.ordinal())
                    .value(Math.round(mToIn(b.x) * 10) / 10.0).value(Math.round(mToIn(b.y) * 10) / 10.0)
                    .value(Math.round(mToIn(b.z) * 10) / 10.0).endArray();
        }
        j.endArray();
        j.name("hives").beginArray();
        for (Hive h : world.hives.values()) {
            j.beginObject().field("alliance", h.alliance.name()).field("angle", Math.toDegrees(h.angle()))
                    .field("upEnd", h.upEnd()).field("tips", h.tips).field("massG", h.upCellMass() * 1000)
                    .field("tipMassG", h.tipMassKg * 1000).endObject();
        }
        j.endArray();
        j.name("flowers").beginArray();
        for (Flower f : world.flowers) {
            j.beginObject().field("owner", f.owner() == null ? "" : f.owner().name())
                    .field("inVolume", f.scoringBalls().size()).endObject();
        }
        j.endArray();
        j.name("fouls").beginArray();
        for (RuleChecker.Foul f : rules.all()) {
            j.beginObject().field("t", Math.round(f.time * 10) / 10.0).field("robot", f.robot)
                    .field("rule", f.rule).field("penalty", f.penalty.name()).field("what", f.description).endObject();
        }
        j.endArray();
        j.field("startProblems", String.join("; ", startProblems));
        j.name("telemetry").any(new ArrayList<Object>(telemetry.publishedLines()));
        List<String> logLines = new ArrayList<>();
        String line;
        while ((line = pendingLog.poll()) != null) {
            logLines.add(line);
        }
        j.name("log").any(new ArrayList<Object>(logLines));
        return j.endObject().toString();
    }

    // ---- accessors for tests and headless mode ----

    public SimRobot ourRobot() {
        return ours;
    }

    public GameWorld world() {
        return world;
    }

    public ScoreKeeper score() {
        return score;
    }

    public RuleChecker rules() {
        return rules;
    }

    public MatchTimer timer() {
        return timer;
    }

    /** The final match report (JSON), or null until the match is over. */
    public String report() {
        return report;
    }

    public List<String> startProblems() {
        return startProblems;
    }

    public BallMechanisms mechanisms() {
        return mech;
    }

    public RobotHardwareSim hardware() {
        return hardware;
    }

    public List<SimRobot> robots() {
        return Collections.unmodifiableList(robots);
    }

    public OpModeRunner runner() {
        return runner;
    }

    public OpModeRegistry registry() {
        return registry;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** Queues a command exactly as if the browser had sent it. */
    public void command(String cmd, Object value) {
        Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("type", "cmd");
        msg.put("cmd", cmd);
        if (value != null) {
            msg.put(cmd.equals("init") ? "opmode" : "value", value);
        }
        onClientMessage(msg);
    }

    /** INITs any OpMode class, even one not in TeamCode (used by tests). */
    public void initOpMode(Class<? extends com.qualcomm.robotcore.eventloop.opmode.OpMode> type, boolean autonomous) {
        commands.add(() -> {
            timer.reset();
            runner.init(OpModeRegistry.Entry.of(type, autonomous));
        });
    }

    /** The telemetry lines currently shown. */
    public List<String> telemetryLines() {
        return telemetry.publishedLines();
    }

    /** Sets gamepad 1 (used by tests). */
    public void setGamepad1(Gamepad g) {
        incoming1 = g;
    }

    /** Runs {@code seconds} of simulated time as fast as possible (tests / headless). */
    public void runFor(double seconds) {
        long end = lockstep.physicsTimeNs() + (long) (seconds * 1e9);
        while (lockstep.physicsTimeNs() < end && running) {
            stepOnce();
        }
    }
}
