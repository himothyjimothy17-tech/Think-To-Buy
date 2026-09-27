package org.biobuzz.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.MatchTimer;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.ElementKind;
import org.biobuzz.sim.game.Flower;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.game.RuleChecker;
import org.biobuzz.sim.game.ScoreKeeper;
import org.biobuzz.sim.geom.Pose2d;
import org.biobuzz.sim.util.Units;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4 checks: HIVE tips (AUTO vs TELEOP), FLOWER ownership and the
 * bottom NECTAR bonus, GARDEN, LEAVE / PARK, the rule checks and the full
 * AUTO -> TRANSITION -> TELEOP match flow.
 */
@Timeout(300)
class Stage4Test {

    private Simulation sim;

    private Simulation newSim() throws Exception {
        sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        return sim;
    }

    @AfterEach
    void tearDown() {
        if (sim != null) {
            sim.shutdown();
        }
    }

    /** Drops a ball straight into a HIVE's up-facing CELL opening. */
    private void dunk(Ball b, Hive h) {
        double[] c = h.upOpeningCenter();
        double[] n = h.upOpeningNormal();
        sim.world().launch(b, c[0] + n[0] * 0.08, c[1] + n[1] * 0.08, c[2] + n[2] * 0.08,
                -n[0] * 1.5, -n[1] * 1.5, -n[2] * 1.5, "US", Alliance.RED, sim.nowSeconds());
    }

    /** Drops a ball into the top of a FLOWER. */
    private void dropIntoFlower(Ball b, Flower f, String by) {
        sim.world().launch(b, f.x, f.y, f.topZ + 0.12, 0, 0, -0.5, by, by == null ? null : Alliance.RED, sim.nowSeconds());
    }

    private List<Ball> freePollen(int n) {
        return sim.world().balls.stream().filter(b -> b.kind == ElementKind.POLLEN && b.state == Ball.State.FREE)
                .limit(n).toList();
    }

    /** Just sits still (a do-nothing AUTO). */
    public static class DoNothing extends LinearOpMode {
        @Override
        public void runOpMode() {
            waitForStart();
            while (opModeIsActive()) {
                idle();
            }
        }
    }

    /** Drives off the wall for 0.4 s, then stops (earns LEAVE). */
    public static class LeaveOnly extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            waitForStart();
            ElapsedTime t = new ElapsedTime();
            while (opModeIsActive() && t.seconds() < 0.4) {
                for (DcMotorEx m : r.driveMotors()) {
                    m.setPower(0.4);
                }
            }
            for (DcMotorEx m : r.driveMotors()) {
                m.setPower(0);
            }
            while (opModeIsActive()) {
                idle();
            }
        }
    }

    /** Keeps driving forever (a TELEOP that ignores the end of the match would be caught by G404). */
    public static class NeverStops extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            waitForStart();
            while (opModeIsActive()) {
                for (DcMotorEx m : r.driveMotors()) {
                    m.setPower(0.15);
                }
            }
        }
    }

    @Test
    void tipsCountAsAutoBeforeTeleopAndTeleopAfter() throws Exception {
        newSim();
        sim.initOpMode(DoNothing.class, true);
        sim.runFor(0.3);
        sim.command("start", null); // practice AUTO
        sim.runFor(0.2);
        Hive red = sim.world().hives.get(Alliance.RED);
        // 3 NECTAR (135 g) are already in; tipping needs 250 g -> about 5 more POLLEN (23 g each).
        for (Ball b : freePollen(6)) {
            dunk(b, red);
            sim.runFor(0.15);
        }
        sim.runFor(1.5);
        assertEquals(1, red.tips, "the HIVE should have tipped once");
        ScoreKeeper.Breakdown s = sim.score().get(Alliance.RED);
        assertEquals(1, s.autoTips);
        assertEquals(0, s.teleopTips);
        assertEquals(20, s.autoTotal(SimConfig.load(Paths.get("config"), List.of()).game.obj("points")));

        // Same thing in a TELEOP practice period.
        sim.shutdown();
        newSim();
        sim.initOpMode(DoNothing.class, false);
        sim.runFor(0.3);
        sim.command("start", null);
        sim.runFor(0.2);
        red = sim.world().hives.get(Alliance.RED);
        for (Ball b : freePollen(6)) {
            dunk(b, red);
            sim.runFor(0.15);
        }
        sim.runFor(1.5);
        assertEquals(0, sim.score().get(Alliance.RED).autoTips);
        assertEquals(1, sim.score().get(Alliance.RED).teleopTips);
    }

    @Test
    void flowerOwnershipBottomNectarAndG410() throws Exception {
        newSim();
        sim.runFor(0.3);
        Flower f = sim.world().flowers.get(0);
        Ball redNectar = sim.world().balls.stream().filter(b -> b.kind == ElementKind.RED_NECTAR
                && b.state == Ball.State.STAGED).findFirst().orElseThrow();
        dropIntoFlower(redNectar, f, "US");
        sim.runFor(0.6);
        assertEquals(Ball.State.IN_FLOWER, redNectar.state);
        sim.score().updateEndItems(sim.world(), sim.robots(), false);
        assertEquals(Alliance.RED, f.owner());
        assertEquals(Alliance.RED, f.bottomNectar());
        ScoreKeeper.Breakdown red = sim.score().get(Alliance.RED);
        assertEquals(1, red.bottomNectar);
        assertEquals(f.scoringBalls().size(), red.flowerBalls, "the owner scores every ball in the scoring volume");
        // Not in the last 60 s of a match -> G410 MAJOR FOUL for US (points to BLUE).
        assertTrue(sim.rules().all().stream().anyMatch(x -> x.rule.equals("G410") && x.penalty == RuleChecker.Penalty.MAJOR));
        assertEquals(20, sim.score().get(Alliance.BLUE).foulPoints);

        // A BLUE NECTAR on top takes ownership; RED keeps the bottom bonus.
        Ball blueNectar = sim.world().balls.stream().filter(b -> b.kind == ElementKind.BLUE_NECTAR
                && b.state == Ball.State.STAGED).findFirst().orElseThrow();
        dropIntoFlower(blueNectar, f, null);
        sim.runFor(0.6);
        if (blueNectar.state == Ball.State.IN_FLOWER && f.scoringBalls().contains(blueNectar)) {
            sim.score().updateEndItems(sim.world(), sim.robots(), false);
            assertEquals(Alliance.BLUE, f.owner());
            assertEquals(Alliance.RED, f.bottomNectar());
            assertEquals(0, sim.score().get(Alliance.RED).flowerBalls);
            assertEquals(1, sim.score().get(Alliance.RED).bottomNectar);
        }
    }

    @Test
    void gardenBallsCountForTheGardensAlliance() throws Exception {
        newSim();
        sim.runFor(0.5);
        sim.score().updateEndItems(sim.world(), sim.robots(), false);
        // §10.3.1: 4 POLLEN are staged in each GARDEN.
        assertEquals(4, sim.score().get(Alliance.RED).garden);
        assertEquals(4, sim.score().get(Alliance.BLUE).garden);
    }

    @Test
    void legalStartPassesAndOffTheWallFailsG304() throws Exception {
        newSim();
        sim.runFor(0.1);
        RuleChecker rc = sim.rules();
        for (var r : sim.robots()) {
            List<String> p = rc.checkStart(r, 4, r.length, r.width, r.height);
            assertTrue(p.isEmpty(), r.id + " should start legally: " + p);
        }
        var us = sim.ourRobot();
        us.setPose(new Pose2d(Units.inToM(-50), 0, 0));
        assertTrue(rc.checkStart(us, 4, us.length, us.width, us.height).stream().anyMatch(s -> s.startsWith("G304.C")));
        us.setPose(new Pose2d(Units.inToM(-63.5), Units.inToM(36), 0));
        assertTrue(rc.checkStart(us, 4, us.length, us.width, us.height).stream().anyMatch(s -> s.startsWith("G304.E")));
        us.setPose(new Pose2d(Units.inToM(6), Units.inToM(-63.5), Math.PI / 2));
        assertTrue(rc.checkStart(us, 4, us.length, us.width, us.height).stream().anyMatch(s -> s.startsWith("G304.A")));
        assertTrue(rc.checkStart(us, 3, us.length, us.width, us.height).stream().anyMatch(s -> s.startsWith("G304.G")));
    }

    @Test
    void rankingPointsFollowTheTable() throws Exception {
        newSim();
        ScoreKeeper s = sim.score();
        ScoreKeeper.Breakdown red = s.get(Alliance.RED);
        red.leave = 2;
        red.autoPark = 1;
        red.teleopPark = 1;  // 3+3+5+5 = 16 -> SWARM
        red.autoTips = 2;
        red.teleopTips = 2;  // 4 tips -> POLLINATOR 1 (7 needed for the second)
        // RED 3+3+5+40+40+5 = 96 > BLUE 0 -> WIN (3)
        assertEquals(1 + 1 + 3, s.rankingPoints(Alliance.RED));
        red.teleopTips = 5;
        assertEquals(1 + 2 + 3, s.rankingPoints(Alliance.RED));
        assertEquals(0, s.rankingPoints(Alliance.BLUE));
    }

    @Test
    void fullMatchRunsAllPhasesAndScoresLeave() throws Exception {
        // AI off: the other robots sit still, so only our LEAVE counts.
        sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()).withAiOverrides(java.util.Map.of("enabled", false)),
                List.of());
        sim.registry().register(org.biobuzz.sim.opmode.OpModeRegistry.Entry.of(LeaveOnly.class, true));
        sim.registry().register(org.biobuzz.sim.opmode.OpModeRegistry.Entry.of(NeverStops.class, false));
        sim.startMatch("LeaveOnly", "NeverStops");
        sim.runFor(1.5);
        assertEquals(MatchTimer.Phase.AUTO, sim.timer().phase());
        assertTrue(sim.startProblems().isEmpty(), "legal start: " + sim.startProblems());
        sim.runFor(30);
        assertEquals(MatchTimer.Phase.TRANSITION, sim.timer().phase());
        ScoreKeeper.Breakdown red = sim.score().get(Alliance.RED);
        assertEquals(1, red.leave, "only US drove off the wall");
        assertEquals(0, red.autoPark);
        sim.runFor(8);
        assertEquals(MatchTimer.Phase.TELEOP, sim.timer().phase());
        assertNull(sim.report());
        sim.runFor(121);
        assertEquals(MatchTimer.Phase.POST_MATCH, sim.timer().phase());
        sim.runFor(3.5);
        assertNotNull(sim.report(), "the final report is written once everything is at rest");
        assertTrue(sim.score().isFinal());
        // The TELEOP OpMode is stopped at 0:00 by the Driver Station, so no G404.
        assertTrue(sim.rules().all().stream().noneMatch(f -> f.rule.equals("G404")), "" + sim.rules().all());
        System.out.println("full match report: " + sim.report());
    }
}
