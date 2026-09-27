package org.biobuzz.sim;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.ElementKind;
import org.biobuzz.sim.game.Flower;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.geom.Pose2d;
import org.biobuzz.sim.robot.BallMechanisms;
import org.biobuzz.sim.util.Units;
import org.firstinspires.ftc.teamcode.Intake;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.firstinspires.ftc.teamcode.Shooter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3 checks: staging, flywheel spin-up / drop / recovery, servo
 * travel, shooting into a CELL, intake (including jams), pulling POLLEN out
 * of a FLOWER, the Limelight and the possession sensor.
 */
@Timeout(120)
class Stage3Test {

    static volatile double r1;
    static volatile double r2;
    static volatile double r3;
    static volatile double r4;
    static volatile List<String> seen = new ArrayList<>();

    private Simulation sim;

    private Simulation newSim(Map<String, Object> robotOverrides) throws Exception {
        SimConfig cfg = SimConfig.load(Paths.get("config"), List.of());
        if (robotOverrides != null) {
            cfg = cfg.withRobotOverrides(robotOverrides);
        }
        sim = new Simulation(cfg, List.of());
        return sim;
    }

    @AfterEach
    void tearDown() {
        if (sim != null) {
            sim.shutdown();
        }
    }

    private void run(Class<? extends LinearOpMode> type, double seconds) {
        sim.initOpMode(type, false);
        sim.runFor(0.3);
        sim.command("start", null);
        sim.runFor(seconds);
    }

    private long count(ElementKind kind, Ball.State state) {
        return sim.world().balls.stream().filter(b -> b.kind == kind && b.state == state).count();
    }

    @Test
    void stagingMatchesTheManual() throws Exception {
        newSim(null);
        List<Ball> all = sim.world().balls;
        assertEquals(40, all.stream().filter(b -> b.kind == ElementKind.POLLEN).count(), "§9.8: 40 POLLEN");
        assertEquals(8, all.stream().filter(b -> b.kind == ElementKind.RED_NECTAR).count(), "§9.8: 8 red NECTAR");
        assertEquals(8, all.stream().filter(b -> b.kind == ElementKind.BLUE_NECTAR).count(), "§9.8: 8 blue NECTAR");
        assertEquals(16, count(ElementKind.POLLEN, Ball.State.IN_FLOWER), "4 POLLEN in each of 4 FLOWERS");
        assertEquals(16, count(ElementKind.POLLEN, Ball.State.HELD), "4 pre-loads in each of 4 robots");
        assertEquals(8, count(ElementKind.POLLEN, Ball.State.FREE), "4 POLLEN in each GARDEN");
        assertEquals(3, count(ElementKind.RED_NECTAR, Ball.State.IN_CELL), "3 red NECTAR in the red up CELL");
        assertEquals(5, count(ElementKind.RED_NECTAR, Ball.State.STAGED), "5 red NECTAR in the ALLIANCE AREA");
        for (Flower f : sim.world().flowers) {
            assertNotNull(f.pocket(), "each FLOWER has a POLLEN in its bottom pocket");
            assertEquals(3, f.tube().size());
            assertEquals(Units.inToM(2.8) / 2, f.pocket().radius, 1e-9);
        }
        // 3 NECTAR (135 g) must not tip the HIVE by themselves.
        sim.runFor(1.0);
        assertEquals(0, sim.world().hives.get(Alliance.RED).tips);
    }

    /** Spin up to 3500 RPM, fire one ball, measure the drop and recovery. */
    public static class FlywheelShot extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            Shooter s = new Shooter(r);
            waitForStart();
            ElapsedTime t = new ElapsedTime();
            s.setTargetRpm(3500);
            while (opModeIsActive() && !s.atSpeed()) {
                idle();
            }
            r1 = t.seconds();
            sleep(500);
            s.openGate();
            ElapsedTime g = new ElapsedTime();
            double min = 1e9;
            while (opModeIsActive() && g.seconds() < 0.6) {
                min = Math.min(min, s.getRpm());
                if (s.getRpm() < 3400) {
                    s.closeGate();
                }
            }
            r2 = 3500 - min;
            ElapsedTime rec = new ElapsedTime();
            while (opModeIsActive() && !s.atSpeed() && rec.seconds() < 2) {
                idle();
            }
            r3 = rec.seconds();
        }
    }

    @Test
    void flywheelSpinsUpDropsAndRecovers() throws Exception {
        newSim(null);
        run(FlywheelShot.class, 5.0);
        System.out.printf("flywheel: spin-up to 3500 RPM %.2f s, drop per shot %.0f RPM, recovery %.2f s%n", r1, r2, r3);
        assertTrue(r1 > 0.4 && r1 < 1.5, "spin-up time " + r1);
        assertTrue(r2 > 50 && r2 < 600, "a shot should cost some RPM: " + r2);
        assertTrue(r3 < 1.0, "recovery " + r3);
        // The gate servo needs ~0.36 s to close, so a second ball often slips through
        // before it shuts - a real effect of servo travel time.
        assertTrue(sim.mechanisms().shots >= 1 && sim.mechanisms().shots <= 2);
    }

    @Test
    void gateServoTakesRealTimeToOpen() throws Exception {
        newSim(null);
        sim.hardware().servosByRole.get("shooter.servo").setPosition(0.0);
        sim.runFor(0.5);
        sim.hardware().servosByRole.get("shooter.servo").setPosition(0.3);
        sim.runFor(0.2);
        double midway = sim.mechanisms().servoActual();
        sim.runFor(0.3);
        System.out.printf("gate servo: %.2f after 0.2 s, %.2f after 0.5 s (target 0.30)%n", midway, sim.mechanisms().servoActual());
        // 60 deg / 0.20 s at 6 V, x5/6 at the hub's 5 V, over a 300 deg range = 0.83 position/s.
        assertEquals(0.167, midway, 0.02);
        assertEquals(0.30, sim.mechanisms().servoActual(), 1e-6);
    }

    /** Shoots all preloads at a fixed RPM (set before the run). */
    public static class ShootPreloads extends LinearOpMode {
        static double rpm;

        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            Shooter s = new Shooter(r);
            waitForStart();
            s.setTargetRpm(rpm);
            ElapsedTime t = new ElapsedTime();
            while (opModeIsActive() && t.seconds() < 5) {
                if (t.seconds() > 0.8) {
                    s.fire();
                }
            }
            s.setTargetRpm(0);
        }
    }

    @Test
    void shotsFromTheAudienceCornerGoIntoTheUpCell() throws Exception {
        newSim(null);
        Hive hive = sim.world().hives.get(Alliance.RED);
        double[] target = hive.upOpeningCenter();
        double sx = Units.inToM(-62);
        double sy = Units.inToM(-62);
        sim.ourRobot().setPose(new Pose2d(sx, sy, Math.atan2(target[1] - sy, target[0] - sx)));
        double dist = Units.mToIn(Math.hypot(target[0] - sx, target[1] - sy));
        ShootPreloads.rpm = Shooter.rpmForShot(dist + 1, Units.mToIn(target[2]) - 15, 45);
        run(ShootPreloads.class, 6.0);
        long inCell = sim.world().balls.stream().filter(b -> b.state == Ball.State.IN_CELL && b.launchedBy != null).count();
        System.out.printf("corner shots: %d of 4 in the CELL (up-cell mass %.0f g, tip at %.0f g)%n", inCell,
                hive.upCellMass() * 1000, hive.tipMassKg * 1000);
        assertTrue(inCell >= 2, "at least 2 of 4 should go in from the tuned corner spot, got " + inCell);
        assertEquals(4, sim.mechanisms().shots);
    }

    /** Intake with jam handling while driving slowly. */
    public static class DriveAndIntake extends LinearOpMode {
        static double power;
        static double seconds;

        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            Intake in = new Intake(r);
            waitForStart();
            ElapsedTime t = new ElapsedTime();
            while (opModeIsActive() && t.seconds() < seconds) {
                in.intake();
                for (DcMotorEx m : r.driveMotors()) {
                    m.setPower(power);
                }
            }
            for (DcMotorEx m : r.driveMotors()) {
                m.setPower(0);
            }
            ElapsedTime extra = new ElapsedTime();
            while (opModeIsActive() && extra.seconds() < 1.0) {
                in.intake();
            }
            r1 = in.getJamCount();
            in.stop();
        }
    }

    @Test
    void intakeCollectsTheGardenOneAtATime() throws Exception {
        newSim(null);
        sim.mechanisms().dumpAll();
        // Drive along the audience wall toward the red corner, over the GARDEN line.
        // (Move our partner out of the lane - its start spot is right next to the GARDEN.)
        sim.robots().get(1).setPose(new Pose2d(Units.inToM(-40), Units.inToM(20), 0));
        sim.ourRobot().setPose(new Pose2d(Units.inToM(-38), Units.inToM(-63.4), Math.PI));
        DriveAndIntake.power = 0.25;
        DriveAndIntake.seconds = 2.2;
        run(DriveAndIntake.class, 3.6);
        BallMechanisms m = sim.mechanisms();
        System.out.printf("garden sweep: held %d, pickups %d, jams %d (TeamCode cleared %.0f)%n", m.heldCount(), m.pickups,
                m.jams, r1);
        assertTrue(m.pickups >= 3, "should collect most of the GARDEN line, got " + m.pickups);
    }

    @Test
    void twoBallsAtOnceJamAndTheIntakeClassClearsIt() throws Exception {
        newSim(null);
        sim.mechanisms().dumpAll();
        sim.ourRobot().setPose(new Pose2d(0, 0, 0));
        // Two POLLEN side by side just in front of the bumper.
        int placed = 0;
        for (Ball b : sim.world().balls) {
            if (b.kind == ElementKind.POLLEN && b.state == Ball.State.FREE && placed < 2) {
                b.setPosition(Units.inToM(10.5), Units.inToM(placed == 0 ? -1.6 : 1.6), b.radius);
                b.stop();
                b.asleep = true;
                placed++;
            }
        }
        DriveAndIntake.power = 0.0;
        DriveAndIntake.seconds = 1.5;
        run(DriveAndIntake.class, 2.8);
        BallMechanisms m = sim.mechanisms();
        System.out.printf("jam test: jams %d, TeamCode noticed %.0f, intake now %s, held %d%n", m.jams, r1,
                m.intakeState(), m.heldCount());
        assertTrue(m.jams >= 1, "two balls at once should jam");
        assertTrue(r1 >= 1, "Intake.java should notice the jam from current + speed");
        assertFalse(m.intakeState() == BallMechanisms.IntakeState.JAMMED, "and clear it");
    }

    @Test
    void intakePullsPollenOutOfAFlowerBottom() throws Exception {
        newSim(null);
        sim.mechanisms().dumpAll();
        // The red-wall FLOWER (on the x = -72 wall, at y = -24).
        Flower f = sim.world().flowers.stream().filter(fl -> fl.x < -1.5).findFirst().orElseThrow();
        // Face it from the field side, mouth right at it.
        sim.ourRobot().setPose(new Pose2d(f.x + Units.inToM(8.5 + 3.2), f.y, Math.PI));
        DriveAndIntake.power = 0.0;
        DriveAndIntake.seconds = 2.5;
        run(DriveAndIntake.class, 3.8);
        System.out.printf("FLOWER retrieval: held %d, FLOWER now has %d in tube, pocket %s%n",
                sim.mechanisms().heldCount(), f.tube().size(), f.pocket() == null ? "empty" : "full");
        assertEquals(4, sim.mechanisms().heldCount(), "all 4 POLLEN pulled out, one at a time");
    }

    /** Reads the Limelight in both pipelines. */
    public static class LookAround extends LinearOpMode {
        static int pipeline;

        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            r.limelight.pipelineSwitch(pipeline);
            r.limelight.start();
            waitForStart();
            sleep(300);
            LLResult res = r.limelight.getLatestResult();
            seen = new ArrayList<>();
            if (res != null && res.isValid()) {
                for (LLResultTypes.FiducialResult f : res.getFiducialResults()) {
                    seen.add("tag" + f.getFiducialId());
                }
                for (LLResultTypes.DetectorResult d : res.getDetectorResults()) {
                    seen.add(d.getClassName());
                }
                if (res.getBotpose() != null) {
                    r1 = res.getBotpose().getPosition().x;
                    r2 = res.getBotpose().getPosition().y;
                }
                r3 = res.getStaleness();
                r4 = res.getTx();
            }
        }
    }

    @Test
    void limelightSeesTagsUnderTheHive() throws Exception {
        newSim(null);
        sim.ourRobot().setPose(new Pose2d(Units.inToM(-12.75), Units.inToM(-40), Math.PI / 2));
        LookAround.pipeline = 0;
        r1 = Double.NaN;
        run(LookAround.class, 0.6);
        System.out.println("limelight apriltag: sees " + seen + String.format(", botpose (%.1f, %.1f) in, staleness %.0f ms",
                Units.mToIn(r1), Units.mToIn(r2), r3));
        assertTrue(seen.stream().anyMatch(s -> s.startsWith("tag3")), "should see red CELL tags");
        assertEquals(-12.75, Units.mToIn(r1), 4.0);
        assertEquals(-40, Units.mToIn(r2), 4.0);
    }

    @Test
    void limelightDetectsGardenPollen() throws Exception {
        newSim(null);
        // Looking along the audience wall at the red GARDEN line. With the camera tilted
        // 15 deg up (for AprilTags), floor balls are only in view from ~6 ft away.
        sim.ourRobot().setPose(new Pose2d(Units.inToM(5), Units.inToM(-60), Math.PI));
        LookAround.pipeline = 1;
        r4 = Double.NaN;
        run(LookAround.class, 0.6);
        System.out.println("limelight detector, partner in the way: " + seen);
        assertTrue(!seen.contains("pollen") || seen.size() < 4, "the partner robot should block most of the view");
        sim.shutdown();

        newSim(null);
        sim.robots().get(1).setPose(new Pose2d(Units.inToM(-40), Units.inToM(20), 0)); // partner out of the way
        sim.ourRobot().setPose(new Pose2d(Units.inToM(5), Units.inToM(-60), Math.PI));
        run(LookAround.class, 0.6);
        System.out.println("limelight detector: " + seen + String.format(", primary tx %.1f deg", r4));
        assertTrue(seen.contains("pollen"));
    }

    @Test
    void possessionSensorSeesABallAtTheGate() throws Exception {
        newSim(Map.of("possessionSensor", Map.of("enabled", true)));
        assertNotNull(sim.hardware().possessionSensor);
        double withBall = sim.hardware().possessionSensor.getDistance(
                org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.CM);
        sim.mechanisms().dumpAll();
        double empty = sim.hardware().possessionSensor.getDistance(
                org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.CM);
        assertTrue(withBall < 5 && empty > 15, withBall + " / " + empty);
    }
}
