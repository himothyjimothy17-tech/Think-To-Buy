package org.biobuzz.sim.util;

import java.util.Random;

/**
 * Seeded random numbers, split into independent named "streams".
 *
 * WHY STREAMS: if the IMU noise and the launch spread shared one random
 * generator, adding one more IMU reading would shift every later launch and
 * change the whole match. With a separate stream per subsystem (derived from
 * the match seed + the stream name), each part stays repeatable on its own.
 *
 * Same seed => same match, every time.
 */
public final class SimRandom {
    private final long seed;

    public SimRandom(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /** A new generator for one subsystem, e.g. stream("imu"). */
    public Random stream(String name) {
        long h = seed * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < name.length(); i++) {
            h = (h ^ name.charAt(i)) * 0x100000001B3L;
        }
        return new Random(h ^ (h >>> 29));
    }

    /** Normal (bell curve) random number with the given spread. */
    public static double gaussian(Random r, double sigma) {
        return r.nextGaussian() * sigma;
    }
}
