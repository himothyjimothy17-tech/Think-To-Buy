package org.biobuzz.sim.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * All three config files, loaded together:
 *   game.jsonc   - rules and field (from the manual)
 *   robot.jsonc  - our robot (with any design variants applied on top)
 *   hubs.jsonc   - the Driver Station robot configuration
 *   ai.jsonc     - how the other three robots drive and play
 */
public final class SimConfig {

    public final Path configDir;
    public final Cfg game;
    public final Cfg robot;
    public final Cfg hubs;
    /** The other robots' AI (ai.jsonc). */
    public final Cfg ai;
    public final List<String> variants;

    private SimConfig(Path configDir, Cfg game, Cfg robot, Cfg hubs, Cfg ai, List<String> variants) {
        this.configDir = configDir;
        this.game = game;
        this.robot = robot;
        this.hubs = hubs;
        this.ai = ai;
        this.variants = variants;
    }

    /**
     * Loads the config folder. Each name in {@code variantNames} is a file in
     * config/variants/ (without ".jsonc") whose values override robot.jsonc.
     */
    @SuppressWarnings("unchecked")
    public static SimConfig load(Path configDir, List<String> variantNames) throws IOException {
        Map<String, Object> game = (Map<String, Object>) read(configDir.resolve("game.jsonc"));
        Map<String, Object> robot = (Map<String, Object>) read(configDir.resolve("robot.jsonc"));
        Map<String, Object> hubs = (Map<String, Object>) read(configDir.resolve("hubs.jsonc"));
        Map<String, Object> ai = (Map<String, Object>) read(configDir.resolve("ai.jsonc"));
        List<String> applied = new ArrayList<>();
        for (String v : variantNames) {
            if (v == null || v.isBlank()) {
                continue;
            }
            Path file = configDir.resolve("variants").resolve(v + ".jsonc");
            if (!Files.exists(file)) {
                throw new IOException("Unknown design variant \"" + v + "\" (no file " + file + ")");
            }
            robot = Cfg.deepMerge(robot, (Map<String, Object>) read(file));
            applied.add(v);
        }
        return new SimConfig(configDir,
                new Cfg(game, "game.jsonc"),
                new Cfg(robot, "robot.jsonc" + (applied.isEmpty() ? "" : " + " + applied)),
                new Cfg(hubs, "hubs.jsonc"),
                new Cfg(ai, "ai.jsonc"),
                applied);
    }

    /** Returns a copy with {@code robotOverrides} applied (used by the live tuning panel later). */
    public SimConfig withRobotOverrides(Map<String, Object> robotOverrides) {
        return new SimConfig(configDir, game,
                new Cfg(Cfg.deepMerge(robot.raw(), robotOverrides), "robot.jsonc (tuned)"),
                hubs, ai, variants);
    }

    /** Returns a copy with {@code aiOverrides} applied (e.g. {"enabled": false}). */
    public SimConfig withAiOverrides(Map<String, Object> aiOverrides) {
        return new SimConfig(configDir, game, robot, hubs, new Cfg(Cfg.deepMerge(ai.raw(), aiOverrides), "ai.jsonc (overridden)"),
                variants);
    }

    private static Object read(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Missing config file: " + file.toAbsolutePath());
        }
        return Jsonc.parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }
}
