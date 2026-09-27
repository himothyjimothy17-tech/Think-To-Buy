package org.biobuzz.sim;

import org.biobuzz.sim.config.Jsonc;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.core.Tunables;
import org.firstinspires.ftc.teamcode.Shooter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 8 checks: live tuning, the 10 Hz recorder and saved runs. */
@Timeout(120)
class Stage8Test {

    @Test
    @SuppressWarnings("unchecked")
    void tuneRecordAndSaveARun() throws Exception {
        Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        Path file = null;
        try {
            sim.onClientMessage(Map.of("type", "cmd", "cmd", "tune", "name", "Shooter.KP", "value", 42.0));
            sim.runFor(0.01);
            assertEquals(42.0, Shooter.KP);
            Map<String, Object> field = (Map<String, Object>) Jsonc.parse(sim.fieldMessage(), "field");
            List<Object> tunables = (List<Object>) field.get("tunables");
            assertTrue(tunables.stream().anyMatch(t -> "Shooter.KP".equals(((Map<String, Object>) t).get("name"))
                    && Double.valueOf(42.0).equals(((Map<String, Object>) t).get("value"))));

            sim.setup(null, null, 5L);
            sim.startMatch("Leave Auto", "");
            sim.runFor(6.0);
            file = sim.saveRun("stage8-test-run");
            Map<String, Object> run = (Map<String, Object>) Jsonc.parse(Files.readString(file), "run");
            assertEquals("biobuzz-run", run.get("type"));
            List<Object> frames = (List<Object>) run.get("frames");
            // ~10 frames per second from the moment the match started (INIT takes 1 s).
            assertTrue(frames.size() >= 45 && frames.size() <= 55, "frames: " + frames.size());
            Map<String, Object> f0 = (Map<String, Object>) frames.get(0);
            assertEquals(4, ((List<Object>) f0.get("robots")).size());
            assertTrue(((List<Object>) f0.get("balls")).size() > 50);
            assertEquals(5.0, ((Map<String, Object>) run.get("report")).get("seed"));
            assertEquals(42.0, ((Map<String, Object>) run.get("tunables")).get("Shooter.KP"));
        } finally {
            sim.shutdown();
            Tunables.restoreDefaults();
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }
}
