package org.biobuzz.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.core.Tunables;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.opmode.OpModeRegistry;
import org.firstinspires.ftc.teamcode.Intake;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.firstinspires.ftc.teamcode.Shooter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 5 checks: the same seed gives the exact same match (so comparisons
 * are fair), different seeds differ, tunables and variants apply, and the
 * seed list parser.
 */
@Timeout(300)
class Stage5Test {

    /** Uses the randomness everywhere: driving (grip), intake, shots (spread), sensors. */
    public static class Busy extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            Shooter s = new Shooter(r);
            Intake in = new Intake(r);
            waitForStart();
            ElapsedTime t = new ElapsedTime();
            s.setTargetRpm(3600);
            while (opModeIsActive() && t.seconds() < 6) {
                double p = t.seconds() < 2 ? 0.5 : -0.3;
                for (DcMotorEx m : r.driveMotors()) {
                    m.setPower(p);
                }
                in.intake();
                if (t.seconds() > 1) {
                    s.fire();
                }
                telemetry.addData("yaw", r.imu.getRobotYawPitchRollAngles().getYaw(
                        org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES));
                telemetry.update();
            }
            for (DcMotorEx m : r.driveMotors()) {
                m.setPower(0);
            }
        }
    }

    private static String fingerprint(long seed) throws Exception {
        Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        try {
            sim.registry().register(OpModeRegistry.Entry.of(Busy.class, true));
            sim.setup(null, null, seed);
            String rep = sim.runMatch("Busy", "", true);
            StringBuilder sb = new StringBuilder(rep.replaceAll("\"wallSeconds\":[^,}]*", ""));
            for (Ball b : sim.world().balls) {
                sb.append(String.format("|%d %.9f %.9f %.9f", b.id, b.x, b.y, b.z));
            }
            sb.append(String.format("|robot %.12f %.12f %.12f", sim.ourRobot().x, sim.ourRobot().y, sim.ourRobot().heading));
            sb.append("|").append(sim.telemetryLines());
            return sb.toString();
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void sameSeedSameMatchDifferentSeedDifferentMatch() throws Exception {
        String a = fingerprint(7);
        String b = fingerprint(7);
        String c = fingerprint(8);
        assertEquals(a, b, "a match must be exactly repeatable from its seed");
        assertNotEquals(a, c, "a different seed should change something (placement, grip, noise)");
    }

    @Test
    void variantsSetTeamCodeTunablesAndResetBetweenSims() throws Exception {
        Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of("hood")), List.of("hood"));
        assertEquals(true, Tunables.get("Shooter.HOOD_MODE"));
        assertTrue(sim.mechanisms().hoodMode());
        Tunables.set("Shooter.KP", 12.5);
        assertEquals(12.5, Shooter.KP);
        sim.shutdown();
        sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        assertEquals(false, Tunables.get("Shooter.HOOD_MODE"), "a new sim starts from the code's own values");
        assertEquals(60.0, Shooter.KP);
        sim.shutdown();
    }

    @Test
    void seedListParser() {
        assertEquals(List.of(1L, 2L, 3L), Headless.parseSeeds("3"));
        assertEquals(List.of(5L, 6L, 7L), Headless.parseSeeds("5-7"));
        assertEquals(List.of(2L, 9L, 11L, 12L), Headless.parseSeeds("2,9,11-12"));
    }

    @Test
    void headlessRunOneGivesAReport() throws Exception {
        Headless.Row r = new Headless.Row();
        r.auto = "Leave Auto";
        String rep = Headless.runOne(Paths.get("config"), r, 3);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) org.biobuzz.sim.config.Jsonc.parse(rep, "r");
        assertEquals(3.0, Headless.metrics(m, true).get("score"));
        assertEquals(0.0, Headless.metrics(m, true).get("illegalStart"));
    }
}
