package org.biobuzz.sim;

import org.biobuzz.sim.ai.AiRobot;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.util.Units;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

@Tag("experiment")
class AiProbeTest {
    @Test
    void probe() throws Exception {
        Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        sim.setup(null, null, Long.getLong("seed", 12L));
        sim.startMatch("", "");
        for (int i = 0; i < 90; i++) {
            sim.runFor(0.2);
            StringBuilder sb = new StringBuilder(String.format("t %.0f %s:", sim.nowSeconds(), sim.timer().phase()));
            for (AiRobot ai : sim.ais()) {
                sb.append(String.format("  %s (%.0f,%.0f,%.0f) v %.0f %s |", ai.body.id, Units.mToIn(ai.body.x),
                        Units.mToIn(ai.body.y), Math.toDegrees(ai.body.heading), Units.mToIn(Math.hypot(ai.body.vx, ai.body.vy)),
                        ai.status()));
            }
            sb.append(String.format(" score R %d B %d", sim.score().total(org.biobuzz.sim.field.Alliance.RED),
                    sim.score().total(org.biobuzz.sim.field.Alliance.BLUE)));
            System.out.println(sb);
        }
        sim.shutdown();
    }
}
