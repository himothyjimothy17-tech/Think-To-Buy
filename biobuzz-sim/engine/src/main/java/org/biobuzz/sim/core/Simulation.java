package org.biobuzz.sim.core;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.field.Field;
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
        Cfg bat = cfg.robot.obj("battery");
        battery = new Battery(bat.num("restVoltage"), bat.num("internalResistanceOhm"), bat.num("electronicsCurrentA"));
        hardware = new RobotHardwareSim(cfg, ours, this::batteryVoltage, random);
        warnings.addAll(hardware.warnings);
        ours.drivetrain = buildDrivetrain();
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
                timer.reset();
                runner.init(e);
                break;
            }
            case "start":
                if (runner.state() == OpModeRunner.State.INIT) {
                    runner.start();
                    timer.startPeriod(runner.entry().autonomous ? MatchTimer.Phase.AUTO : MatchTimer.Phase.TELEOP,
                            nowSeconds());
                }
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
        // Motors that aren't part of a simulated mechanism yet spin freely.
        for (MotorState m : hardware.allMotors()) {
            if (!m.role.startsWith("drive.")) {
                spinFree(m, STEP_S);
            }
        }
        battery.update(hardware.allMotors());

        // 3) Advance the clock and the match.
        lockstep.advancePhysics(STEP_NS);
        runner.afterStep();
        if (timer.update(nowSeconds())) {
            log("Time's up (" + (runner.entry() != null && runner.entry().autonomous ? "AUTO" : "TELEOP") + " period over)");
            runner.stop();
        }
    }

    /**
     * A motor with nothing attached except its own rotor/gearbox inertia.
     * TODO(stage 3): replaced by the intake and flywheel mechanisms.
     */
    private void spinFree(MotorState m, double dt) {
        // ESTIMATE: motor rotor inertia (about 1.5e-6 kg*m^2) seen through the gearbox
        // grows with the gear ratio squared, plus a little for the output shaft.
        final double inertia = 1.5e-6 * m.spec.gearRatio * m.spec.gearRatio + 1e-5;
        double t = m.torque(battery.voltage());
        m.velocityRadPerSec += t / inertia * dt;
        m.angleRad += m.velocityRadPerSec * dt;
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
                .field("timeLeft", timer.secondsLeft(now))
                .endObject();
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
        j.name("servos").beginArray();
        hardware.servosByRole.forEach((role, s) ->
                j.beginObject().field("name", s.configName()).field("role", role).field("position", s.getPosition()).endObject());
        j.endArray();
        j.endObject();

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
