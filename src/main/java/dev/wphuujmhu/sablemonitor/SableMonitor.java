package dev.wphuujmhu.sablemonitor;

import com.mojang.logging.LogUtils;
import dev.wphuujmhu.sablemonitor.command.SableMonitorCommand;
import dev.wphuujmhu.sablemonitor.profiling.ProfilingTicker;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * SableMonitor - per-sub-level physics cost, measured inside Rapier's Rust step.
 * <p>
 * The numbers are produced by a patched {@code rapier3d} (see {@code native/rapier/PATCH.md}),
 * exported through a patched build of Sable's {@code sable_rapier} native library
 * (see {@code native/sable_rapier/PATCH.md}) and surfaced by {@code /sablemonitor}.
 */
@Mod(SableMonitor.MODID)
public class SableMonitor {
    public static final String MODID = "sablemonitor";

    private static final Logger LOGGER = LogUtils.getLogger();

    public SableMonitor(final IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(ProfilingTicker.class);

        LOGGER.info("SableMonitor loading (profiler requires the patched sable_rapier natives)");
    }

    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        SableMonitorCommand.register(event.getDispatcher());
    }
}
