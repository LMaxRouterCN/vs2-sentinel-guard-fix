# VS2 Sentinel Guard Fix

English | [简体中文](#vs2-哨兵守卫修复)

Fixes a Valkyrien Skies 2 bug where a leaked sentinel coordinate `BlockPos(0, minY-1, 0)` is unconditionally ship-transformed into a ~24-million-block ghost biome query point that shifts every tick — and that query keeps being sent to the world generator, tick after tick.

The query blocks the main thread until it returns.

In vanilla, the world generator is noise-based: queries are cheap and instant, so the problem stays buried — easy to never take seriously.

But swap the generator for another kind, the query becomes expensive and slow, and the problem surfaces.

Terrain Diffusion is one such generator. VS2 sends TD the query; TD starts a fresh GPU inference (the query coordinate is absurd — it can never hit TD's cache, and it is absurd anew every tick, compounding the stall), then replies to VS2.

On my hardware, one such inference takes ~5 s — meaning the per-tick cost goes from 50 ms to 5000+ ms, a hundredfold TPS drop.

That "merely slow" outcome only holds if the generator is robust enough to correctly handle a coordinate tens of millions of blocks out; a less robust generator will freeze or crash instead.

## Symptom

Spark shows the main thread parked inside `getNoiseBiome`, with VS2 calling into the world generator.

Concretely: in a normal world, mine deep wanderlite ore (`vs_clockwork:wanderlite_deepslate_ore`) underground — the ore becomes a physics ship > ends up covered by blocks > the bug triggers.

Any physics entity created in a block-covered spot — or later covered by blocks — triggers the bug.

In theory, any hollow, overhanging, or buried ship can trigger it.

## The `BlockPos(0, minY-1, 0)` — where it comes from

Valkyrien Skies 2's weather system wraps every biome and precipitation query with an occlusion test: "is this block's view of the sky actually blocked by the world?" For ship blocks whose transformed position falls below the terrain (hollow / overhanging / buried ships; y ~ -134 in practice), the occlusion test concludes "this block cannot see the sky" and returns `BlockPos(0, minY-1, 0)` — `minY` being the world's minimum build height.

## Why Valkyrien wrote it

It is a textbook **sentinel value**: a magic coordinate whose only job is to mean "no real answer — caller, skip this query." The pattern itself is ordinary engineering practice; the intent was clearly for the caller to recognize the marker and not use it.

## Where it breaks

No code downstream ever checks for the sentinel. The very next step in VS2's own pipeline — the shared transform funnel `CompatUtil.toSameSpaceAs` — unconditionally applies the ship's rotation matrix to whatever position it is handed:

```text
out = c_world + R * (-c_ship)
```

For a real block coordinate this lands back on the actual block. For the sentinel `(0, minY-1, 0)` it produces a point roughly `|c_ship| ~ 24 M` blocks from the world origin that **moves every tick** as the ship rocks.

Every tick, `BiomeManager` is then asked to resolve a biome at that drifting garbage coordinate.

- Vanilla BiomeSources answer instantly from noise, so pure-vanilla setups never notice.
- Synchronous-inference BiomeSource mods (Terrain Diffusion: ~5 s of GPU inference per uncached region) park the main thread inside `getNoiseBiome` once per tick, forever. TPS drops to ~0.18. No natural end.

## The fix

One guard, injected at the shared transform funnel `CompatUtil.toSameSpaceAs` — the single choke point that all three VS2 weather wrappers flow through:

```java
if (level != null && position.y() < level.getMinBuildHeight()) return (x, minY, z); // clamp to world floor, skip the ship matrix
```

Zero false positives: every legitimate block coordinate satisfies `y >= minBuildHeight`; only the sentinel (and other invalid values) fall below it.

Sentinel queries now resolve to a fixed point `(0, minY, 0)` instead of a 24 M-block moving target — if the biome-source mod has a cache, it hits; the main thread never parks, TPS stays at 20.

## Drawbacks

This fix does not stop the query — and "what biome is a block-covered physics entity in" is not inherently meaningless. VS2 assumes it is; that is VS2's problem, and I have no intention of touching it.

All I do is relocate the query to `(0, minY, 0)`. Consequence: every block-covered physics entity now shares the biome at `(0, minY, 0)` — if the biome there snows, it snows on every block-covered physics entity.

The root cause still lives in VS2. That codebase genuinely earns the name "mountain of shit" — it needs a ground-up rework, but I (for now) have no intention of touching it.

## Version & maintenance

- Tested: Valkyrien Skies 2.4.11 on Minecraft 1.20.1, Forge 47.x.
- Other VS2 / MC versions: untested. The VS2 dependency range is intentionally left open — we cannot confirm which versions carry the sentinel leak. If the mixin target signature ever changes upstream, the mod fails loudly at apply time rather than silently misbehaving.
- This is a targeted bug fix, not a feature mod. No updates are planned beyond keeping the fix buildable; if VS2 upstream fixes the sentinel leak, this mod retires.

## Install / uninstall

Drop the jar in `mods/`. No config, no world data, no persistence. Remove the jar to uninstall — zero residue.

## Build

- JDK 17; Gradle 8.14.3 via wrapper (or `tools/gradle-8.14.3`).
- Prerequisite: `libs/valkyrienskies-2.4.11.jar` must exist (compileOnly; copy it from your instance's `mods/`). `libs/` is gitignored.
- `.\gradlew.bat clean build` produces `build/libs/vs2-sentinel-guard-fix-1.0.1+1.20.1-forge.jar`
- Self-check: `javap -p -c -classpath build/libs/vs2-sentinel-guard-fix-1.0.1+1.20.1-forge.jar com.lmaxrouter.vs2sentinelguardfix.mixin.CompatUtilMixin` — the handler's `getMinBuildHeight` should appear as SRG `m_141937_` (proof of reobfJar remap).

## License

MPL-2.0.

---

# VS2 哨兵守卫修复

[English](#vs2-sentinel-guard-fix) | 简体中文

修复 Valkyrien Skies 2 的一个 bug:泄漏的哨兵坐标 `BlockPos(0, minY-1, 0)` 被无条件执行飞船坐标变换,产生一个约 2400 万格之外、每 tick 改变位置的生物群系查询点,并持续向世界生成器发送查询请求。

这个查询请求一般情况下会卡住主线程,直到结果返回。

在原版中,原版的世界生成器基于噪声,查询是廉价的,很快的,所以这个问题不突出,也一般不会让人觉得要正视它。

但如果世界生成器被换成了另一种,查询变得昂贵且耗时,问题就会凸显出来。

Terrain Diffusion 就是其中一种,所以 VS2 向 TD 发送查询请求,TD 就开始调用 GPU 跑一次全新的推理(这个查询坐标极其离谱,不可能命中 TD 的缓存,而且每一次都离谱,加剧了卡顿),然后给 VS2 回复。

在我的硬件环境下,这次推理要花 5 秒,也就是说,每次 tick 都由 50ms 变成了 5000+ms,TPS 直接下降 100 倍。

"慢"只建立在这个世界生成器够稳定且强壮,能正确处理上千万格坐标的情况下,如果世界生成器不够强壮,就会表现为卡死和崩溃。

## 症状

Spark 显示主线程卡在 `getNoiseBiome` 内,由 VS2 调用世界生成器。

在正常世界中的地底挖掘深层漫游矿石`vs_clockwork:wanderlite_deepslate_ore`,矿石物理化>满足被覆盖条件>触发bug。

只要在被方块覆盖的地方创建物理实体,或者让物理实体被方块覆盖,都会触发bug。

理论上来说任何中空、悬挑或被掩埋的飞船都会触发。

## `BlockPos(0, minY-1, 0)` 从哪来

VS2 的天气系统给每个生物群系与降水查询都包了一层遮挡测试:"这个方块看天空的视线是否真的被世界挡住了?" 对于变换后位置低于地形(中空/悬挑/掩埋飞船;实践中 y ~ -134)的飞船方块,遮挡测试判定"此方块看不见天空",并返回 `BlockPos(0, minY-1, 0)`——`minY` 即世界的最低可建造高度。

## Valkyrien 为什么这么写

这是一个教科书式的**哨兵值**:一个魔法坐标,唯一的职责是表达"没有真实答案——调用方,跳过本次查询"。这个模式本身是普通的工程实践;意图显然是让调用方识别标记、不去使用它。

## 断在哪

下游没有任何代码检查这个哨兵。VS2 自己管线的下一步——共享变换漏斗 `CompatUtil.toSameSpaceAs`——对手上的任何位置无条件应用飞船旋转矩阵:

```text
out = c_world + R * (-c_ship)
```

对真实方块坐标,结果落回实际方块;对哨兵 `(0, minY-1, 0)`,它产生一个距世界原点约 `|c_ship| ~ 2400 万`格的点,并**随飞船摇晃逐 tick 移动**。

于是每 tick,`BiomeManager` 都被要求在那个漂移的垃圾坐标上解析生物群系。

- 原版 BiomeSource 从噪声即时作答,纯原版环境毫无感知。
- 同步推理型 BiomeSource 模组(Terrain Diffusion:每个未缓存区域约 5 秒 GPU 推理)使主线程每 tick 永远停在 `getNoiseBiome` 里。TPS 跌至 ~0.18,没有自然终点。

## 修复方案

一道守卫,注入共享变换漏斗 `CompatUtil.toSameSpaceAs`——VS2 三个天气包装器共同流经的唯一咽喉:

```java
if (level != null && position.y() < level.getMinBuildHeight()) return (x, minY, z); // 钳到世界地板,跳过飞船矩阵
```

零误报:每个合法方块坐标都满足 `y >= minBuildHeight`,只有哨兵(及其他非法值)低于它。

哨兵查询现在解析到一个固定的点 `(0, minY, 0)`,而非 2400 万格的移动靶——如果那个世界生成器模组有缓存机制,就会命中,主线程不再停留,TPS 稳在 20。

## 缺点

它没有停止查询,而且查询"被方块覆盖的物理实体的所处群系"也并不是毫无意义的,VS2 假定这是无意义的,这属于 VS2 的问题,我也不想碰这玩意。

我只是把查询转移到了 (0, minY, 0),也就是说,现在所有被方块覆盖的物理实体,群系都是 (0, minY, 0) 位置的群系,如果这个位置的群系会下雪,所有被方块覆盖的物理实体上都会下雪。

问题根源还在 VS2。

屎山名副其实,这套玩意就是很垃圾,需要大干一场,但是我(暂时)不想碰。

## 版本与维护

- 测试环境:Valkyrien Skies 2.4.11,Minecraft 1.20.1,Forge 47.x。
- 其他 VS2 / MC 版本:未测试。VS2 依赖范围有意保持开放——我们无法确认哪些版本携带此哨兵泄漏。若上游 mixin 目标签名变化,本模组在 apply 时会大声的失败,而非静默出错。
- 这是针对性 bug 修复,不是功能模组。除保持可构建之外没有更新计划;若 VS2 上游修复了哨兵泄漏,本模组即退役。

## 安装 / 卸载

把 jar 丢进 `mods/`。无配置、无世界数据、无持久化;删除 jar 即卸载,无残留。

## 构建

- JDK 17;Gradle 8.14.3,经 wrapper(或 `tools/gradle-8.14.3`)。
- 前置:`libs/valkyrienskies-2.4.11.jar` 必须存在(compileOnly;从你实例的 `mods/` 复制)。`libs/` 已被 gitignore。
- `.\gradlew.bat clean build` 产出 `build/libs/vs2-sentinel-guard-fix-1.0.1+1.20.1-forge.jar`
- 自检:`javap -p -c -classpath build/libs/vs2-sentinel-guard-fix-1.0.1+1.20.1-forge.jar com.lmaxrouter.vs2sentinelguardfix.mixin.CompatUtilMixin`——handler 中的 `getMinBuildHeight` 应显示为 SRG 名 `m_141937_`(reobfJar 重映射的证据)。

## 许可证

MPL-2.0.