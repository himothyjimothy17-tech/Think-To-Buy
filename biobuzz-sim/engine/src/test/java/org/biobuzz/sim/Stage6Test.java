package org.biobuzz.sim;

import org.biobuzz.sim.ai.AiRobot;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.RuleChecker;
import org.biobuzz.sim.geom.Pose2d;
import org.biobuzz.sim.physics.Collisions;
import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.sim.util.Units;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6 checks: robots push each other by mass, and the three AI robots
 * play a sensible, legal match (score, stay on their side in AUTO, park).
 */
@Timeout(300)
class Stage6Test {

    @Test
    void heavierRobotWinsAShovingMatch() throws Exception {
        Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        try {
            double s = Units.inToM(17);
            SimRobot heavy = new SimRobot("H", Alliance.RED, false, s, s, s, new Pose2d(0, 0, 0));
            SimRobot light = new SimRobot("L", Alliance.BLUE, false, s, s, s, new Pose2d(s - 0.02, 0, Math.PI));
            heavy.massKg = 18;
            light.massKg = 9;
            // Both drive straight at each other at 1 m/s, re-accelerating every step.
            for (int i = 0; i < 1000; i++) {
                heavy.vx = 1.0;
                light.vx = -1.0;
                heavy.x += heavy.vx * 0.001;
                light.x += light.vx * 0.001;
                Collisions.resolveAll(List.of(heavy, light), sim.world().field());
            }
            // Momentum: (18 - 9) / 27 = 0.33 m/s toward the light robot after each bump.
            System.out.printf("shove: heavy moved %.2f m, light moved %.2f m%n", heavy.x, light.x - (s - 0.02));
            assertTrue(heavy.x > 0.25 && heavy.x < 0.45, "heavy robot should push the light one back: " + heavy.x);
            assertTrue(Math.abs(light.x - heavy.x - s) < 0.01, "they stay in contact without overlapping");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void aiRobotsPlayALegalMatch() throws Exception {
        int totalShots = 0;
        for (long seed = 1; seed <= 3; seed++) {
            Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
            try {
                sim.setup(null, null, seed);
                sim.runMatch("", "", false);
                for (AiRobot ai : sim.ais()) {
                    totalShots += ai.shots;
                    assertTrue(ai.pickups > 5, ai.body.id + " should collect balls, got " + ai.pickups);
                }
                for (RuleChecker.Foul f : sim.rules().all()) {
                    assertTrue(!(f.rule.equals("G402") && f.penalty == RuleChecker.Penalty.MAJOR), "no AUTO contact fouls: " + f);
                    assertTrue(!f.rule.equals("G403") && !f.rule.equals("G404") && !f.rule.equals("G410")
                            && !f.rule.equals("G407") && !f.rule.equals("G408"), "AI broke a rule: " + f);
                }
                int tips = sim.score().get(Alliance.RED).tips() + sim.score().get(Alliance.BLUE).tips();
                System.out.printf("seed %d: RED %d - BLUE %d, %d tips%n", seed, sim.score().total(Alliance.RED),
                        sim.score().total(Alliance.BLUE), tips);
                assertTrue(tips >= 3, "AI robots should tip HIVES");
                assertTrue(sim.score().get(Alliance.BLUE).teleopPark + sim.score().get(Alliance.RED).teleopPark >= 1);
            } finally {
                sim.shutdown();
            }
        }
        assertTrue(totalShots > 60);
    }

    @Test
    void aiStaysStillOutsideAutoAndTeleop() throws Exception {
        Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        try {
            SimRobot opp = sim.robots().get(2);
            double x0 = opp.x;
            sim.runFor(2.0); // PRE_MATCH
            assertEquals(x0, opp.x, 1e-9);
        } finally {
            sim.shutdown();
        }
    }
}
