package dimblend.experience.gametest;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock.WhistleSize;
import com.simibubi.create.content.decoration.steamWhistle.WhistleExtenderBlock;
import dimblend.experience.compat.create.TrainHorn;
import dimblend.experience.compat.create.TrainHornStateAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 列车汽笛（Create 蒸汽引擎路径）运行时验证——模板复用 {@code item_drain_refill}
 * （3×3×3 全空平台，工作层 y=1）：储罐 (1,1,1)、汽笛 (1,2,1) 落地式朝北，
 * 红石块 (1,2,0)（汽笛正面相邻格）供信号，引擎放 (2,2,1)（汽笛右侧）。
 *
 * <p>锁定截止 tick 由 {@link TrainHornStateAccess} 读取；触发与放置同 tick 同步发生
 * （neighborChanged 在 setBlock 内同步调用），故 until == 放置时 gameTime +
 * {@link TrainHorn.HornKind#durationTicks()} 可精确断言。</p>
 *
 * <p>2026-10-26 音高改版新增：特殊模式进入重置（音高 1.0 + 尺寸 MEDIUM）、放置邻接的首次
 * tick 对账、非潜行扳手循环（刻度/尺寸/动作栏/静音）、离开与再进入、普通模式扳手保持
 * Create 默认（循环 SIZE）、NBT 落盘与客户端更新包同步、触发包音高 = 2^(v−1)。
 * 扳手点击用真实 {@link ServerPlayer} 走 {@code ServerPlayerGameMode.useItemOn}，
 * 与实机服务端路径一致（含「SUCCESS 后不再调物品 useOn」这层分派）。</p>
 *
 * <p>不加 {@code @GameTestHolder}：引用 Create 类，仅 Create 在场时由 DimBlend 显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class TrainHornGameTests {

    static final BlockPos TANK = new BlockPos(1, 1, 1);
    static final BlockPos WHISTLE = new BlockPos(1, 2, 1);
    static final BlockPos SIGNAL = new BlockPos(1, 2, 0);
    static final BlockPos ENGINE = new BlockPos(2, 2, 1);

    private TrainHornGameTests() {
    }

    /** 六面邻接蒸汽引擎时，无热源也给红石上升沿鸣笛（本需求核心口径）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void steamEngineNeighborTriggersHorn(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        long t0 = helper.getLevel().getGameTime();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            long until = hornUntil(helper);
            helper.assertTrue(until == t0 + TrainHorn.HornKind.STEAM.durationTicks(),
                    "steam engine neighbor must lock horn for steam duration, until=" + until
                            + " expected=" + (t0 + TrainHorn.HornKind.STEAM.durationTicks()));
            helper.assertTrue(hornKind(helper) == TrainHorn.HornKind.STEAM,
                    "steam engine neighbor must be recognized as STEAM, got " + hornKind(helper));
            helper.succeed();
        });
    }

    /** 无引擎邻接：红石上升沿不触发（保持 Create 原生行为）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void whistleWithoutEngineStaysSilent(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(hornUntil(helper) == 0L,
                    "no engine neighbor must not trigger horn, until=" + hornUntil(helper));
            helper.succeed();
        });
    }

    /** 播放期间断电立即停止并清锁；不等片段结束即可重新供能、从头播放。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void powerLossStopsAndRearmsImmediately(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(hornUntil(helper) > 0L, "precondition: horn must be locked after first edge");
            helper.setBlock(SIGNAL, Blocks.AIR);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(hornUntil(helper) == 0L,
                        "power loss must immediately clear horn lock, until=" + hornUntil(helper));
                long restartedAt = helper.getLevel().getGameTime();
                helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(hornUntil(helper) == restartedAt + TrainHorn.HornKind.STEAM.durationTicks(),
                            "fresh power must immediately restart the full horn duration, until="
                                    + hornUntil(helper) + " expected="
                                    + (restartedAt + TrainHorn.HornKind.STEAM.durationTicks()));
                    helper.succeed();
                });
            });
        });
    }

    /** 持续高电平只播放一次：素材自然结束后，普通邻接更新不得重触发。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void heldPowerDoesNotRetriggerAfterPlayback(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            long firstUntil = hornUntil(helper);
            helper.assertTrue(firstUntil > 0L, "precondition: horn must be locked after first edge");
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(helper.getLevel().getGameTime() >= firstUntil,
                        "precondition: steam clip must have ended");
                helper.setBlock(WHISTLE.west(), Blocks.STONE);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(hornUntil(helper) == firstUntil,
                            "held power must not retrigger on a neighbor update, firstUntil=" + firstUntil
                                    + " now=" + hornUntil(helper));
                    helper.succeed();
                });
            });
        });
    }

    /** 引擎新邻接 = 进入特殊模式：音高重置 v=1.0（刻度 10）、Create 尺寸重置 MEDIUM。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void engineNeighborEntersSpecialModeResetsPitchAndSize(GameTestHelper helper) {
        placeWhistleRig(helper, WhistleSize.LARGE);
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(!specialMode(helper), "precondition: no engine neighbor must not be special mode");
            helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
            helper.runAfterDelay(1, () -> {
                helper.assertTrue(specialMode(helper), "new engine neighbor must enter special mode");
                helper.assertTrue(pitchTenths(helper) == TrainHorn.PITCH_TENTHS_DEFAULT,
                        "entering special mode must reset the pitch to 1.0, tenths=" + pitchTenths(helper));
                helper.assertTrue(whistleSize(helper) == WhistleSize.MEDIUM,
                        "entering special mode must reset the whistle size to MEDIUM, size=" + whistleSize(helper));
                helper.succeed();
            });
        });
    }

    /** 汽笛后放置（贴着已有引擎）：自身邻居更新观察不到，靠首次 tick 对账补上。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void whistlePlacedNextToEngineEntersOnFirstTick(GameTestHelper helper) {
        helper.setBlock(TANK, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(WHISTLE, whistleState(WhistleSize.LARGE));
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(specialMode(helper), "a whistle placed next to an engine must enter special mode");
            helper.assertTrue(pitchTenths(helper) == TrainHorn.PITCH_TENTHS_DEFAULT,
                    "placement must reset the pitch to 1.0, tenths=" + pitchTenths(helper));
            helper.assertTrue(whistleSize(helper) == WhistleSize.MEDIUM,
                    "placement must reset the whistle size to MEDIUM, size=" + whistleSize(helper));
            helper.succeed();
        });
    }

    /** 非潜行扳手：每次 +0.1、尺寸随档位、2.0 后回 0.0，且全程不发声（不锁鸣笛）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void wrenchClickCyclesPitchAndSize(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(pitchTenths(helper) == TrainHorn.PITCH_TENTHS_DEFAULT,
                    "precondition: entering special mode resets the pitch, tenths=" + pitchTenths(helper));
            List<Packet<?>> packets = wrenchClick(helper, false);
            helper.assertTrue(pitchTenths(helper) == 11,
                    "one wrench click must add one tenth, tenths=" + pitchTenths(helper));
            helper.assertTrue(whistleSize(helper) == WhistleSize.MEDIUM,
                    "0.7-1.3 must keep the whistle MEDIUM, size=" + whistleSize(helper));
            helper.assertTrue(hasPitchActionBar(packets, "1.1"),
                    "the click must show the new pitch (1.1) on the action bar");
            helper.assertTrue(hornUntil(helper) == 0L, "wrenching must not play the horn");
            wrenchClick(helper, false);
            wrenchClick(helper, false);
            wrenchClick(helper, false);
            helper.assertTrue(pitchTenths(helper) == 14,
                    "four clicks must reach 1.4, tenths=" + pitchTenths(helper));
            helper.assertTrue(whistleSize(helper) == WhistleSize.LARGE,
                    "1.4 and above must be LARGE, size=" + whistleSize(helper));
            setPitchTenths(helper, TrainHorn.PITCH_TENTHS_MAX);
            wrenchClick(helper, false);
            helper.assertTrue(pitchTenths(helper) == TrainHorn.PITCH_TENTHS_MIN,
                    "2.0 must wrap to 0.0, tenths=" + pitchTenths(helper));
            helper.assertTrue(whistleSize(helper) == WhistleSize.SMALL,
                    "0.0-0.6 must be SMALL, size=" + whistleSize(helper));
            helper.assertTrue(hornUntil(helper) == 0L, "the wrap must not play the horn either");
            helper.succeed();
        });
    }

    /** 离开特殊模式保留刻度但不保留特殊语义；再进入再次重置 1.0 与 MEDIUM。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 60)
    public static void leavingAndReEnteringResetsPitch(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(1, () -> {
            wrenchClick(helper, false);
            wrenchClick(helper, false);
            helper.assertTrue(pitchTenths(helper) == 12, "precondition: two clicks reach 1.2, tenths=" + pitchTenths(helper));
            helper.setBlock(ENGINE, Blocks.AIR);
            helper.runAfterDelay(1, () -> {
                helper.assertTrue(!specialMode(helper), "removing the engine must leave special mode");
                helper.assertTrue(pitchTenths(helper) == 12,
                        "leaving must not rewrite the stored pitch, tenths=" + pitchTenths(helper));
                helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
                helper.runAfterDelay(1, () -> {
                    helper.assertTrue(specialMode(helper), "a new engine neighbor must re-enter special mode");
                    helper.assertTrue(pitchTenths(helper) == TrainHorn.PITCH_TENTHS_DEFAULT,
                            "re-entering must reset the pitch to 1.0, tenths=" + pitchTenths(helper));
                    helper.assertTrue(whistleSize(helper) == WhistleSize.MEDIUM,
                            "re-entering must reset the size to MEDIUM, size=" + whistleSize(helper));
                    helper.succeed();
                });
            });
        });
    }

    /** 尺寸档位同步上方延长管：只改汽笛会与延长管模型大小错位。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void sizeBandKeepsWhistleExtendersInSync(GameTestHelper helper) {
        placeWhistleRig(helper, WhistleSize.LARGE);
        helper.setBlock(WHISTLE.above(), AllBlocks.STEAM_WHISTLE_EXTENSION.getDefaultState()
                .setValue(WhistleExtenderBlock.SIZE, WhistleSize.LARGE));
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(whistleSize(helper) == WhistleSize.MEDIUM,
                    "entering special mode must reset the whistle to MEDIUM, size=" + whistleSize(helper));
            helper.assertTrue(extenderSize(helper) == WhistleSize.MEDIUM,
                    "entering special mode must mirror MEDIUM onto the whistle extender, size=" + extenderSize(helper));
            setPitchTenths(helper, TrainHorn.PITCH_TENTHS_MAX);
            wrenchClick(helper, false);
            helper.assertTrue(pitchTenths(helper) == TrainHorn.PITCH_TENTHS_MIN,
                    "precondition: the wrench must wrap 2.0 to 0.0, tenths=" + pitchTenths(helper));
            helper.assertTrue(whistleSize(helper) == WhistleSize.SMALL,
                    "0.0-0.6 must be SMALL, size=" + whistleSize(helper));
            helper.assertTrue(extenderSize(helper) == WhistleSize.SMALL,
                    "the wrap to 0.0 must mirror SMALL onto the whistle extender, size=" + extenderSize(helper));
            helper.succeed();
        });
    }

    /** 特殊模式之外：扳手保持 Create 默认（循环 SIZE），音高不动、不进入特殊模式。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void wrenchOutsideSpecialModeKeepsCreateDefault(GameTestHelper helper) {
        placeWhistleRig(helper, WhistleSize.MEDIUM);
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(!specialMode(helper), "precondition: no engine neighbor");
            wrenchClick(helper, false);
            helper.assertTrue(pitchTenths(helper) == TrainHorn.PITCH_TENTHS_DEFAULT,
                    "outside special mode the wrench must not touch the pitch, tenths=" + pitchTenths(helper));
            helper.assertTrue(whistleSize(helper) == WhistleSize.LARGE,
                    "Create's default wrench action must cycle the whistle size MEDIUM to LARGE, size="
                            + whistleSize(helper));
            helper.succeed();
        });
    }

    /** 刻度 + 标志随 BE NBT 落盘（save/load 往返）并按客户端更新包同步。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void pitchSurvivesSaveLoadRoundTrip(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(1, () -> {
            for (int i = 0; i < 5; i++) {
                wrenchClick(helper, false);
            }
            helper.assertTrue(pitchTenths(helper) == 15, "precondition: five clicks reach 1.5, tenths=" + pitchTenths(helper));
            TrainHornStateAccess access = access(helper);
            BlockEntity be = helper.getBlockEntity(WHISTLE);
            var registries = helper.getLevel().registryAccess();
            CompoundTag disk = be.saveWithFullMetadata(registries);
            CompoundTag sync = be.getUpdateTag(registries);
            helper.assertTrue(disk.getInt(TrainHorn.TAG_PITCH_TENTHS) == 15,
                    "saved NBT must carry the pitch, got " + disk.getInt(TrainHorn.TAG_PITCH_TENTHS));
            helper.assertTrue(disk.getBoolean(TrainHorn.TAG_SPECIAL_MODE),
                    "saved NBT must carry the special mode flag");
            helper.assertTrue(sync.getInt(TrainHorn.TAG_PITCH_TENTHS) == 15,
                    "the client update tag must carry the pitch, got " + sync.getInt(TrainHorn.TAG_PITCH_TENTHS));
            helper.assertTrue(sync.getBoolean(TrainHorn.TAG_SPECIAL_MODE),
                    "the client update tag must carry the special mode flag");
            access.dimblend$setPitchTenths(3);
            access.dimblend$setSpecialMode(false);
            be.loadWithComponents(disk, registries);
            helper.assertTrue(access.dimblend$pitchTenths() == 15,
                    "the stored pitch must survive a reload, tenths=" + access.dimblend$pitchTenths());
            helper.assertTrue(access.dimblend$specialMode(), "the stored special mode flag must survive a reload");
            helper.succeed();
        });
    }

    /** 触发包音高 = 2^(v−1)：v=0.0 → 0.5，v=2.0 → 2.0（服务端权威，随包下发）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void triggerPayloadPitchFollowsPitchValue(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(1, () -> {
            setPitchTenths(helper, TrainHorn.PITCH_TENTHS_MIN);
            helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(hornUntil(helper) > 0L, "precondition: the rising edge must start the horn");
                helper.assertTrue(Math.abs(hornPitch(helper) - 0.5F) < 1.0E-6F,
                        "v=0.0 must play one octave lower (0.5), got " + hornPitch(helper));
                helper.assertTrue(hornPitch(helper) == TrainHorn.heardPitch(TrainHorn.PITCH_TENTHS_MIN),
                        "the payload pitch must equal 2^(v-1)");
                helper.setBlock(SIGNAL, Blocks.AIR);
                helper.runAfterDelay(2, () -> {
                    setPitchTenths(helper, TrainHorn.PITCH_TENTHS_MAX);
                    helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
                    helper.runAfterDelay(2, () -> {
                        helper.assertTrue(hornUntil(helper) > 0L, "precondition: the second edge must restart the horn");
                        helper.assertTrue(Math.abs(hornPitch(helper) - 2.0F) < 1.0E-6F,
                                "v=2.0 must play one octave higher (2.0), got " + hornPitch(helper));
                        helper.assertTrue(hornPitch(helper) == TrainHorn.heardPitch(TrainHorn.PITCH_TENTHS_MAX),
                                "the payload pitch must equal 2^(v-1)");
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** 复用给 CDG/CCA 的同类测试：储罐 + 朝北落地汽笛。 */
    static void placeWhistleRig(GameTestHelper helper) {
        placeWhistleRig(helper, null);
    }

    static void placeWhistleRig(GameTestHelper helper, @Nullable WhistleSize size) {
        helper.setBlock(TANK, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.setBlock(WHISTLE, whistleState(size));
    }

    private static BlockState whistleState(@Nullable WhistleSize size) {
        BlockState state = AllBlocks.STEAM_WHISTLE.get().defaultBlockState()
                .setValue(WhistleBlock.FACING, Direction.NORTH);
        return size == null ? state : state.setValue(WhistleBlock.SIZE, size);
    }

    static long hornUntil(GameTestHelper helper) {
        return access(helper).dimblend$hornUntilTick();
    }

    /** 最近一次锁定的音效种类（null = 从未触发；有效性由 {@link #hornUntil} 判定）。 */
    static TrainHorn.@Nullable HornKind hornKind(GameTestHelper helper) {
        int ordinal = access(helper).dimblend$hornKind();
        return ordinal < 0 ? null : TrainHorn.HornKind.values()[ordinal];
    }

    /** 最近一次触发下发的音高（{@link TrainHornPayload} 的 pitch 字段同源）。 */
    static float hornPitch(GameTestHelper helper) {
        return access(helper).dimblend$hornPitch();
    }

    static int pitchTenths(GameTestHelper helper) {
        return access(helper).dimblend$pitchTenths();
    }

    static boolean specialMode(GameTestHelper helper) {
        return access(helper).dimblend$specialMode();
    }

    static WhistleSize whistleSize(GameTestHelper helper) {
        return helper.getBlockState(WHISTLE).getValue(WhistleBlock.SIZE);
    }

    static WhistleSize extenderSize(GameTestHelper helper) {
        return helper.getBlockState(WHISTLE.above()).getValue(WhistleExtenderBlock.SIZE);
    }

    static void setPitchTenths(GameTestHelper helper, int tenths) {
        access(helper).dimblend$setPitchTenths(tenths);
    }

    static TrainHornStateAccess access(GameTestHelper helper) {
        if (helper.getBlockEntity(WHISTLE) instanceof TrainHornStateAccess access) {
            return access;
        }
        helper.fail("precondition: whistle BE must implement TrainHornStateAccess");
        throw new IllegalStateException("missing TrainHornStateAccess");
    }

    /**
     * 用真实服务端玩家（{@code ServerPlayerGameMode.useItemOn}）对汽笛右键一次，
     * 返回本次调用经 {@code player.connection.send} 发出的包（动作栏反馈断言用）。
     * 与服务端实机路径一致：方块 useItemOn → 物品 useOn 的分派由原版逻辑决定。
     */
    static List<Packet<?>> wrenchClick(GameTestHelper helper, boolean sneaking) {
        ServerLevel level = helper.getLevel();
        List<Packet<?>> packets = new ArrayList<>();
        // 动作栏反馈在服务端是 ClientboundSystemChatPacket(overlay=true)，由 ServerPlayer
        // 经 listener#send(..., listener) → Connection#send(packet, listener, flush) 下发；
        // 未连接的原版 Connection 只会排队，因此在 Connection 层截获实际下发的包。
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override
            public void send(Packet<?> packet) {
                packets.add(packet);
            }

            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
                packets.add(packet);
            }

            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
                packets.add(packet);
            }
        };
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "train-horn-wrench"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setShiftKeyDown(sneaking);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        BlockPos absolute = helper.absolutePos(WHISTLE);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(absolute.getCenter(), Direction.UP, absolute, false));
        return packets;
    }

    /** 动作栏里出现「列车汽笛 音高=<expected>」（结构断言：文案键 + 唯一参数，不依赖语言加载）。 */
    private static boolean hasPitchActionBar(List<Packet<?>> packets, String expected) {
        for (Packet<?> packet : packets) {
            if (packet instanceof ClientboundSystemChatPacket chat && chat.overlay()
                    && chat.content().getContents() instanceof TranslatableContents contents
                    && TrainHorn.HUD_PITCH_SET.equals(contents.getKey())
                    && contents.getArgs().length == 1
                    && expected.equals(contents.getArgs()[0])) {
                return true;
            }
        }
        return false;
    }
}