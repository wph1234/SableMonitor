package dev.wphuujmhu.sablemonitor.profiling;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.wphuujmhu.sablemonitor.natives.NativePhysicsProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Headless profiling: turn the profiler on at server start and log the per-sub-level table
 * periodically, without needing a player to type a command.
 * <p>
 * Opt-in via system properties so a normal server pays nothing:
 * <ul>
 *   <li>{@code -Dsablemonitor.profile=true} - enable sampling at server start</li>
 *   <li>{@code -Dsablemonitor.profile.log=true} - log the table every 20 ticks</li>
 * </ul>
 */
public final class ProfilingTicker {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final boolean ENABLED = Boolean.getBoolean("sablemonitor.profile");
    private static final boolean LOG = Boolean.getBoolean("sablemonitor.profile.log");

    private static int ticks;

    private ProfilingTicker() {
    }

    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        if (!ENABLED) {
            return;
        }

        LOGGER.info("SableMonitor: profiling enabled by -Dsablemonitor.profile (log={})",
                LOG ? "on" : "off");

        if (!NativePhysicsProfile.setEnabled(true)) {
            LOGGER.warn("SableMonitor: profiling requested but the patched natives are not loaded");
        }
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        if (!ENABLED || !LOG) {
            return;
        }

        final MinecraftServer server = event.getServer();
        final ServerLevel level = server.overworld();

        final long handle;

        try {
            handle = Rapier3D.getSceneHandle(level);
        } catch (final Throwable t) {
            return;
        }

        final NativePhysicsProfile.Snapshot snapshot = NativePhysicsProfile.take(handle);

        if (snapshot == null || ++ticks % 20 != 0) {
            return;
        }

        final StringBuilder sb = createSb(snapshot);

        LOGGER.info("SableMonitor: {}", sb);
    }

    private static @NotNull StringBuilder createSb(NativePhysicsProfile.Snapshot snapshot) {
        // User-facing wording: narrow phase -> "collision", solver -> "physics".
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "probe t=%d step=%.3fms/tick substeps=%d broad=%.3f collision=%.3f islands=%.3f physics=%.3f",
                ticks, snapshot.stepMs(), snapshot.substeps(), snapshot.broadMs(),
                snapshot.narrowMs(), snapshot.islandMs(), snapshot.solverMs()));

        for (final NativePhysicsProfile.Entry entry : snapshot.entries()) {
            sb.append(String.format(" | id=%d collision~=%.3f physics~=%.3f pairs=%d island=%d",
                    entry.id(), entry.narrowMs(), entry.solverMs(), entry.pairs(), entry.islandMax()));
        }
        return sb;
    }
}
