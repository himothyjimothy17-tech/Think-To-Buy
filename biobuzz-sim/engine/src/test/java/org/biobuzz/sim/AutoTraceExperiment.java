package org.biobuzz.sim;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.core.Tunables;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.util.Units;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Experiment: run our AUTO once and print what it's doing every 0.5 s.
 * -Pexp.seed=3 -Pexp.ai=on -Pexp.variant=hood -Pexp.auto="BIOBUZZ Auto RED" -Pexp.set="A.B=1;C.D=2"
 */
@Tag("experiment")
class AutoTraceExperiment {
    @Test
    void trace() throws Exception {
        String variant = System.getProperty("variant", "");
        List<String> vs = variant.isEmpty() ? List.of() : List.of(variant);
        SimConfig cfg = SimConfig.load(Paths.get("config"), vs);
        if (!"on".equals(System.getProperty("ai", "off"))) {
            cfg = cfg.withAiOverrides(Map.of("enabled", false));
        }
        Simulation sim = new Simulation(cfg, vs);
        for (String s : System.getProperty("set", "").split(";")) {
            if (!s.isBlank()) {
                Tunables.setFromText(s);
            }
        }
        sim.setup(null, null, Long.getLong("seed", 1L));
        sim.startMatch(System.getProperty("auto", "BIOBUZZ Auto RED"), "");
        Hive red = sim.world().hives.get(Alliance.RED);
        double fineFrom = Double.parseDouble(System.getProperty("fineFrom", "-1"));
        double fineTo = Double.parseDouble(System.getProperty("fineTo", "-1"));
        while (sim.nowSeconds() < fineFrom) {
            sim.runFor(0.1);
        }
        while (sim.nowSeconds() < fineTo) {
            sim.runFor(Double.parseDouble(System.getProperty("fineStep", "0.1")));
            StringBuilder m = new StringBuilder();
            for (org.biobuzz.sim.physics.MotorState ms : sim.ourRobot().drivetrain.motors()) {
                m.append(String.format(" %.2f", ms.appliedPower));
            }
            if (System.getProperty("nectar") != null) {
                StringBuilder bs = new StringBuilder();
                for (org.biobuzz.sim.game.Ball b : sim.world().balls) {
                    if (b.kind == org.biobuzz.sim.game.ElementKind.RED_NECTAR && b.state != org.biobuzz.sim.game.Ball.State.STAGED
                            && b.state != org.biobuzz.sim.game.Ball.State.IN_CELL) {
                        bs.append(String.format(" [%d %s %.1f,%.1f %s]", b.id, b.state.name().charAt(0), Units.mToIn(b.x),
                                Units.mToIn(b.y), b.holder));
                    }
                }
                System.out.printf("N %.2f us (%.1f,%.1f,%.0f)%s%n", sim.nowSeconds(), Units.mToIn(sim.ourRobot().x),
                        Units.mToIn(sim.ourRobot().y), Math.toDegrees(sim.ourRobot().heading), bs);
            }
            if (System.getProperty("balls") != null) {
                StringBuilder bs = new StringBuilder();
                for (int id = 16; id < 20; id++) {
                    org.biobuzz.sim.game.Ball b = sim.world().balls.get(id);
                    bs.append(String.format(" [%d %s %.1f,%.1f]", id, b.state.name().charAt(0), Units.mToIn(b.x), Units.mToIn(b.y)));
                }
                System.out.printf("B %.2f front %.1f%s%n", sim.nowSeconds(),
                        Units.mToIn(sim.ourRobot().x) - 8.5, bs);
            }
            System.out.printf("M %.1f held %d gate %b servo %.2f intake %s rpm %.0f%n", sim.nowSeconds(),
                    sim.mechanisms().heldCount(), sim.mechanisms().ballAtGate(sim.nowSeconds()), sim.mechanisms().servoActual(),
                    sim.mechanisms().intakeState(), sim.mechanisms().flywheelRpm());
            System.out.printf("F %.1f us (%.1f,%.1f,%.0f) v (%.1f,%.1f) powers%s | %s%n", sim.nowSeconds(),
                    Units.mToIn(sim.ourRobot().x), Units.mToIn(sim.ourRobot().y), Math.toDegrees(sim.ourRobot().heading),
                    Units.mToIn(sim.ourRobot().vx), Units.mToIn(sim.ourRobot().vy), m, String.join(" | ", sim.telemetryLines()));
        }
        if (System.getProperty("flights") != null) {
            // Follow every ball we launch after t = flightsFrom, at 20 ms steps, until it lands or enters a CELL.
            double from = Double.parseDouble(System.getProperty("flights"));
            java.util.Map<Integer, StringBuilder> paths = new java.util.LinkedHashMap<>();
            while (sim.nowSeconds() < 30.5) {
                sim.runFor(0.02);
                if (sim.nowSeconds() < from) {
                    continue;
                }
                for (org.biobuzz.sim.game.Ball b : sim.world().balls) {
                    if ("US".equals(b.launchedBy) && b.launchedAt > from && sim.nowSeconds() - b.launchedAt < 1.2) {
                        paths.computeIfAbsent(b.id, k -> new StringBuilder()).append(String.format(" %s(%.0f,%.0f,%.0f)",
                                b.state.name().charAt(0), Units.mToIn(b.x), Units.mToIn(b.y), Units.mToIn(b.z)));
                    }
                }
            }
            paths.forEach((id, p) -> System.out.println("PATH " + id + ":" + p));
        }
        for (int i = 0; i < 80 && sim.timer().phase() != org.biobuzz.sim.core.MatchTimer.Phase.TELEOP; i++) {
            sim.runFor(0.5);
            List<String> tel = sim.telemetryLines();
            org.biobuzz.sim.robot.SimRobot pt = sim.robots().get(1);
            System.out.printf("P %5.1f partner (%5.1f,%5.1f) %s%n", sim.nowSeconds(), Units.mToIn(pt.x), Units.mToIn(pt.y),
                    sim.ais().isEmpty() ? "" : sim.ais().get(0).status());
            System.out.printf("T %5.1f %-10s us (%5.1f,%5.1f,%4.0f) cell %3.0f g tips %d shots %d picks %d jams %d | %s%n",
                    sim.nowSeconds(), sim.timer().phase(), Units.mToIn(sim.ourRobot().x), Units.mToIn(sim.ourRobot().y),
                    Math.toDegrees(sim.ourRobot().heading), red.upCellMass() * 1000, red.tips, sim.mechanisms().shots,
                    sim.mechanisms().pickups, sim.mechanisms().jams, String.join(" | ", tel) + " powered=" + sim.poweredReason());
        }
        for (double[] l : sim.mechanisms().launchLog) {
            org.biobuzz.sim.game.Ball b = sim.world().balls.get((int) l[1]);
            System.out.printf("SHOT t %.2f ball %d (%s) rpm %.0f at (%.1f,%.1f) hdg %.1f speed %.2f pitch %.1f yaw %.1f -> %s at (%.0f,%.0f,%.0f)%n",
                    l[0], (int) l[1], b.kind, l[2], Units.mToIn(l[3]), Units.mToIn(l[4]), Math.toDegrees(l[5]), l[6], l[7], l[8],
                    b.state, Units.mToIn(b.x), Units.mToIn(b.y), Units.mToIn(b.z));
        }
        System.out.println("REPORT " + sim.buildReport(sim.nowSeconds()));
        sim.shutdown();
    }
}
