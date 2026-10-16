package com.lmaxrouter.vs2sentinelfix.mixin;

import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [核心修复] VS2 哨兵坐标幽灵查询 — 根因与全案见
 * vs-td-investigation/CASE-REPORT.md (v3)。
 *
 * 机制:
 *   VS2 world_weather 的 occlude wrap 判断"船方块被世界地形遮挡"时,返回哨兵
 *   BlockPos(0, minY-1, 0) 表示"无效位置,别用"。但下游 useBiomeAtWorldPos /
 *   usePrecipitationAtWorldPos 不检查哨兵,直接把它当真实船坐标送进
 *   CompatUtil.toSameSpaceAs 套 shipToWorld 变换,输出 = c_w + R*(-c_s):
 *   一个约 2400 万格外、随船姿态逐 tick 移动的垃圾点。同步推理型 BiomeSource
 *   (如 Terrain Diffusion)对它缓存永远 miss,每 tick 主线程推理数秒 -> TPS 0.18。
 *
 * 修复不变量(比两处各贴一刀更根本):
 *   toSameSpaceAs 是 VS2 所有"船坐标 <-> 世界坐标"变换的漏斗(三个 weather
 *   wrap 全部经过)。低于世界地板 minBuildHeight 的位置不可能是船的真实方块
 *   坐标 —— 真实查询来自高度图,其 y 永远 >= minBuildHeight;哨兵永远在地板之下。
 *   因此在漏斗口恢复不变量:低于地板的位置不做船变换,x/z 原样保留、y 钳到地板。
 *
 * 行为后果:
 *   哨兵落到稳定坐标 (0, minY, 0):不再被船矩阵放大、不再逐 tick 移动;
 *   下游 getBiome 查询稳定(最多触发一次 chunk(0,0) 同步加载),TD 缓存恒命中。
 *   对真实位置(永远在地板之上)零影响;maxBuildHeight 一侧不设防 —— 船高于
 *   世界建筑上限是 VS2 明确支持的合法场景(occlude 源码佐证)。
 *
 * 技术要点:
 *   - @Mixin targets 用字符串 + remap = false:目标是 VS2 的普通类(非 MC 类),
 *     其类名/方法名在生产环境不被 SRG 重映射(已对 2.4.11 jar 用 javap 核实),
 *     因此 method 描述符直接写原名,不进 refmap。
 *   - 只注入 4 参主重载:Kotlin 的 toSameSpaceAs$default 默认参数桥内部调用
 *     主重载,守卫对桥同样生效(漏斗全堵)。
 *   - handler 只捕获前缀参数 (Level, Vector3dc),两个 Ship 参数省略 —— Mixin
 *     规范允许参数前缀子集,由此编译期零依赖 vs-core(3041 个类已内嵌于 VS2 jar)。
 *   - level 可空是原 API 语义(Kotlin Level?),null 时无从判断地板,保持原行为。
 */
@Mixin(targets = "org.valkyrienskies.mod.common.CompatUtil", remap = false)
public abstract class CompatUtilMixin {

    /**
     * 守卫注入点:toSameSpaceAs(Level, Vector3dc, Ship, Ship) 的方法头。
     * 位置低于世界地板 -> 取消原方法,返回未变换(仅 y 钳到地板)的副本。
     */
    @Inject(
        method = "toSameSpaceAs(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/valkyrienskies/core/api/ships/Ship;Lorg/valkyrienskies/core/api/ships/Ship;)Lorg/joml/Vector3d;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vs2sf$guardBelowWorldFloor(Level level, Vector3dc position, CallbackInfoReturnable<Vector3d> cir) {
        // 原 API 允许 level 为 null(null 时 getShipManagingPos 路径不可用),
        // 此时无法定义"世界地板",保持原行为
        if (level == null) {
            return;
        }
        if (position.y() < level.getMinBuildHeight()) {
            // 哨兵(或任何越界位置):跳过船变换;x/z 原样保留,y 钳到地板 ——
            // y 钳制同时消除下游节索引越界风险(ChunkAccess 分节查询)
            cir.setReturnValue(new Vector3d(position.x(), level.getMinBuildHeight(), position.z()));
        }
        // 高于地板:放行,走原变换逻辑(真实船坐标,零影响)
    }
}