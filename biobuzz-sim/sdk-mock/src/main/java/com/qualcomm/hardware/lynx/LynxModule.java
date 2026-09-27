package com.qualcomm.hardware.lynx;

import com.qualcomm.robotcore.hardware.HardwareDevice;

import org.biobuzz.simhooks.SimOnly;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit;

/**
 * A REV Control Hub or Expansion Hub.
 *
 * The most useful feature for us is BULK CACHING. Normally every encoder
 * read is a separate message to the hub (1-3 ms each). With bulk caching,
 * one message returns ALL of a hub's encoders at once:
 *
 *   for (LynxModule hub : hardwareMap.getAll(LynxModule.class)) {
 *       hub.setBulkCachingMode(LynxModule.BulkCachingMode.AUTO);
 *   }
 *
 *   OFF    - every read is its own message (slow, always fresh)
 *   AUTO   - reads come from the last bulk read; reading the SAME value twice
 *            triggers a new bulk read
 *   MANUAL - reads come from the cache until you call clearBulkCache()
 *            (call it once at the top of every loop)
 *
 * The simulator models the time each message takes, so you can see the
 * loop-time difference.
 */
public abstract class LynxModule implements HardwareDevice {

    public enum BulkCachingMode { OFF, MANUAL, AUTO }

    protected boolean isParent;
    protected BulkCachingMode bulkCachingMode = BulkCachingMode.OFF;

    @SimOnly
    protected LynxModule(boolean isParent) {
        this.isParent = isParent;
    }

    public int getRevProductNumber() {
        return isParent ? 0x311153 : 0x311152;
    }

    public int getModuleAddress() {
        return isParent ? 173 : 2;
    }

    public boolean isParent() {
        return isParent;
    }

    /** Sets the hub's LED color (0xRRGGBB). The simulator ignores it. */
    public void setConstant(int color) {
    }

    public BulkCachingMode getBulkCachingMode() {
        return bulkCachingMode;
    }

    public void setBulkCachingMode(BulkCachingMode mode) {
        this.bulkCachingMode = mode;
        clearBulkCache();
    }

    /** Throws away the cached bulk data, so the next read fetches fresh values. */
    public abstract void clearBulkCache();

    /** Total current through this hub (all its motors, servos and electronics). */
    public abstract double getCurrent(CurrentUnit unit);

    /** Battery voltage measured by this hub. */
    public abstract double getInputVoltage(VoltageUnit unit);
}
