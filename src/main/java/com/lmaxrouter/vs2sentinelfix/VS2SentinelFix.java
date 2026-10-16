package com.lmaxrouter.vs2sentinelfix;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VS2 哨兵坐标修复 — Forge mod 入口壳。
 *
 * 本 mod 的全部功能在 CompatUtilMixin(见 mixin 包)里,mixin 由 Mixin 系统
 * 在 mod 构造前应用;这个类只负责两件事:
 *   1. 给 Forge 一个 @Mod 入口(FML 需要,否则 mod jar 不被当作 mod 加载)
 *   2. 留一行启动日志,方便在 latest.log 里确认本 mod 确实加载了
 */
@Mod("vs2sentinelfix")
public class VS2SentinelFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("vs2sentinelfix");

    public VS2SentinelFix() {
        // mixin 生效与否看这行之后游戏是否还卡;守卫本身零日志(每 tick 触发,不打)
        LOGGER.info("VS2 Sentinel Fix loaded - CompatUtil.toSameSpaceAs guard active");
    }
}