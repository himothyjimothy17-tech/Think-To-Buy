package org.biobuzz.sim;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.RuleChecker;
import org.biobuzz.sim.game.ScoreKeeper;
import org.biobuzz.sim.robot.SimRobot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.biobuzz.sim.util.Units.inToM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 9 checks: our AUTO is LEGAL and does what it says, for both
 * alliances, with the other three robots playing. (How WELL it scores is
 * measured with the headless batch runs; see README.)
 */
@Timeout(600)
class Stage9Test {

    private static Simulation run(Alliance a, long seed, boolean ai) throws Exception {
        SimConfig cfg = SimConfig.load(Paths.get("config"), List.of());
        if (!ai) {
            cfg = cfg.withAiOverrides(Map.of("enabled", false));
        }
        Simulation sim = new Simulation(cfg, List.of());
        sim.setup(a, "redWallAudienceEnd", seed); // red-frame name; the sim turns it 180 deg for BLUE
        sim.runMatch(a == Alliance.RED ? "BIOBUZZ Auto RED" : "BIOBUZZ Auto BLUE", "", true);
        return sim;
    }

    private static void assertLegalAndComplete(Simulation sim, Alliance a) {
        assertTrue(sim.startProblems().isEmpty(), "G304 start: " + sim.startProblems());
        for (RuleChecker.Foul f : sim.rules().all()) {
            if (f.robot.equals("US")) {
                // Our AUTO must not earn even a warning.
                throw new AssertionError("our AUTO broke a rule: " + f);
            }
        }
        SimRobot us = sim.ourRobot();
        ScoreKeeper sk = sim.score();
        assertFalse(sk.touchingWall(us), "LEAVE: must end AUTO off the wall");
        assertTrue(ScoreKeeper.overlaps(us, sim.world().field().loadingZone.get(a)), "PARK: must end in our LOADING ZONE");
        assertTrue(sk.get(a).autoTips >= 1, "at least one TIP in AUTO");
        assertFalse(sim.buildReport(sim.nowSeconds()).contains("TRANSITION\""), "every TIP must finish before AUTO ends");
        assertEquals(null, sim.runner().error());
    }

    @Test
    void redAutoIsLegalWithAiRobots() throws Exception {
        for (long seed : new long[] {1, 6, 12}) {
            Simulation sim = run(Alliance.RED, seed, true);
            try {
                assertLegalAndComplete(sim, Alliance.RED);
            } finally {
                sim.shutdown();
            }
        }
    }

    @Test
    void blueAutoIsTheMirrorImage() throws Exception {
        for (long seed : new long[] {2, 9}) {
            Simulation sim = run(Alliance.BLUE, seed, true);
            try {
                assertLegalAndComplete(sim, Alliance.BLUE);
            } finally {
                sim.shutdown();
            }
        }
    }

    @Test
    void soloAutoTipsTheHiveOnItsOwn() throws Exception {
        // No help from anyone: LEAVE 3 + PARK 5 + one TIP 20 = 28.
        for (long seed : new long[] {3, 4}) {
            Simulation sim = run(Alliance.RED, seed, false);
            try {
                assertLegalAndComplete(sim, Alliance.RED);
                assertEquals(28, sim.score().get(Alliance.RED).autoTotal(
                        SimConfig.load(Paths.get("config"), List.of()).game.obj("points")));
            } finally {
                sim.shutdown();
            }
        }
    }

    @Test
    void startPoseIsLegal() throws Exception {
        Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        try {
            SimRobot us = sim.ourRobot();
            assertEquals(inToM(-63.5), us.x, 1e-9);
            assertEquals(inToM(-40), us.y, 1e-9);
            assertTrue(sim.rules().checkStart(us, 4, us.length, us.width, us.height).isEmpty());
        } finally {
            sim.shutdown();
        }
    }
}
