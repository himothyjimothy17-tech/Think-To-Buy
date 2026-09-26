package org.biobuzz.simhooks;

/**
 * Thrown inside the OpMode thread when the OpMode ignored a STOP request for
 * too long. The real SDK kills the robot app in this situation ("stuck in
 * stop()"); the simulator unwinds the OpMode thread instead.
 *
 * It extends Error (not Exception) so a normal {@code catch (Exception e)} in
 * TeamCode can't accidentally swallow it.
 */
@SimOnly
public class OpModeTerminatedError extends Error {
    public OpModeTerminatedError(String message) {
        super(message);
    }
}
