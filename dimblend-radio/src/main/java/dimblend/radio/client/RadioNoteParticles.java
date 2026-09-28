package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.SubLevelProjection;
import dimblend.radio.net.ClientRadioState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 电台播中音符粒子：radio 只在空盘唱片机上播（塞盘即让位原版），空盘 BE 永不 tick，
 * 原版 {@code JukeboxSongPlayer.spawnMusicParticles} 走不到，这里按客户端状态补齐。
 *
 * <p>语义对齐原版：粒子源是 {@link ClientRadioState} 的 playing 状态而非实际在播实例
 * （{@link RadioController} 的 LIVE）——原版音符由服务端发出，与客户端音量、缺文件、
 * 是否听得到无关。节拍锚定外推服务钟 {@code (now - startMillis) % 1000 < 50}，与原版
 * 20 tick 节奏一致、各客户端同相位、中途开播不立即喷一颗。颜色参数同原版
 * （{@code random.nextInt(4)/24.0}）；位置 = 唱片机中心世界坐标 + 0.7 格（即原版的
 * 底部中心 + 1.2），Sable 结构上的 pos 是 plot 坐标，经 {@link SubLevelProjection}
 * 投影（与 {@link RadioInstance} 声源同款），结构移动时逐 tick 跟随。距离剔除由
 * ParticleEngine 内部处理（超 32 格不渲染），无需自判。</p>
 *
 * <p>不发 {@code GameEvent.JUKEBOX_PLAY}：那是服务端玩法逻辑（惊扰监 sensor），不属于粒子效果。</p>
 *
 * <p>已知取舍：两曲之间服务端保持 playing=true 等 advance 广播（静默最长近 10s），
 * 期间音符照发，与原版「曲终音符即停」不同。客户端按曲库时长跳过 GAP 拍点可行，
 * 但会让粒子耦合曲库、背离「与是否听得到解耦」的设计，故保留照发。</p>
 */
@EventBusSubscriber(modid = DimBlendRadio.MODID, value = Dist.CLIENT)
public final class RadioNoteParticles {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 暂停时 gameTime 冻结但 ClientTickEvent 照发：冻结在拍点上会每帧叠一颗
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        String dim = mc.level.dimension().location().toString();
        for (var entry : ClientRadioState.view().entrySet()) {
            var key = entry.getKey();
            var state = entry.getValue();
            if (!state.playing() || !key.dimension().equals(dim)) {
                continue;
            }
            // 服务钟（RadioClock 毫秒）1s 一拍、拍窗 50ms（约一个客户端 tick），与原版 20 tick 节奏一致；
            // 外推钟落后 startMillis（刚进服/补推）时不喷
            long now = ClientRadioState.estimateServerNow(key, state);
            if (now < state.startMillis() || (now - state.startMillis()) % 1000L >= 50L) {
                continue;
            }
            // Sable 结构上的唱片机 pos 是 plot 坐标（原点两千万格外），必须投影到世界坐标
            // （与 RadioInstance 声源同款换算）；原版「底部中心 + 1.2」即方块中心 + 0.7
            Vec3 pos = SubLevelProjection.worldCenter(mc.level, key.pos()).add(0.0, 0.7, 0.0);
            float color = mc.level.getRandom().nextInt(4) / 24.0F;
            mc.level.addParticle(ParticleTypes.NOTE, pos.x(), pos.y(), pos.z(), color, 0.0, 0.0);
        }
    }

    private RadioNoteParticles() {
    }
}
