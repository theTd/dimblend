package dimblend.experience.compat.sable;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisBlueprint;
import dimblend.experience.chassis.ChassisEvents;
import dimblend.experience.chassis.ChassisRegistry;
import dimblend.experience.chassis.ChassisRegistryData;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /dbx chassis ...} 的命令面：世界锚探针 + 标准车架身份登记。
 *
 * <p>探针只把字符串交给 {@link ChassisWorldAnchorProbe}，本类不 import 任何 Sable / Simurail
 * 类型，这样未装 Sable 的实例也能安全加载命令注册类；真正的探针类只在
 * {@link #sableAndSimurail()} 通过后才触达。登记命令只写
 * {@link ChassisRegistryData} 的纯数据；已登记的 rotating 标准车架由
 * {@link ChassisRailLock} 在稳定检查后自动建生产轨锁（Y/Z + 全部旋转锁定，仅 X 平移），
 * 登记/校准/注销会触发它的请求或释放，不改质量/驱动/应力/制动。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class ChassisProbeCommand {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dbx")
                .then(Commands.literal("chassis")
                        .then(Commands.literal("probe")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("start")
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .executes(context -> start(context, 200, "read"))
                                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                                        .executes(context -> start(context,
                                                                IntegerArgumentType.getInteger(context, "seconds") * 20, "read"))
                                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                                .executes(context -> start(context,
                                                                        IntegerArgumentType.getInteger(context, "seconds") * 20,
                                                                        StringArgumentType.getString(context, "mode")))))))
                                .then(Commands.literal("stop").executes(ChassisProbeCommand::stop))
                                .then(Commands.literal("status").executes(ChassisProbeCommand::status))
                                .then(Commands.literal("dump").executes(ChassisProbeCommand::dump)))
                        .then(Commands.literal("register")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .then(Commands.argument("blueprint", StringArgumentType.word())
                                                .then(Commands.argument("nominalHeight",
                                                                DoubleArgumentType.doubleArg(
                                                                        ChassisRegistry.MIN_NOMINAL_HEIGHT,
                                                                        ChassisRegistry.MAX_NOMINAL_HEIGHT))
                                                        .executes(ChassisProbeCommand::registerChassis)))))
                        .then(Commands.literal("unregister")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .executes(ChassisProbeCommand::unregisterChassis)))
                        .then(Commands.literal("calibrate")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .then(Commands.argument("nominalHeight",
                                                        DoubleArgumentType.doubleArg(
                                                                ChassisRegistry.MIN_NOMINAL_HEIGHT,
                                                                ChassisRegistry.MAX_NOMINAL_HEIGHT))
                                                .executes(ChassisProbeCommand::calibrateChassis))))
                        .then(Commands.literal("registry")
                                .requires(source -> source.hasPermission(2))
                                .executes(ChassisProbeCommand::listRegistry))));
    }

    private static int start(CommandContext<CommandSourceStack> context, int ticks, String mode) {
        CommandSourceStack source = context.getSource();
        if (!sableAndSimurail()) {
            source.sendFailure(Component.literal("底盘世界锚探针需要 Sable 与 Simurail。"));
            return 0;
        }
        return ChassisWorldAnchorProbe.start(source,
                StringArgumentType.getString(context, "target"), mode, ticks) ? 1 : 0;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!sableAndSimurail()) {
            source.sendFailure(Component.literal("底盘世界锚探针需要 Sable 与 Simurail。"));
            return 0;
        }
        ChassisWorldAnchorProbe.stop("command");
        source.sendSuccess(() -> Component.literal(ChassisWorldAnchorProbe.status()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!sableAndSimurail()) {
            source.sendFailure(Component.literal("底盘世界锚探针需要 Sable 与 Simurail。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(ChassisWorldAnchorProbe.status()), false);
        return 1;
    }

    private static int dump(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!sableAndSimurail()) {
            source.sendFailure(Component.literal("底盘世界锚探针需要 Sable 与 Simurail。"));
            return 0;
        }
        for (String line : ChassisWorldAnchorProbe.dump()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int registerChassis(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!ChassisEvents.inDimension(level)) {
            source.sendFailure(Component.literal("只在 dimblend:rotating 登记标准车架。"));
            return 0;
        }
        String rawUuid = StringArgumentType.getString(context, "uuid");
        final UUID id;
        try {
            id = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal("UUID 无效：" + rawUuid));
            return 0;
        }
        String rawBlueprint = StringArgumentType.getString(context, "blueprint");
        final ChassisBlueprint blueprint;
        try {
            blueprint = ChassisBlueprint.valueOf(rawBlueprint.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal("蓝图必须是 POWER 或 CARRIAGE。"));
            return 0;
        }
        double nominalHeight = DoubleArgumentType.getDouble(context, "nominalHeight");
        ChassisRegistryData data = ChassisRegistryData.get(level);
        if (!data.registry().register(id, blueprint, nominalHeight)) {
            source.sendFailure(Component.literal("登记失败：标称高度无效。"));
            return 0;
        }
        data.setDirty();
        ChassisEvents.railLockRelease(level, id, "registered");
        ChassisEvents.railLockRequest(level, id);
        source.sendSuccess(() -> Component.literal("已登记 " + id + " blueprint=" + blueprint
                + " nominalHeight=" + nominalHeight
                + "（rotating 标准车架将在连续稳定检查后自动启用轨锁：Y/Z + 全部旋转锁定，仅 X 平移）"), false);
        return 1;
    }

    private static int unregisterChassis(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!ChassisEvents.inDimension(level)) {
            source.sendFailure(Component.literal("只在 dimblend:rotating 操作标准车架登记。"));
            return 0;
        }
        String rawUuid = StringArgumentType.getString(context, "uuid");
        UUID id;
        try {
            id = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal("UUID 无效：" + rawUuid));
            return 0;
        }
        ChassisRegistryData data = ChassisRegistryData.get(level);
        if (!data.registry().remove(id)) {
            source.sendFailure(Component.literal("没有登记：" + id));
            return 0;
        }
        data.setDirty();
        ChassisEvents.railLockRelease(level, id, "unregistered");
        source.sendSuccess(() -> Component.literal("已删除登记 " + id
                + "（已释放轨锁；仅真拆解使用；脱轨/卸载不应删身份）"), false);
        return 1;
    }

    private static int calibrateChassis(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!ChassisEvents.inDimension(level)) {
            source.sendFailure(Component.literal("只在 dimblend:rotating 校准标准车架。"));
            return 0;
        }
        String rawUuid = StringArgumentType.getString(context, "uuid");
        UUID id;
        try {
            id = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal("UUID 无效：" + rawUuid));
            return 0;
        }
        double nominalHeight = DoubleArgumentType.getDouble(context, "nominalHeight");
        ChassisRegistryData data = ChassisRegistryData.get(level);
        if (!data.registry().calibrate(id, nominalHeight)) {
            source.sendFailure(Component.literal("校准失败：UUID 未登记或标称高度无效。"));
            return 0;
        }
        data.setDirty();
        ChassisEvents.railLockRelease(level, id, "calibrated");
        source.sendSuccess(() -> Component.literal("已校准 " + id + " nominalHeight=" + nominalHeight
                + "（旧轨锁已释放；下一轮稳定检查会按新标称高度重建）"), false);
        return 1;
    }

    private static int listRegistry(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!ChassisEvents.inDimension(level)) {
            source.sendFailure(Component.literal("只在 dimblend:rotating 查看标准车架登记。"));
            return 0;
        }
        var entries = ChassisRegistryData.get(level).registry().entries();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("标准车架登记：空"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("标准车架登记 " + entries.size() + " 条："), false);
        int limit = Math.min(entries.size(), 20);
        for (int index = 0; index < limit; index++) {
            var entry = entries.get(index);
            source.sendSuccess(() -> Component.literal("- " + entry.id() + " " + entry.blueprint()
                    + " h=" + entry.nominalHeight() + " " + entry.lifecycle()
                    + " lock=" + ChassisEvents.railLockStatus(level, entry.id())), false);
        }
        if (entries.size() > limit) {
            source.sendSuccess(() -> Component.literal("（仅显示前 " + limit + " 条）"), false);
        }
        return 1;
    }

    private static boolean sableAndSimurail() {
        return ModList.get().isLoaded("sable") && ModList.get().isLoaded("simurail");
    }

    private ChassisProbeCommand() {
    }
}
