package com.qualcomm.robotcore.util;

import org.biobuzz.simhooks.SimHooks;

import java.util.concurrent.TimeUnit;

/**
 * A stopwatch. In the simulator it reads the SIMULATED clock, so timers in
 * our OpModes work the same whether the sim runs at 0.25x, 1x or 50x speed.
 *
 * Reading the clock costs a tiny bit of simulated time (like a real CPU
 * would), so a loop like {@code while (timer.seconds() < 2) {}} still ends.
 */
public class ElapsedTime {

    public enum Resolution { SECONDS, MILLISECONDS }

    public static final long SECOND_IN_NANO = 1_000_000_000L;
    public static final long MILLIS_IN_NANO = 1_000_000L;

    /** Simulated cost of reading the clock: 2 microseconds (ESTIMATE). */
    private static final long CLOCK_READ_COST_NS = 2_000L;

    protected volatile long nsStartTime;
    protected final double resolution;

    public ElapsedTime() {
        reset();
        this.resolution = SECOND_IN_NANO;
    }

    public ElapsedTime(long startTime) {
        this.nsStartTime = startTime;
        this.resolution = SECOND_IN_NANO;
    }

    public ElapsedTime(Resolution resolution) {
        reset();
        this.resolution = resolution == Resolution.MILLISECONDS ? MILLIS_IN_NANO : SECOND_IN_NANO;
    }

    protected long nsNow() {
        SimHooks.charge(CLOCK_READ_COST_NS);
        return SimHooks.nanoTime();
    }

    public long now(TimeUnit unit) {
        return unit.convert(nsNow(), TimeUnit.NANOSECONDS);
    }

    public void reset() {
        nsStartTime = nsNow();
    }

    public double startTime() {
        return nsStartTime / resolution;
    }

    public long startTimeNanoseconds() {
        return nsStartTime;
    }

    public double time() {
        return (nsNow() - nsStartTime) / resolution;
    }

    public long time(TimeUnit unit) {
        return unit.convert(nanoseconds(), TimeUnit.NANOSECONDS);
    }

    public double seconds() {
        return nanoseconds() * 1e-9;
    }

    public double milliseconds() {
        return seconds() * 1000.0;
    }

    public long nanoseconds() {
        return nsNow() - nsStartTime;
    }

    public Resolution getResolution() {
        return resolution == MILLIS_IN_NANO ? Resolution.MILLISECONDS : Resolution.SECONDS;
    }

    public void log(String label) {
        System.out.println(String.format("timer: %s = %s", label, this));
    }

    @Override
    public String toString() {
        return String.format("%.4f %s", time(), resolution == SECOND_IN_NANO ? "seconds" : "milliseconds");
    }
}
