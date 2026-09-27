package org.biobuzz.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.core.Tunables;
import org.biobuzz.sim.util.Units;
import org.firstinspires.ftc.teamcode.DriveController;
import org.firstinspires.ftc.teamcode.Localizer;
import org.firstinspires.ftc.teamcode.MecanumDrive;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Experiment: drive a route with DriveController + Localizer, and compare the
 * localizer's estimate with the simulator's truth at each waypoint.
 */
@Tag("experiment")
class DriveTuneExperiment {

    static final double[][] ROUTE = {{-40, -40, 45}, {-60, -58, 60}, {-40, -63, 180}, {-62, 40, 0}, {-30, 50, -90}, {-63.5, 0, 0}};
    static final List<String> out = new ArrayList<>();
    static Simulation current;
    static boolean useVision;

    public static class Route extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            r.limelight.pipelineSwitch(0);
            r.limelight.start();
            Localizer loc = new Localizer(r);
            DriveController dc = new DriveController(new MecanumDrive(r), loc);
            waitForStart();
            loc.setPose(-63.5, 0, 0);
            ElapsedTime total = new ElapsedTime();
            for (double[] w : ROUTE) {
                ElapsedTime t = new ElapsedTime();
                double th = Math.toRadians(w[2]);
                while (opModeIsActive() && t.seconds() < 4) {
                    loc.update();
                    if (useVision) {
                        loc.addVision(r.limelight.getLatestResult());
                    }
                    double d = dc.step(w[0], w[1], th, DriveController.MAX_SPEED_IN_S);
                    if (d < 1.0 && Math.abs(dc.headingError(th)) < Math.toRadians(2) && loc.getSpeed() < 3) {
                        break;
                    }
                }
                dc.stop();
                double tx = Units.mToIn(current.ourRobot().x);
                double ty = Units.mToIn(current.ourRobot().y);
                out.add(String.format("target (%5.1f,%5.1f) est (%5.1f,%5.1f) true (%5.1f,%5.1f) err %4.1f in  %.2f s",
                        w[0], w[1], loc.getX(), loc.getY(), tx, ty, Math.hypot(tx - loc.getX(), ty - loc.getY()), t.seconds()));
            }
            out.add(String.format("total %.1f s, vision fixes %d", total.seconds(), loc.getVisionFixes()));
        }
    }

    @Test
    void driveRoute() throws Exception {
        for (double lat : new double[] {1.0, 0.9, 0.8}) {
            for (boolean vision : new boolean[] {false, true}) {
                SimConfig cfg = SimConfig.load(Paths.get("config"), List.of()).withAiOverrides(Map.of("enabled", false));
                current = new Simulation(cfg, List.of());
                Tunables.set("Localizer.LATERAL_MULTIPLIER", lat);
                useVision = vision;
                out.clear();
                current.initOpMode(Route.class, true);
                current.runFor(0.3);
                current.command("start", null);
                current.runFor(29.5);
                System.out.println("LATERAL_MULTIPLIER " + lat + (vision ? " + vision" : ""));
                out.forEach(s -> System.out.println("  " + s));
                current.shutdown();
            }
        }
    }
}
