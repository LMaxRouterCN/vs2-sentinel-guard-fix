# VS2 Sentinel Fix

修复 Valkyrien Skies 2(2.4.11)world_weather 哨兵坐标 bug:被世界地形"遮挡"的船方块返回哨兵 BlockPos(0, minY-1, 0),下游无条件套船矩阵变换,产出约 2400 万格外、随船逐 tick 漂移的幽灵生物群系查询点;同步推理型 BiomeSource(Terrain Diffusion)缓存永远 miss,每 tick 主线程推理 3.5-5.4 秒,TPS 跌至 0.18,无自然终点。与天气无关。完整案件卷宗:../vs-td-investigation/CASE-REPORT.md(v3)。

## 修复原理(一行守卫,漏斗口)

- 注入 org.valkyrienskies.mod.common.CompatUtil#toSameSpaceAs(Level, Vector3dc, Ship, Ship) —— VS2 三个 weather wrap(occlude / useBiomeAtWorldPos / usePrecipitationAtWorldPos)的公共变换漏斗
- HEAD cancellable 守卫:level != null && position.y() < level.getMinBuildHeight() → 跳过船变换,返回 (x, minY, z)(y 钳到世界地板)
- 不变式:真实方块坐标恒 y >= minBuildHeight;低于地板的只能是哨兵/无效坐标 → 零误伤;maxY 侧不设防(船高于世界建筑上限是 VS2 支持的合法场景)
- 效果:哨兵落点稳定为 (0, minY, 0),TD 缓存恒命中,TPS 恢复

## 部署 / 卸载

- 部署:把 build/libs/vs2-sentinel-fix-1.0.0.jar 放进 mods 目录(当前已部署一份到实例 mods)
- 卸载:删除该 jar 即可,无残留
- 版本窗口:valkyrienskies [2.4.11, 2.5) 强制依赖;窗口外 Forge 拒绝加载本 mod(required mixin 遇上游签名漂移会崩游戏,拒载更安全;上游 2.5 修复本 bug 后本 mod 光荣退役)

## 游戏内验证

1. latest.log 搜索 "VS2 Sentinel Fix loaded"(mod 构造器输出的一行)
2. 进受影响存档,走近船坞(进世界约 75-80 秒后船坞区块进入 ticking),观察 TPS(F3 或 spark):应稳定 20
3. 反证:移除本 jar 重测,TPS 回落至 0.18 一带

## 已知良性代价

哨兵钳到 (0, minY, 0):若该世界 chunk(0,0) 不在出生点常载范围,首次查询会同步加载一次 chunk(百毫秒级,一次性);此后 BiomeManager / TD 缓存命中。

## 构建(复现)

- JDK 17(E:\Java\Zulu\zulu-17),Windows
- 前置:libs/valkyrienskies-2.4.11.jar 必须存在 —— mixin 注解处理器编译期校验 @Mixin 目标类须在 classpath(来源:实例 mods 目录里的 VS2 2.4.11 jar;compileOnly,不进产物)。libs/ 已 gitignore,克隆后需手动放置
- 命令:.\gradlew.bat build(8.14.3 发行包已装入本机 dists 缓存;或直接用 tools\gradle-8.14.3\bin\gradle.bat)
- 网络:插件与依赖走 Gradle 插件门户 / maven.minecraftforge.net / Sponge / Maven Central。注意:本机 maven.neoforged.net 持续不可达,故 ModDevGradle 钉在 2.0.116(本机缓存有配套 neoform-runtime 1.0.43 + AutoRenamingTool 2.0.4 + MC 1.20.1 素材全套);升级 MDG 版本前先确认该主机可达或缓存命中
- 产物自检:javap -p -c -classpath build/libs/vs2-sentinel-fix-1.0.0.jar com.lmaxrouter.vs2sentinelfix.mixin.CompatUtilMixin —— handler 内 getMinBuildHeight 应显示为 SRG 名 m_141937_(reobfJar 重映射成功的标志)
- tools/(gradle 发行包)与 libs/ 均已 gitignore,不入库

## 案件关联

- 根因、证据链、备选修复方案:../vs-td-investigation/CASE-REPORT.md
- 本 mod 只做 VS2 侧根治;TD 侧防御刀(getNoiseBiome 主线程熔断)与上游 issue 起草为待决事项