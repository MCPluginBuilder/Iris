/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine.actuator;

import art.arcane.iris.engine.DimensionStackContext;
import art.arcane.iris.engine.DimensionStackLayout;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedActuator;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.context.ChunkedDataCache;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.documentation.BlockCoordinates;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterBiomeInject;
import art.arcane.volmlib.util.matter.slices.BiomeInjectMatter;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBiome;

import java.util.List;

public class IrisBiomeActuator extends EngineAssignedActuator<PlatformBiome> {
    private final RNG rng;
    private final ChronoLatch cl = new ChronoLatch(5000);
    private final KMap<String, ResolvedBiome> resolvedBiomes = new KMap<>();

    public IrisBiomeActuator(Engine engine) {
        super(engine, "Biome");
        rng = new RNG(engine.getSeedManager().getBiome());
    }

    @BlockCoordinates
    @Override
    public void onActuate(int x, int z, Hunk<PlatformBiome> h, boolean multicore, ChunkContext context) {
        // No catch here: a partial biome write must fail the chunk (EngineAssignedActuator
        // rethrows), or the chunk is flagged REAL with default plains columns forever.
        PrecisionStopwatch p = PrecisionStopwatch.start();
        int width = h.getWidth();
        int depth = h.getDepth();
        int height = h.getHeight();
        Engine engine = getEngine();
        Mantle<Matter> mantle = engine.getMantle().getMantle();
        ChunkedDataCache<IrisBiome> biomeCache = context.getBiome();
        DimensionStackContext dimensionStackContext = engine.getDimensionStackContext();

        for (int xf = 0; xf < width; xf++) {
            for (int zf = 0; zf < depth; zf++) {
                int worldX = x + xf;
                int worldZ = z + zf;
                IrisBiome biome = biomeCache.get(xf, zf);
                ResolvedBiome resolved = resolve(biomeKey(
                        biome,
                        getDimension(),
                        engine,
                        worldX,
                        0,
                        worldZ
                ));
                PlatformBiome platformBiome = resolved.biome();

                if (platformBiome != null) {
                    h.set(xf, 0, zf, xf, height - 1, zf, platformBiome);
                }

                mantle.set(worldX, 0, worldZ, resolved.matter());
                if (dimensionStackContext != null) {
                    PlatformBiome bottomBiome = platformBiome == null
                            ? h.getRaw(xf, 0, zf)
                            : platformBiome;
                    applyDimensionStackBiomes(
                            xf,
                            zf,
                            worldX,
                            worldZ,
                            h,
                            mantle,
                            engine,
                            new ResolvedBiome(bottomBiome, resolved.matter()),
                            context.getDimensionStackLayout(xf, zf)
                    );
                }
            }
        }
        engine.getMetrics().getBiome().put(p.getMilliseconds());
    }

    private void applyDimensionStackBiomes(
            int localX,
            int localZ,
            int worldX,
            int worldZ,
            Hunk<PlatformBiome> output,
            Mantle<Matter> mantle,
            Engine engine,
            ResolvedBiome bottom,
            DimensionStackLayout layout
    ) {
        List<DimensionStackLayout.Layer> layers = layout.layersBottomToTop();
        for (int layerIndex = 1; layerIndex < layers.size(); layerIndex++) {
            DimensionStackLayout.Layer lower = layers.get(layerIndex - 1);
            DimensionStackLayout.Layer layer = layers.get(layerIndex);
            int gapMinY = (int) Math.max(0L, (long) lower.contentTopY() + 1L);
            int gapMaxY = (int) Math.min(
                    (long) output.getHeight() - 1L,
                    (long) layer.localBaseY() - 1L
            );
            applyBiomeRange(
                    localX,
                    localZ,
                    worldX,
                    worldZ,
                    gapMinY,
                    gapMaxY,
                    output,
                    mantle,
                    bottom
            );
            if (!layer.visible()) {
                continue;
            }
            ResolvedBiome resolved = bottom;
            if (layer.biome() != null) {
                IrisDimension dimension = layer.terrainContext().getDimension();
                resolved = resolve(biomeKey(
                        layer.biome(),
                        dimension,
                        engine,
                        worldX,
                        layer.clippedSurfaceY(),
                        worldZ
                ));
            }
            applyBiomeRange(
                    localX,
                    localZ,
                    worldX,
                    worldZ,
                    layer.renderMinY(),
                    layer.renderMaxY(),
                    output,
                    mantle,
                    resolved
            );
        }
    }

    private void applyBiomeRange(
            int localX,
            int localZ,
            int worldX,
            int worldZ,
            int minimumY,
            int maximumY,
            Hunk<PlatformBiome> output,
            Mantle<Matter> mantle,
            ResolvedBiome resolved
    ) {
        if (minimumY > maximumY) {
            return;
        }
        clearBiomeMatterRange(mantle, worldX, worldZ, minimumY, maximumY);
        if (resolved.biome() != null) {
            output.set(
                    localX,
                    minimumY,
                    localZ,
                    localX,
                    maximumY,
                    localZ,
                    resolved.biome()
            );
        }
        if (resolved.matter() != null) {
            mantle.set(worldX, minimumY, worldZ, resolved.matter());
        }
    }

    static void clearBiomeMatterRange(
            Mantle<Matter> mantle,
            int worldX,
            int worldZ,
            int minimumY,
            int maximumY
    ) {
        for (int y = minimumY; y <= maximumY; y++) {
            mantle.remove(worldX, y, worldZ, MatterBiomeInject.class);
        }
    }

    private String biomeKey(
            IrisBiome biome,
            IrisDimension dimension,
            Engine engine,
            int x,
            int y,
            int z
    ) {
        if (biome.isCustom()) {
            IrisBiomeCustom custom = biome.getCustomBiome(rng, engine, x, y, z);
            return dimension.getCustomBiomeKey(custom.getId());
        }
        return biome.getSkyBiomeKey(rng, engine, x, y, z);
    }

    /**
     * The registry lookup and the biome id only depend on the scatter resolved key, so cache the pair
     * instead of paying two string keyed lookups per column. Unresolved biomes are never cached, so a
     * biome registered later still resolves.
     */
    private ResolvedBiome resolve(String key) {
        ResolvedBiome cached = key == null ? null : resolvedBiomes.get(key);

        if (cached != null) {
            return cached;
        }

        IrisPlatform platform = IrisPlatforms.get();
        PlatformBiome biome = platform.registries().biome(key);
        ResolvedBiome resolved = new ResolvedBiome(biome, BiomeInjectMatter.get(platform.biomeWriter().biomeIdFor(key)));

        if (key != null && biome != null) {
            resolvedBiomes.put(key, resolved);
        }

        return resolved;
    }

    private record ResolvedBiome(PlatformBiome biome, MatterBiomeInject matter) {
    }
}
