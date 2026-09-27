package org.biobuzz.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.opmode.OpModeRunner;
import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.sim.util.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1 checks: the fake SDK, the field, and our real MecanumTeleOp
 * driving in the simulator.
 */
@Timeout(60) // a hang is a bug: fail instead of freezing the build
class Stage1Test {

    private Simulation sim;

    private Simulation newSim(String alliance) throws Exception {
        sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        if (alliance != null) {
            sim.command("alliance", alliance);
            sim.runFor(0.001);
        }
        return sim;
    }

    @AfterEach
    void tearDown() {
        if (sim != null) {
            sim.shutdown();
        }
    }

    private static Gamepad sticks(float lx, float ly, float rx) {
        Gamepad g = new Gamepad();
        g.left_stick_x = lx;
        g.left_stick_y = ly;
        g.right_stick_x = rx;
        return g;
    }

    /** INIT + START our real TeleOp. */
    private void startTeleOp() {
        sim.command("init", "Mecanum TeleOp");
        sim.runFor(0.5);
        assertEquals(OpModeRunner.State.INIT, sim.runner().state(), "TeleOp should be in INIT: " + sim.runner().error());
        sim.command("start", null);
        sim.runFor(0.2);
        assertEquals(OpModeRunner.State.RUNNING, sim.runner().state());
    }

    @Test
    void teleOpIsFoundInTeamCode() throws Exception {
        newSim(null);
        assertNotNull(sim.registry().find("Mecanum TeleOp"), "MecanumTeleOp should be discovered by its @TeleOp name");
    }

    @Test
    void noConfigWarningsForOurRobot() throws Exception {
        newSim(null);
        assertTrue(sim.warnings().isEmpty(), "unexpected warnings: " + sim.warnings());
    }

    @Test
    void stickForwardDrivesAwayFromRedDriver() throws Exception {
        newSim(null);
        SimRobot r = sim.ourRobot();
        double x0 = r.x;
        double y0 = r.y;
        startTeleOp();
        sim.setGamepad1(sticks(0, -1f, 0)); // stick pushed UP = negative y
        sim.runFor(1.0);
        double dx = Units.mToIn(r.x - x0);
        double dy = Units.mToIn(r.y - y0);
        assertTrue(dx > 20, "robot should move toward +x (away from red wall), moved " + dx + " in");
        assertTrue(Math.abs(dy) < 1, "robot should not drift sideways, drifted " + dy + " in");
    }

    @Test
    void strafeRightAndTurnRightGoTheRightWay() throws Exception {
        newSim(null);
        SimRobot r = sim.ourRobot();
        startTeleOp();
        // Red robot faces +x, so its right-hand side is -y.
        double y0 = r.y;
        sim.setGamepad1(sticks(1f, 0, 0));
        sim.runFor(0.8);
        assertTrue(Units.mToIn(r.y - y0) < -10, "strafe right should move toward -y");

        sim.setGamepad1(sticks(0, 0, 0));
        sim.runFor(0.5);
        double h0 = r.heading;
        sim.setGamepad1(sticks(0, 0, 0.5f));
        sim.runFor(0.5);
        assertTrue(r.heading < h0 - 0.3, "right stick right should turn clockwise (heading decreases)");
    }

    @Test
    void fieldCentricWorksForBlueToo() throws Exception {
        newSim("blue");
        SimRobot r = sim.ourRobot();
        double x0 = r.x;
        startTeleOp();
        sim.setGamepad1(sticks(0, -1f, 0));
        sim.runFor(1.0);
        assertTrue(Units.mToIn(r.x - x0) < -20, "blue robot should drive toward -x (away from the blue wall)");
    }

    @Test
    void wallsStopTheRobot() throws Exception {
        newSim(null);
        SimRobot r = sim.ourRobot();
        startTeleOp();
        sim.setGamepad1(sticks(0, 1f, 0)); // backwards into the red wall
        sim.runFor(1.0);
        for (double[] c : r.corners()) {
            assertTrue(c[0] >= -72 * 0.0254 - 1e-6, "robot corner went through the wall");
        }
    }

    @Test
    void sameInputsGiveExactlyTheSameResult() throws Exception {
        double[] a = scriptedRun();
        sim.shutdown();
        double[] b = scriptedRun();
        assertEquals(a[0], b[0], 0.0, "x must be bit-for-bit identical");
        assertEquals(a[1], b[1], 0.0, "y must be bit-for-bit identical");
        assertEquals(a[2], b[2], 0.0, "heading must be bit-for-bit identical");
    }

    private double[] scriptedRun() throws Exception {
        newSim(null);
        startTeleOp();
        sim.setGamepad1(sticks(0.3f, -0.8f, 0.2f));
        sim.runFor(1.3);
        sim.setGamepad1(sticks(-0.5f, 0.1f, -0.4f));
        sim.runFor(0.9);
        SimRobot r = sim.ourRobot();
        return new double[] {r.x, r.y, r.heading};
    }

    @Test
    void loopTimeIsRealistic() throws Exception {
        newSim(null);
        startTeleOp();
        sim.runFor(0.5);
        String loopLine = sim.telemetryLines().stream().filter(l -> l.startsWith("Loop")).findFirst().orElse("");
        double ms = Double.parseDouble(loopLine.replaceAll("[^0-9.]", ""));
        // With bulk reads: 1 bulk read + IMU + voltage + motor writes, plus overhead.
        assertTrue(ms > 3 && ms < 20, "loop time should be realistic, was " + ms + " ms");
    }

    /** An OpMode with a hardware-name typo, like a teammate might write. */
    public static class TypoOpMode extends LinearOpMode {
        @Override
        public void runOpMode() {
            hardwareMap.get(DcMotor.class, "frontleft"); // lower-case L: typo!
            waitForStart();
        }
    }

    @Test
    void hardwareNameTypoFailsLikeOnTheRobot() throws Exception {
        newSim(null);
        sim.initOpMode(TypoOpMode.class, false);
        sim.runFor(0.2);
        assertEquals(OpModeRunner.State.CRASHED, sim.runner().state());
        assertTrue(sim.runner().error().contains("Unable to find a hardware device with name \"frontleft\""),
                sim.runner().error());
    }

    /** An OpMode that ignores STOP (forgot opModeIsActive()). */
    public static class IgnoresStopOpMode extends LinearOpMode {
        @Override
        public void runOpMode() {
            waitForStart();
            while (true) {
                sleep(10);
            }
        }
    }

    @Test
    void opModeThatIgnoresStopIsKilled() throws Exception {
        newSim(null);
        sim.initOpMode(IgnoresStopOpMode.class, false);
        sim.runFor(0.1);
        sim.command("start", null);
        sim.runFor(0.5);
        sim.command("stop", null);
        sim.runFor(2.0);
        assertEquals(OpModeRunner.State.STOPPED, sim.runner().state());
        assertTrue(sim.runner().error() != null && sim.runner().error().contains("opModeIsActive"));
    }

    /** An OpMode stuck in a loop that never calls the SDK. */
    public static class EndlessLoopOpMode extends LinearOpMode {
        @Override
        public void runOpMode() {
            waitForStart();
            long spins = 0;
            // Exits only when interrupted, so the test doesn't leave a spinning thread behind.
            while (!Thread.currentThread().isInterrupted()) {
                spins++;
            }
            telemetry.addData("spins", spins);
        }
    }

    @Test
    void opModeStuckWithoutSdkCallsIsKilledByWatchdog() throws Exception {
        newSim(null);
        sim.initOpMode(EndlessLoopOpMode.class, false);
        sim.runFor(0.1);
        sim.command("start", null);
        sim.runFor(0.5);
        assertEquals(OpModeRunner.State.CRASHED, sim.runner().state());
        assertTrue(sim.runner().error().contains("no SDK calls"), sim.runner().error());
    }
}
