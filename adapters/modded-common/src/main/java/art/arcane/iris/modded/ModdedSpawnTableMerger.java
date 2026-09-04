/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

package art.arcane.iris.modded;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.DimensionStackContext;
import art.arcane.iris.engine.DimensionTerrainContext;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.GenerationSessionLease;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.util.project.context.IrisContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class ModdedSpawnTableMerger {
    private final IrisModdedChunkGenerator generator;
    private volatile SpawnTableState state = new SpawnTableState();

    ModdedSpawnTableMerger(IrisModdedChunkGenerator generator) {
        this.generator = generator;
    }

    void initializeVanillaSpawnBiomes(Registry<Biome> registry) {
        SpawnTableState active = state;
        if (active.initialized) {
            return;
        }
        synchronized (active) {
            if (active != state || active.initialized) {
                return;
            }
            Engine current = generator.engineOrNull();
            if (current == null) {
                return;
            }

            try (GenerationSessionLease lease = generator.requireGenerationLease(current, "modded_spawn_biomes");
                 IrisContext.Scope ignored = IrisContext.open(current, lease.sessionId(), null)) {
                IrisDimension hostDimension = current.getDimension();
                addVanillaSpawnBiomes(
                        registry,
                        hostDimension,
                        current.getData(),
                        hostDimension.getReachableBiomes(current),
                        active
                );
                DimensionStackContext stackContext = current.getDimensionStackContext();
                if (stackContext != null) {
                    for (DimensionTerrainContext terrainContext : stackContext.getLayersBottomToTop()) {
                        if (terrainContext.isSelfReferencing()) {
                            continue;
                        }
                        IrisDimension dimension = terrainContext.getDimension();
                        addVanillaSpawnBiomes(
                                registry,
                                dimension,
                                terrainContext.getData(),
                                dimension.getReachableBiomes(terrainContext),
                                active
                        );
                    }
                }
                active.initialized = true;
            }
        }
    }

    private void addVanillaSpawnBiomes(
            Registry<Biome> registry,
            IrisDimension dimension,
            IrisData data,
            Iterable<IrisBiome> biomes,
            SpawnTableState state
    ) {
        for (IrisBiome irisBiome : biomes) {
            if (irisBiome == null || !irisBiome.isCustom()) {
                continue;
            }
            Holder<Biome> vanillaHolder = resolveBiomeHolder(
                    registry, irisBiome.getVanillaDerivativeKey());
            if (vanillaHolder == null) {
                continue;
            }
            for (IrisBiomeCustom customBiome : irisBiome.getCustomDerivitives()) {
                Holder<Biome> customHolder = resolveBiomeHolder(
                        registry,
                        ModdedWorldgenIds.biomeRef(
                                data.getDataFolder().getName(),
                                dimension.getLoadKey(),
                                customBiome.getId()
                        )
                );
                if (customHolder == null) {
                    continue;
                }
                state.vanillaSpawnBiomes.putIfAbsent(customHolder.value(), vanillaHolder);
            }
        }
    }

    Holder<Biome> vanillaSpawnBiome(Biome biome) {
        return state.vanillaSpawnBiomes.get(biome);
    }

    WeightedList<MobSpawnSettings.SpawnerData> mergedSpawnTable(
            Biome biome, MobCategory category,
            WeightedList<MobSpawnSettings.SpawnerData> vanillaSpawns,
            WeightedList<MobSpawnSettings.SpawnerData> explicitSpawns) {
        SpawnTableKey key = new SpawnTableKey(biome, category);
        return state.mergedSpawnTables.computeIfAbsent(
                key, ignored -> NativeSpawnTableMerger.merge(vanillaSpawns, explicitSpawns));
    }

    private Holder<Biome> resolveBiomeHolder(Registry<Biome> registry, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Identifier identifier = Identifier.tryParse(key);
        if (identifier == null) {
            return null;
        }
        Optional<Holder.Reference<Biome>> reference = registry.get(identifier);
        return reference.<Holder<Biome>>map((Holder.Reference<Biome> value) -> value).orElse(null);
    }

    void resetVanillaSpawnBiomes() {
        state = new SpawnTableState();
    }

    private record SpawnTableKey(Biome biome, MobCategory category) {
    }

    private static final class SpawnTableState {
        private final ConcurrentHashMap<Biome, Holder<Biome>> vanillaSpawnBiomes = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<SpawnTableKey, WeightedList<MobSpawnSettings.SpawnerData>> mergedSpawnTables =
                new ConcurrentHashMap<>();
        private volatile boolean initialized;
    }
}
