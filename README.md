# VS2 Sentinel Guard Fix

Fixes a Valkyrien Skies 2 bug where a leaked sentinel coordinate `BlockPos(0, minY-1, 0)` is
unconditionally ship-transformed into a ~24-million-block drifting ghost biome query point,
freezing any server that runs a synchronous-inference BiomeSource mod (e.g. Terrain Diffusion)
at TPS ~0.18.

## Symptom

- TPS collapses to ~0.2 roughly 75-80 seconds after world load, exactly when a dock /
  shipyard chunk starts ticking. It never resolves on its own — only logging out or walking
  far enough away (shipyard unload) stops it.
- Spark / stack dump shows the main thread parked inside `BiomeSource.getNoiseBiome`
  for 3.5-5.4 s per tick, on a coordinate ~24 M blocks away that moves slightly every tick.
- Dead ends that do **not** help: `/weather clear` (this is not a weather bug — verified
  against level.dat), removing rain mods, waiting it out.
- Affects any ship that is hollow, overhanging, or buried. Vanilla-only worlds never notice.

## The `BlockPos(0, minY-1, 0)` — where it comes from

Valkyrien Skies 2's weather system wraps every biome and precipitation query with an
occlusion test: "is this block's view of the sky actually blocked by the world?" For ship
blocks whose transformed position falls below the terrain (hollow / overhanging / buried
ships; y ~ -134 in practice), the occlusion test concludes "this block cannot see the sky"
and returns `BlockPos(0, minY-1, 0)` — `minY` being the world's minimum build height.

## Why Valkyrien wrote it

It is a textbook **sentinel value**: a magic coordinate whose only job is to mean
"no real answer — caller, skip this query." The pattern itself is ordinary engineering
practice; the intent was clearly for the caller to recognize the marker and not use it.

## Where it breaks

No code downstream ever checks for the sentinel. The very next step in VS2's own pipeline —
the shared transform funnel `CompatUtil.toSameSpaceAs` — unconditionally applies the ship's
rotation matrix to whatever position it is handed:

    out = c_world + R * (-c_ship)

For a real block coordinate this lands back on the actual block. For the sentinel
`(0, minY-1, 0)` it produces a point roughly `|c_ship| ~ 24 M` blocks from the world origin
that **moves every tick** as the ship rocks. Every tick, `BiomeManager` is then asked to
resolve a biome at that drifting garbage coordinate.

- Vanilla BiomeSources answer instantly from noise, so pure-vanilla setups never notice.
- Synchronous-inference BiomeSource mods (Terrain Diffusion: ~5 s of GPU inference per
  uncached region) park the main thread inside `getNoiseBiome` once per tick, forever.
  TPS drops to ~0.18. No natural end.

## The fix

One guard, injected at the shared transform funnel `CompatUtil.toSameSpaceAs` — the single
choke point that all three VS2 weather wrappers flow through:

    if (level != null && position.y() < level.getMinBuildHeight())
        return (x, minY, z); // clamp to world floor, skip the ship matrix

Zero false positives: every legitimate block coordinate satisfies `y >= minBuildHeight`;
only the sentinel (and other invalid values) fall below it. Sentinel queries now resolve to
a fixed, cacheable point `(0, minY, 0)` instead of a 24 M-block moving target — the cache
hits, the main thread never parks, TPS stays at 20.

## Version & maintenance

- Tested: Valkyrien Skies 2.4.11 on Minecraft 1.20.1, Forge 47.x.
- Other VS2 / MC versions: untested. The VS2 dependency range is intentionally left open —
  we cannot confirm which versions carry the sentinel leak. If the mixin target signature
  ever changes upstream, the mod fails loudly at apply time rather than silently misbehaving.
- This is a targeted bug fix, not a feature mod. No updates are planned beyond keeping the
  fix buildable; if VS2 upstream fixes the sentinel leak, this mod retires.

## Install / uninstall

Drop the jar in `mods/`. No config, no world data, no persistence. Remove the jar to
uninstall — zero residue.

## Build

- JDK 17; Gradle 8.14.3 via wrapper (or `tools/gradle-8.14.3`).
- Prerequisite: `libs/valkyrienskies-2.4.11.jar` must exist (compileOnly; copy it from your
  instance's `mods/`). `libs/` is gitignored.
- `.\gradlew.bat clean build` produces `build/libs/vs2-sentinel-guard-fix-1.0.0.jar`
- Self-check: `javap -p -c -classpath build/libs/vs2-sentinel-guard-fix-1.0.0.jar
  com.lmaxrouter.vs2sentinelguardfix.mixin.CompatUtilMixin` — the handler's
  `getMinBuildHeight` should appear as SRG `m_141937_` (proof of reobfJar remap).

## License

MPL-2.0.