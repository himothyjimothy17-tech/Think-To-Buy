package org.biobuzz.sim;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.ElementKind;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.util.Units;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Experiment: where do the balls land after a red HIVE TIP (first TIP, audience CELL)? */
@Tag("experiment")
class SpillExperiment {
    @Test
    void spill() throws Exception {
        for (long seed = 1; seed <= 6; seed++) {
            Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()).withAiOverrides(Map.of("enabled", false)),
                    List.of());
            sim.setup(null, null, seed);
            Hive h = sim.world().hives.get(Alliance.RED);
            List<Ball> in = new ArrayList<>();
            for (Ball b : sim.world().balls) {
                if (b.state == Ball.State.IN_CELL && b.kind == ElementKind.RED_NECTAR) {
                    in.add(b);
                }
            }
            for (Ball b : sim.world().balls) {
                if (b.kind == ElementKind.POLLEN && b.state == Ball.State.FREE && h.upCellMass() < 0.25) {
                    double[] o = h.upOpeningCenter();
                    double[] n = h.upOpeningNormal();
                    sim.world().launch(b, o[0] + n[0] * 0.08, o[1] + n[1] * 0.08, o[2] + n[2] * 0.08, -n[0] * 1.5, -n[1] * 1.5,
                            -n[2] * 1.5, "X", Alliance.RED, sim.nowSeconds());
                    in.add(b);
                    sim.runFor(0.1);
                }
            }
            sim.runFor(3.0);
            StringBuilder sb = new StringBuilder("seed " + seed + " tips " + h.tips + ":");
            for (Ball b : in) {
                sb.append(String.format(" %s(%.0f,%.0f)", b.kind == ElementKind.POLLEN ? "p" : "N", Units.mToIn(b.x), Units.mToIn(b.y)));
            }
            System.out.println("SPILL " + sb);
            sim.shutdown();
        }
    }
}
