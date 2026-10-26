package dev.wphuujmhu.sablemonitor.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.api.command.SableCommandHelper;
import dev.ryanhcode.sable.api.command.SubLevelArgumentType;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.wphuujmhu.sablemonitor.natives.NativePhysicsProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.*;

/**
 * {@code /sablemonitor ...} - reads the per-sub-level timings produced inside the physics step.
 * <p>
 * The numbers come from the patched {@code rapier3d} (narrow-phase time per collider pair, solver
 * time per island, split across the island's sublevels) and are exposed through the patched
 * {@code sable_rapier} native library. See {@code native/} for both patches.
 */
public final class SableMonitorCommand {
    /**
     * How many sublevels a single listing shows.
     * List is capped like {@code dump}.
     */
    private static final int TOP_N = 10;

    private SableMonitorCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("sablemonitor")
                .requires(commandSourceStack -> commandSourceStack.hasPermission(2));

        root.then(Commands.literal("profile")
                        .then(Commands.literal("start").executes(ctx -> setEnabled(ctx.getSource(), true)))
                        .then(Commands.literal("stop").executes(ctx -> setEnabled(ctx.getSource(), false))));
        root.then(Commands.literal("dump").executes(ctx -> dump(ctx.getSource())));
        root.then(Commands.literal("sublevels")
                        .then(Commands.argument("sub_level", SubLevelArgumentType.subLevels())
                                .executes(ctx -> single(ctx.getSource(), SubLevelArgumentType.getSubLevels(ctx, "sub_level")))));

        dispatcher.register(root);
    }

    private static int setEnabled(final CommandSourceStack source, final boolean enabled) {
        if (!NativePhysicsProfile.setEnabled(enabled)) {
            return fail(source, "Patched rapier natives are not loaded - see the log. " +
                    "Build and install the patched sable_rapier library first.");
        }

        return ok(source, "Sub-levels physics profiling " + (enabled ? "started" : "stopped") + ".");
    }

    private static int dump(final CommandSourceStack source) throws CommandSyntaxException {
        if (!NativePhysicsProfile.getEnabled()) {
            return fail(source, "Profiling is stopped. Run /sablemonitor profile start first.");
        }

        final NativePhysicsProfile.Snapshot snapshot = snapshot(source);

        if (snapshot == null) {
            return 0;
        }

        // Resolve the sublevel lookup *before* printing anything. Sable's helper throws when the
        // level has no sublevel container, and printing first would leave a half-finished table
        // in the chat followed by the error.
        final ServerSubLevelContainer container = SableCommandHelper.requireSubLevelContainer(source);
        final Map<Integer, UUID> UUIDsByRuntimeId = new HashMap<>();

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            UUIDsByRuntimeId.put(subLevel.getRuntimeId(), subLevel.getUniqueId());
        }

        source.sendSuccess(() -> Component.literal("-".repeat(50)).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.translatable("commands.sablemonitor.dump.general1",
                        Component.literal(String.format(Locale.ROOT, "%.3f", snapshot.stepMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                        Component.literal(String.format(Locale.ROOT, "%d", snapshot.substeps())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                        Component.literal(String.format(Locale.ROOT, "%.3f", snapshot.stepMsPerSubstep())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.translatable("commands.sablemonitor.dump.general2",
                        Component.literal(String.format(Locale.ROOT, "%.3f", snapshot.broadMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                        Component.literal(String.format(Locale.ROOT, "%.3f", snapshot.narrowMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                        Component.literal(String.format(Locale.ROOT, "%.3f", snapshot.islandMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                        Component.literal(String.format(Locale.ROOT, "%.3f", snapshot.solverMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                        Component.literal(String.format(Locale.ROOT, "%.3f", snapshot.ccdMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);

        final List<NativePhysicsProfile.Entry> entries = snapshot.entries();
        int printed = 0;

        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No sub-level samples in this tick.").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
        } else {
            /*
             The per-sub-level columns are thread-time sums: every contact pair is timed on
             whichever rayon worker ran it, and one game tick runs several substeps. They rank
             sublevels against each other; they are not wall-clock and do not add up to the
             line above.
             */
            final MutableComponent hoverText = Component.literal("*summed over substeps x worker threads").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY));
            final MutableComponent asteriskComponent = Component.literal("*").setStyle(Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));
            source.sendSuccess(() -> Component.translatable("commands.sablemonitor.dump.label", asteriskComponent, asteriskComponent, asteriskComponent).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)), false);

            for (final NativePhysicsProfile.Entry entry : entries) {
                if (printed >= TOP_N) {
                    break;
                }

                final UUID subLevelId = UUIDsByRuntimeId.get(entry.id());

                // The sublevel was removed between the tick that produced this sample and now.
                if (subLevelId == null) {
                    continue;
                }

                final SubLevel subLevel = container.getSubLevel(subLevelId);

                if (subLevel == null) {
                    continue;
                }

                final Vector3d pos = subLevel.logicalPose().position();
                final MutableComponent subLevelIdComponent = Component.literal(subLevelId.toString());
                processAndSendSubLevelIdComponent(source, pos, subLevelIdComponent, subLevel.getLevel());
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%.3fms %9.3fms %7.3fms %4d %7d",
                        entry.narrowMs(), entry.solverMs(), entry.totalMs(),
                        entry.pairs(), entry.islandMax())), false);
                printed++;
            }

            final int hidden = entries.size() - printed;

            if (hidden > 0) {
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "... %d more", hidden)).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
            }
        }
        return printed;
    }

    private static int single(final CommandSourceStack source, Collection<ServerSubLevel> subLevels) {
        if (!NativePhysicsProfile.getEnabled()) {
            return fail(source, "Profiling is stopped. Run /sablemonitor profile start first.");
        }

        if (subLevels.isEmpty()) {
            return fail(source, "No sub-levels found.");
        }

        final NativePhysicsProfile.Snapshot snapshot = snapshot(source);
        if (snapshot == null) {
            return 0;
        }

        // Keyed by the id the native side reports. A selector can resolve to the same sublevel
        // more than once, so the map also de-duplicates the request.
        final Map<Integer, ServerSubLevel> subLevelsByRuntimeId = new LinkedHashMap<>();

        for (final ServerSubLevel subLevel : subLevels) {
            subLevelsByRuntimeId.put(subLevel.getRuntimeId(), subLevel);
        }

        // The snapshot is already ordered "most expensive first"; only keep what was asked for.
        final List<NativePhysicsProfile.Entry> entries = new ArrayList<>();

        for (final NativePhysicsProfile.Entry entry : snapshot.entries()) {
            if (subLevelsByRuntimeId.containsKey(entry.id())) {
                entries.add(entry);
            }
        }

        source.sendSuccess(() -> Component.literal("-".repeat(50)).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);

        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No samples for these sub-levels in this tick.").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
        }

        int printed = 0;

        for (final NativePhysicsProfile.Entry entry : entries) {
            if (printed >= TOP_N) {
                break;
            }

            final ServerSubLevel subLevel = subLevelsByRuntimeId.get(entry.id());

            if (subLevel == null) {
                continue;
            }

            final Vector3d pos = subLevel.logicalPose().position();
            final MutableComponent subLevelIdComponent = Component.literal(subLevel.getUniqueId().toString());
            processAndSendSubLevelIdComponent(source, pos, subLevelIdComponent, subLevel.getLevel());
            source.sendSuccess(() -> Component.translatable("commands.sablemonitor.sublevels.stats",
                            Component.literal(String.format(Locale.ROOT, "%.3f", entry.narrowMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                            Component.literal(String.format(Locale.ROOT, "%.3f", entry.solverMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                            Component.literal(String.format(Locale.ROOT, "%.3f", entry.totalMs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                            Component.literal(String.format(Locale.ROOT, "%d", entry.pairs())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)),
                            Component.literal(String.format(Locale.ROOT, "%d", entry.islandMax())).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
            printed++;
        }

        final int hidden = entries.size() - printed;

        if (hidden > 0) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "... %d more", hidden)).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
        }

        // Requested sublevels that produced no sample at all (idle, or not stepped this tick).
        // When nothing at all was sampled the message above already says so.
        final int withoutSamples = subLevelsByRuntimeId.size() - entries.size();

        if (!entries.isEmpty() && withoutSamples > 0) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "%d requested sub-level(s) had no samples in this tick", withoutSamples)).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), false);
        }

        return printed;
    }

    private static void processAndSendSubLevelIdComponent(CommandSourceStack source, Vector3d pos, MutableComponent subLevelIdComponent, Level level) {
        subLevelIdComponent.setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to insert a teleport command")))
                // Locale.ROOT: a default locale such as de_DE would format 1.50 as "1,50" and
                // produce a teleport command that cannot parse. Sable itself formats this the same way.
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, String.format(Locale.ROOT,
                        "/execute in %s run tp @s %.2f %.2f %.2f",
                        level.dimension().location(),
                        pos.x(), pos.y(), pos.z()))));

        source.sendSuccess(() -> subLevelIdComponent, false);
    }

    private static NativePhysicsProfile.Snapshot snapshot(final CommandSourceStack source) {
        final ServerLevel level = source.getLevel();

        final long handle;

        try {
            handle = Rapier3D.getSceneHandle(level);
        } catch (final RuntimeException e) {
            // Sable throws "ServerLevel does not use the Rapier physics pipeline" (and a missing
            // physics system) as IllegalStateException. Errors are left alone so a real problem
            // is not reported as "wrong dimension".
            fail(source, "This level does not use Sable's Rapier physics pipeline.");
            return null;
        }

        if (handle == 0L) {
            fail(source, "Sable's physics scene for this level is not initialised yet.");
            return null;
        }

        final NativePhysicsProfile.Snapshot snapshot = NativePhysicsProfile.take(handle);

        if (snapshot == null) {
            fail(source, "Patched rapier natives are not loaded - see the log.");
            return null;
        }

        return snapshot;
    }

    private static int ok(final CommandSourceStack source, final String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int fail(final CommandSourceStack source, final String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
