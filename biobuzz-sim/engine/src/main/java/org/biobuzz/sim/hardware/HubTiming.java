package org.biobuzz.sim.hardware;

import org.biobuzz.sim.config.Cfg;

/**
 * How much simulated time each kind of hardware call costs (from hubs.jsonc
 * "timingMs"). This is what makes the OpMode loop run at a realistic rate:
 * a loop that reads 8 encoders on the Expansion Hub is much slower than one
 * that reads 1.
 */
public final class HubTiming {
    public final long controlHubReadNs;
    public final long controlHubWriteNs;
    public final long expansionHubReadNs;
    public final long expansionHubWriteNs;
    public final long imuReadNs;
    public final long voltageReadNs;
    public final long servoWriteNs;

    public HubTiming(Cfg timingMs) {
        controlHubReadNs = ms(timingMs, "controlHubRead");
        controlHubWriteNs = ms(timingMs, "controlHubWrite");
        expansionHubReadNs = ms(timingMs, "expansionHubRead");
        expansionHubWriteNs = ms(timingMs, "expansionHubWrite");
        imuReadNs = ms(timingMs, "imuRead");
        voltageReadNs = ms(timingMs, "voltageRead");
        servoWriteNs = ms(timingMs, "servoWrite");
    }

    private static long ms(Cfg c, String key) {
        return Math.round(c.num(key) * 1_000_000.0);
    }
}
