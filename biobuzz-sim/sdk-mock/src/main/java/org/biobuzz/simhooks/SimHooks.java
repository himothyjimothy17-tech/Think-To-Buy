package org.biobuzz.simhooks;

/**
 * The bridge between the fake FTC SDK and the simulator engine.
 *
 * WHY THIS EXISTS: on a real robot, time passes on its own. In the simulator,
 * the engine owns a SIMULATED clock so every run with the same seed gives the
 * exact same result, and headless runs can go faster than real time.
 * Every SDK call that "takes time" on a real Control Hub (reading an encoder,
 * sleep(), idle()) calls {@link #charge(long)} so the simulated clock moves
 * forward by a realistic amount.
 *
 * If no engine is installed (for example in a plain unit test), we fall back
 * to the real computer clock so the SDK classes still behave sensibly.
 */
@SimOnly
public final class SimHooks {

    /** What the engine must provide. */
    public interface Backend {
        /** Current simulated time, in nanoseconds, as seen by the OpMode thread. */
        long nanoTime();

        /**
         * The OpMode thread is spending {@code nanos} of simulated time
         * (a hardware read, sleep(), a loop iteration...). The engine may pause
         * the OpMode thread here while physics catches up.
         */
        void charge(long nanos);
    }

    private static volatile Backend backend;

    private SimHooks() {
    }

    /** Called by the engine when a simulation starts. */
    public static void install(Backend b) {
        backend = b;
    }

    /** Called by the engine when a simulation is torn down. */
    public static void uninstall() {
        backend = null;
    }

    public static long nanoTime() {
        Backend b = backend;
        return b == null ? System.nanoTime() : b.nanoTime();
    }

    public static void charge(long nanos) {
        Backend b = backend;
        if (b != null) {
            b.charge(nanos);
        }
    }
}
