package org.biobuzz.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.core.Tunables;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.geom.Pose2d;
import org.biobuzz.sim.util.Units;
import org.firstinspires.ftc.teamcode.FieldConstants;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.firstinspires.ftc.teamcode.ShotSolver;
import org.firstinspires.ftc.teamcode.Shooter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Experiment: hit rate of OUR shooter code (ShotSolver RPM) into the red
 * audience CELL from a grid of spots. Prints a map (hits out of 4 x seeds).
 * Variants/tunables via system properties: -Dvariant=hood -Dangle=45
 */
@Tag("experiment")
class ShotMapExperiment {

    static volatile double rpm;
    static volatile double hood = Double.NaN;

    public static class Fire extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            Shooter s = new Shooter(r);
            waitForStart();
            if (!Double.isNaN(hood)) {
                s.setHood(hood);
            }
            s.setTargetRpm(rpm);
            ElapsedTime t = new ElapsedTime();
            while (opModeIsActive() && t.seconds() < 4.5) {
                if (t.seconds() > 1.0) {
                    if (Boolean.getBoolean("stream")) {
                        s.fire();
                    } else {
                        s.fireSingle();
                    }
                }
            }
            s.setTargetRpm(0);
        }
    }

    @Test
    void trace() throws Exception {
        if (System.getProperty("trace") == null) {
            return;
        }
        double x = Double.parseDouble(System.getProperty("tx", "-50"));
        double y = Double.parseDouble(System.getProperty("ty", "-58"));
        double[] opening = FieldConstants.AUDIENCE_CELL_OPENING;
        SimConfig cfg = SimConfig.load(Paths.get("config"), List.of()).withAiOverrides(Map.of("enabled", false));
        Simulation sim = new Simulation(cfg, List.of());
        sim.setup(null, null, 1L);
        sim.robots().get(1).setPose(new Pose2d(Units.inToM(-63), Units.inToM(20), 0));
        double hx = Units.inToM(x);
        double hy = Units.inToM(y);
        sim.ourRobot().setPose(new Pose2d(hx, hy, Math.atan2(Units.inToM(opening[1]) - hy, Units.inToM(opening[0]) - hx)));
        double d = Math.hypot(opening[0] - x, opening[1] - y);
        rpm = ShotSolver.rpm(d + 1, opening[2] - FieldConstants.SHOT_EXIT_HEIGHT, 45);
        sim.initOpMode(Fire.class, true);
        sim.runFor(0.2);
        sim.command("start", null);
        int shots = 0;
        List<Ball> watched = new ArrayList<>();
        for (int i = 0; i < 4200; i++) {
            double before = sim.mechanisms().flywheelRpm();
            sim.runFor(0.001);
            if (sim.mechanisms().shots > shots) {
                shots = sim.mechanisms().shots;
                System.out.printf("TRACE t %.3f shot %d at %.0f rpm (target %.0f), servo %.2f%n", sim.nowSeconds(), shots, before, rpm,
                        sim.mechanisms().servoActual());
                for (Ball b : sim.world().balls) {
                    if ("US".equals(b.launchedBy) && !watched.contains(b)) {
                        watched.add(b);
                    }
                }
            }
        }
        for (Ball b : watched) {
            System.out.printf("TRACE ball %d %s at (%.1f, %.1f, %.1f)%n", b.id, b.state, Units.mToIn(b.x), Units.mToIn(b.y),
                    Units.mToIn(b.z));
        }
        sim.shutdown();
    }

    /** Fine search around the corner: spot x/y and RPM scale, many shots each. -Pexp.fine=1 */
    @Test
    void fine() throws Exception {
        if (System.getProperty("fine") == null) {
            return;
        }
        String variant = System.getProperty("variant", "");
        double angle = Double.parseDouble(System.getProperty("angle", "45"));
        List<String> vs = variant.isEmpty() ? List.of() : List.of(variant);
        boolean far = System.getProperty("far") != null;
        double[] opening = far ? FieldConstants.FAR_CELL_OPENING : FieldConstants.AUDIENCE_CELL_OPENING;
        int seeds = Integer.getInteger("seeds", 5);
        double[] xs = parse(System.getProperty("xs", "-64,-62,-60,-58,-56"));
        double[] ys = parse(System.getProperty("ys", "-60,-58,-56,-54"));
        double[] scales = parse(System.getProperty("scales", "1.0,1.01,1.02,1.03,1.04"));
        for (double x : xs) {
            for (double y : ys) {
                StringBuilder row = new StringBuilder(String.format("FINE x %4.0f y %4.0f :", x, y));
                for (double sc : scales) {
                    int hits = 0;
                    int shots = 0;
                    for (int seed = 1; seed <= seeds; seed++) {
                        SimConfig cfg = SimConfig.load(Paths.get("config"), vs).withAiOverrides(Map.of("enabled", false));
                        Simulation sim = new Simulation(cfg, vs);
                        sim.setup(null, null, (long) seed + 100);
                        sim.robots().get(1).setPose(new Pose2d(Units.inToM(-63), Units.inToM(20), 0));
                        if (far) {
                            tipRedHive(sim);
                        }
                        double hx = Units.inToM(x);
                        double hy = Units.inToM(y);
                        sim.ourRobot().setPose(new Pose2d(hx, hy,
                                Math.atan2(Units.inToM(opening[1]) - hy, Units.inToM(opening[0]) - hx)));
                        double d = Math.hypot(opening[0] - x, opening[1] - y) - FieldConstants.SHOT_EXIT_FORWARD;
                        rpm = ShotSolver.rpm(d, opening[2] - FieldConstants.SHOT_EXIT_HEIGHT, angle) * sc;
                        hood = variant.equals("hood") ? (angle - 30) / 30.0 : Double.NaN;
                        sim.initOpMode(Fire.class, true);
                        sim.runFor(0.2);
                        sim.command("start", null);
                        // Count entries as they happen (a TIP would dump them).
                        for (int k = 0; k < 48; k++) {
                            sim.runFor(0.1);
                        }
                        hits += (int) sim.world().balls.stream().filter(b -> "US".equals(b.launchedBy)
                                && b.state == Ball.State.IN_CELL).count();
                        shots += sim.mechanisms().shots;
                        sim.shutdown();
                    }
                    row.append(String.format("  %.2f:%2d/%2d", sc, hits, shots));
                }
                System.out.println(row);
            }
        }
    }

    /** Fills the red up CELL until it TIPS, then lets the spill settle (so the far CELL is up). */
    static void tipRedHive(Simulation sim) {
        org.biobuzz.sim.game.Hive h = sim.world().hives.get(Alliance.RED);
        for (Ball b : sim.world().balls) {
            if (b.kind == org.biobuzz.sim.game.ElementKind.POLLEN && b.state == Ball.State.FREE && h.tips == 0
                    && h.upCellMass() < 0.26) {
                double[] o = h.upOpeningCenter();
                double[] n = h.upOpeningNormal();
                sim.world().launch(b, o[0] + n[0] * 0.08, o[1] + n[1] * 0.08, o[2] + n[2] * 0.08, -n[0] * 1.5, -n[1] * 1.5,
                        -n[2] * 1.5, "X", Alliance.RED, sim.nowSeconds());
                sim.runFor(0.1);
            }
        }
        sim.runFor(3.0);
    }

    private static double[] parse(String s) {
        String[] p = s.split(",");
        double[] out = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = Double.parseDouble(p[i].trim());
        }
        return out;
    }

    @Test
    void map() throws Exception {
        if (System.getProperty("fine") != null) {
            return;
        }
        if (System.getProperty("trace") != null) {
            return;
        }
        String variant = System.getProperty("variant", "");
        double angle = Double.parseDouble(System.getProperty("angle", "45"));
        double scale = Double.parseDouble(System.getProperty("scale", "1.0"));
        List<String> vs = variant.isEmpty() ? List.of() : List.of(variant);
        double[] opening = FieldConstants.AUDIENCE_CELL_OPENING;
        int seeds = 2;
        StringBuilder header = new StringBuilder("  y\\x ");
        List<Double> xs = new ArrayList<>();
        for (double x = -66; x <= -10; x += 4) {
            xs.add(x);
            header.append(String.format("%4.0f", x));
        }
        System.out.println("hits out of " + (4 * seeds) + ", angle " + angle + ", variant '" + variant + "', rpm scale " + scale);
        System.out.println(header);
        double yMin = Double.parseDouble(System.getProperty("ymin", "-66"));
        double yMax = Double.parseDouble(System.getProperty("ymax", "-26"));
        for (double y = yMin; y <= yMax; y += 4) {
            StringBuilder row = new StringBuilder(String.format("%5.0f ", y));
            for (double x : xs) {
                double d = Math.hypot(opening[0] - x, opening[1] - y);
                if (d < 20 || Math.hypot(x + 24.73, y + 19.48) < 13) {
                    row.append("   .");
                    continue;
                }
                int hits = 0;
                for (int seed = 1; seed <= seeds; seed++) {
                    SimConfig cfg = SimConfig.load(Paths.get("config"), vs).withAiOverrides(Map.of("enabled", false));
                    Simulation sim = new Simulation(cfg, vs);
                    sim.setup(null, null, (long) seed);
                    sim.robots().get(1).setPose(new Pose2d(Units.inToM(-63), Units.inToM(20), 0)); // partner out of the way
                    double hx = Units.inToM(x);
                    double hy = Units.inToM(y);
                    double heading = Math.atan2(Units.inToM(opening[1]) - hy, Units.inToM(opening[0]) - hx);
                    sim.ourRobot().setPose(new Pose2d(hx, hy, heading));
                    double exitD = d + FieldConstants.SHOT_EXIT_FORWARD * -1;
                    rpm = ShotSolver.rpm(exitD, opening[2] - FieldConstants.SHOT_EXIT_HEIGHT, angle) * scale;
                    hood = variant.equals("hood") ? (angle - 30) / 30.0 : Double.NaN;
                    if (Double.isNaN(rpm)) {
                        sim.shutdown();
                        continue;
                    }
                    sim.initOpMode(Fire.class, true);
                    sim.runFor(0.2);
                    sim.command("start", null);
                    sim.runFor(4.8);
                    int h = (int) sim.world().balls.stream().filter(b -> "US".equals(b.launchedBy)
                            && (b.state == Ball.State.IN_CELL)).count();
                    if (System.getProperty("debug") != null) {
                        System.out.printf("DBG x %.0f y %.0f seed %d rpm %.0f shots %d hits %d tips %d err %s%n", x, y, seed, rpm,
                                sim.mechanisms().shots, h, sim.world().hives.get(Alliance.RED).tips, sim.runner().error());
                    }
                    hits += h;
                    sim.shutdown();
                }
                row.append(String.format("%4d", hits));
            }
            System.out.println(row);
        }
        Tunables.restoreDefaults();
    }
}
