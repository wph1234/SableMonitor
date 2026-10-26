package dev.wphuujmhu.sablemonitor.natives;
import java.util.List;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Java side of the profiling exports added to the patched {@code sable_rapier} native library.
 * <p>
 * See {@code native/rapier/PATCH.md} and {@code native/sable_rapier/PATCH.md} for the Rust side.
 * The native library is loaded by Sable itself (with our loader mixin substituting a patched build),
 * so there is no {@code System.load} here.
 */
public final class NativePhysicsProfile {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 7 stage timings, then the substep count, then the record count. */
    public static final int HEADER = 9;

    /** id, narrow_ns, solver_ns, pairs, island_max. */
    public static final int STRIDE = 5;

    private static volatile boolean available = true;
    private static boolean profileEnabled = false;

    private NativePhysicsProfile() {
    }

    /**
     * Enables or disables per-sub-level sampling inside the Rust physics step.
     *
     * @return {@code false} if the patched natives are not loaded.
     */
    public static boolean setEnabled(final boolean enabled) {
        if (!available) {
            return false;
        }

        try {
            setEnabled0(enabled);
            profileEnabled = enabled;
            return true;
        } catch (final UnsatisfiedLinkError e) {
            disable(e);
            profileEnabled = false;
            return false;
        }
    }

    /**
     * Reads the last <em>complete</em> game tick. Non-destructive: calling it twice returns the
     * same numbers, and the window is always exactly one tick (all substeps summed).
     *
     * @param sceneHandle the handle from {@code Rapier3D.getSceneHandle(level)}
     * @return {@code null} if the patched natives are not loaded
     */
    public static Snapshot take(final long sceneHandle) {
        if (!available) {
            return null;
        }

        final double[] raw;

        try {
            raw = takeProfile0(sceneHandle);
        } catch (final UnsatisfiedLinkError e) {
            disable(e);
            return null;
        }

        if (raw == null || raw.length < HEADER) {
            return null;
        }

        final List<Entry> entries = new ArrayList<>();

        final int count = (int) raw[8];

        for (int i = 0; i < count; i++) {
            final int base = HEADER + i * STRIDE;

            if (base + STRIDE > raw.length) {
                break;
            }

            entries.add(new Entry(
                    (int) raw[base],
                    raw[base + 1] / 1.0e6,
                    raw[base + 2] / 1.0e6,
                    (int) raw[base + 3],
                    (int) raw[base + 4]
            ));
        }

        entries.sort(Comparator.comparingDouble((Entry e) -> e.narrowMs + e.solverMs).reversed());

        return new Snapshot(
                raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], (int) raw[7], entries
        );
    }

    private static void disable(final UnsatisfiedLinkError e) {
        available = false;
        LOGGER.warn("patched rapier natives are not loaded; profiling disabled. " +
                "Did you forget to build/install the patched sable_rapier library?", e);
    }

    public static boolean getEnabled() {
        return profileEnabled;
    }

    // --- natives ------------------------------------------------------------------------------

    private static native void setEnabled0(boolean enabled);

    private static native double[] takeProfile0(long sceneHandle);

    // --- data ---------------------------------------------------------------------------------

    /** One sub-level's sample for the tick that was just read. */
    public record Entry(int id, double narrowMs, double solverMs, int pairs, int islandMax) {
        public double totalMs() {
            return this.narrowMs + this.solverMs;
        }
    }

    /**
     * One complete game tick.
     * <p>
     * The stage timings are millisecond sums over every substep of that tick, so they are directly
     * comparable to what the server spends in physics per tick.
     *
     * @param substeps how many {@code Rapier3D.step} calls the tick made (usually 1..3)
     */
    public record Snapshot(double stepMs,
                           double broadMs,
                           double narrowMs,
                           double islandMs,
                           double solverMs,
                           double ccdMs,
                           double updateMs,
                           int substeps,
                           List<Entry> entries) {
        /** Average cost of one physics substep for this tick. */
        public double stepMsPerSubstep() {
            return this.substeps <= 0 ? this.stepMs : this.stepMs / this.substeps;
        }
    }
}
