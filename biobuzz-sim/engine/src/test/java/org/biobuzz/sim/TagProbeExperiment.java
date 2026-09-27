package org.biobuzz.sim;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.ElementKind;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.geom.Pose2d;
import org.biobuzz.sim.util.Units;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** Experiment: what do the HIVE AprilTags look like before/after a TIP, from shooting spots? */
@Tag("experiment")
class TagProbeExperiment {

    public static class Look extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            r.limelight.pipelineSwitch(0);
            r.limelight.start();
            waitForStart();
            while (opModeIsActive()) {
                sleep(250);
                LLResult res = r.limelight.getLatestResult();
                StringBuilder sb = new StringBuilder();
                if (res != null && res.isValid()) {
                    for (LLResultTypes.FiducialResult f : res.getFiducialResults()) {
                        sb.append(String.format(" id%d z%.1f", f.getFiducialId(),
                                f.getTargetPoseRobotSpace() == null ? -1 : f.getTargetPoseRobotSpace().getPosition().z / 0.0254));
                    }
                }
                System.out.println("  tags:" + sb);
            }
        }
    }

    @Test
    void probe() throws Exception {
        for (double[] spot : new double[][] {{-60, -58}, {-12.75, -52}, {-60, 58}}) {
            Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()).withAiOverrides(Map.of("enabled", false)),
                    List.of());
            Hive h = sim.world().hives.get(Alliance.RED);
            double[] c = h.upOpeningCenter();
            double sx = Units.inToM(spot[0]);
            double sy = Units.inToM(spot[1]);
            sim.robots().get(1).setPose(new Pose2d(Units.inToM(-63), Units.inToM(20), 0));
            sim.ourRobot().setPose(new Pose2d(sx, sy, Math.atan2(Math.abs(c[1]) * Math.signum(-spot[1]) - sy, c[0] - sx)));
            System.out.println("SPOT " + spot[0] + "," + spot[1]);
            sim.initOpMode(Look.class, true);
            sim.runFor(0.3);
            sim.command("start", null);
            sim.runFor(0.6);
            System.out.println(" -> tipping");
            for (Ball b : sim.world().balls) {
                if (b.kind == ElementKind.POLLEN && b.state == Ball.State.FREE && h.upCellMass() < 0.26) {
                    double[] o = h.upOpeningCenter();
                    double[] n = h.upOpeningNormal();
                    sim.world().launch(b, o[0] + n[0] * 0.08, o[1] + n[1] * 0.08, o[2] + n[2] * 0.08, -n[0] * 1.5,
                            -n[1] * 1.5, -n[2] * 1.5, "X", Alliance.RED, sim.nowSeconds());
                    sim.runFor(0.1);
                }
            }
            sim.runFor(2.0);
            System.out.println(" tips " + h.tips);
            sim.shutdown();
        }
    }
}
