package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import dimblend.experience.compat.cdg.CdgEngineDamage;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 损坏柴油机被破坏后的掉落改为 {@link CdgEngineDamage#DAMAGED_SCRAP_COUNT} 张黄铜板
 * （2026-11-27 口径：用扳手以外的任何方式破坏都只掉残骸，机器本体不进背包）。
 *
 * <p><b>为什么挂在 {@code getDrops} 而不是 {@code playerDestroy}</b>：三机
 * （{@code DieselEngineBlock} / {@code ModularDieselEngineBlock} / {@code HugeDieselEngineBlock}）
 * 自身都不覆写 {@code getDrops} 与 {@code playerDestroy}（javap -p 已核），而原版所有掉落路径
 * 最终都走 {@code BlockBehaviour#getDrops(BlockState, LootParams.Builder)}
 * （{@code mcsrc-1.21.1} {@code BlockBehaviour.java:254}、爆炸 {@code :183}、
 * {@code Block#dropResources} 的静态重载），一处覆写就同时覆盖：
 * 玩家镐子/空手采样破坏、爆炸（TNT/苦力怕）、机械动力钻头、{@code level.destroyBlock(pos, true)}。
 * 旧口径（给掉落物打 {@code dimblend_piston_damaged} 标记让放回去还是坏的）已废弃——
 * 逐格 {@code playerDestroy} 覆写只覆盖"玩家挖"一条路，钻头/爆炸都漏。</p>
 *
 * <p><b>为什么不拆成三个各自 extends 直接超类的 mixin</b>：三机超类各不相同
 * （{@code DirectionalKineticBlock} / {@code HorizontalKineticBlock} / {@code Block}），
 * 单个 {@code @Mixin} 只能声明一个超类，只能取公共上界 {@code Block}。</p>
 *
 * <p><b>{@code super.getDrops} 是否成立</b>：本 mixin 的超类是 {@code Block}，而 {@code getDrops}
 * 声明在 {@code BlockBehaviour}，三机超类链里都含 {@code BlockBehaviour}，所以编译出来的
 * {@code invokespecial BlockBehaviour.getDrops} 在三个目标类里都是合法超类调用（JVMS 允许
 * owner 为任意超类，不限直接超类）。Mixin 侧：{@code MixinPreProcessorStandard} 用
 * {@code ClassInfo.SearchType.SUPER_CLASSES_ONLY} 在目标超类里认领这个覆写，
 * 类里没有"super 必须指向直接超类"的校验分支（sponge-mixin-0.15.2+mixin.0.8.7 javap -p -c 已核）。</p>
 */
@Mixin({DieselEngineBlock.class, ModularDieselEngineBlock.class, HugeDieselEngineBlock.class})
public abstract class CdgPistonDamageDropMixin extends Block {

    /** mixin 必须显式给一个能调到 {@code Block(Properties)} 的构造器（Mixin 不会把构造器 merge 进目标）。 */
    protected CdgPistonDamageDropMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * 与原版 {@code BlockBehaviour#getDrops} 同签名（保持 {@code protected}，与父类一致）。
     *
     * <p>损坏 → 残骸（每次新建的可变 {@link List}，见 {@link CdgEngineDamage#damagedScrapDrops()}）；
     * 未损坏 → {@code super.getDrops}，掉落与改动前完全一样。</p>
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (CdgEngineDamage.isDamagedForDrop(be)) {
            return CdgEngineDamage.damagedScrapDrops();
        }
        return super.getDrops(state, params);
    }
}
