package org.biobuzz.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.geom.Pose2d;
import org.biobuzz.sim.util.Units;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.firstinspires.ftc.teamcode.Shooter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;

/**
 * An experiment (not a pass/fail test): from which spots and RPMs do our 4
 * preloaded POLLEN go into the red up-facing CELL? Prints a table.
 * Run with: ./gradlew :engine:test --tests '*ShootingRange*' -i
 */
@Tag("experiment")
@Timeout(900)
class ShootingRangeTest {

    static volatile double rpmToUse;

    /** Spins up to rpmToUse and fires all 4 preloads. */
    public static class ShootAll extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            Shooter s = new Shooter(r);
            waitForStart();
            s.setTargetRpm(rpmToUse);
            ElapsedTime t = new ElapsedTime();
            while (opModeIsActive() && t.seconds() < 6) {
                if (t.seconds() > 1.0) {
                    boolean was = s.isGateOpen();
                    s.fire();
                    if (!was && s.isGateOpen() && gateOpenedAtRpm == 0) {
                        gateOpenedAtRpm = s.getRpm();
                    }
                }
            }
            s.setTargetRpm(0);
        }
    }

    static volatile double gateOpenedAtRpm;

    @Test
    void mapTheShootingRange() throws Exception {
        System.out.println("   x(in)   y(in)  dist  rpmFormula  scale  inCell/4  flywheel@open");
        for (double x : new double[] {-62, -50, -38}) {
            for (double y : new double[] {-62, -55}) {
                for (double rpmScale : new double[] {0.88, 0.92, 0.96, 1.0}) {
                    Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
                    Hive hive = sim.world().hives.get(Alliance.RED);
                    double[] target = hive.upOpeningCenter();
                    double sx = Units.inToM(x);
                    double sy = Units.inToM(y);
                    double heading = Math.atan2(target[1] - sy, target[0] - sx);
                    sim.ourRobot().setPose(new Pose2d(sx, sy, heading));
                    double dist = Units.mToIn(Math.hypot(target[0] - sx, target[1] - sy));
                    double exitH = Units.mToIn(sim.mechanisms().exitHeight());
                    double rpm = Shooter.rpmForShot(dist + 1.0, Units.mToIn(target[2]) - exitH, 45);
                    rpmToUse = rpm * rpmScale;
                    gateOpenedAtRpm = 0;
                    sim.initOpMode(ShootAll.class, false);
                    sim.runFor(0.3);
                    sim.command("start", null);
                    sim.runFor(7.0);
                    long inCell = sim.world().balls.stream()
                            .filter(b -> b.state == Ball.State.IN_CELL && b.launchedBy != null).count();
                    System.out.printf("%8.0f%8.0f%6.0f%12.0f%7.2f%10d%15.0f%n", x, y, dist, rpm, rpmScale, inCell,
                            gateOpenedAtRpm);
                    sim.shutdown();
                }
            }
        }
    }
}
