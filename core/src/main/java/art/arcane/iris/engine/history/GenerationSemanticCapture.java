package art.arcane.iris.engine.history;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.StructurePlacementMarker;
import art.arcane.iris.engine.framework.StructurePlacementScope;
import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydrologyColumnSample;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveCell;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class GenerationSemanticCapture {
    private static final int CHUNK_SIZE = 16;

    private GenerationSemanticCapture() {
    }

    public static ChunkGenerationSemantics capture(
            Engine engine,
            GenerationHistory.GenerationStage stage
    ) {
        Engine requiredEngine = Objects.requireNonNull(engine, "engine");
        GenerationHistory.GenerationStage requiredStage = Objects.requireNonNull(stage, "stage");
        return capture(
                requiredEngine,
                requiredStage.chunkX(),
                requiredStage.chunkZ(),
                requiredStage.activation().activationId()
        );
    }

    public static ChunkGenerationSemantics capture(
            Engine engine,
            int chunkX,
            int chunkZ,
            long activationId
    ) {
        Engine requiredEngine = Objects.requireNonNull(engine, "engine");
        ChunkGenerationSemantics.Builder semantics = ChunkGenerationSemantics.builder(
                chunkX,
                chunkZ,
                activationId
        );
        captureColumns(requiredEngine, chunkX, chunkZ, semantics);
        captureMantleFacts(requiredEngine, chunkX, chunkZ, semantics);
        captureStructures(requiredEngine, chunkX, chunkZ, semantics);
        return semantics.seal().build();
    }

    private static void captureColumns(
            Engine engine,
            int chunkX,
            int chunkZ,
            ChunkGenerationSemantics.Builder semantics
    ) {
        IrisComplex complex = engine.getComplex();
        int minimumX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int minimumZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        for (int localX = 0; localX < CHUNK_SIZE; localX++) {
            int blockX = minimumX + localX;
            for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
                int blockZ = minimumZ + localZ;
                if (complex.allowsNewDiscreteContentAt(blockX, blockZ)) {
                    IrisBiome biome = complex.getTrueBiomeStream().get(blockX, blockZ);
                    if (biome != null && biome.getLoadKey() != null) {
                        semantics.addSurfaceBiome(biome.getLoadKey());
                    }
                    IrisRegion region = complex.getRegionStream().get(blockX, blockZ);
                    if (region != null && region.getLoadKey() != null) {
                        semantics.addRegion(region.getLoadKey());
                    }
                }
                if (!hasFullHydrologyWeight(complex, blockX, blockZ)) {
                    continue;
                }
                HydrologyColumnSample hydrology = complex.sampleHydrologyColumn(blockX, blockZ);
                if (hydrology == null) {
                    continue;
                }
                for (HydrologyColumnLayer layer : hydrology.layers()) {
                    semantics.addRiverProfile(layer.profileKey());
                    semantics.addRiverFeature(
                            layer.profileKey(),
                            layer.feature().type(),
                            layer.feature().id(),
                            layer.feature().x(),
                            Math.addExact(
                                    layer.feature().y(),
                                    engine.getDimension().getMinHeight()
                            ),
                            layer.feature().z()
                    );
                }
            }
        }
    }

    private static void captureMantleFacts(
            Engine engine,
            int chunkX,
            int chunkZ,
            ChunkGenerationSemantics.Builder semantics
    ) {
        Mantle<Matter> mantle = engine.getMantle().getMantle();
        int minimumX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int minimumZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        Set<CavePosition> resolvedCaves = new HashSet<>();
        mantle.iterateChunk(chunkX, chunkZ, MatterCavern.class, (localX, y, localZ, cavern) -> {
            captureCave(
                    engine,
                    semantics,
                    resolvedCaves,
                    minimumX + localX,
                    y,
                    minimumZ + localZ,
                    cavern == null ? null : cavern.getCustomBiome()
            );
        });
        mantle.iterateChunk(chunkX, chunkZ, HydrologyCaveCell.class, (localX, y, localZ, cell) -> {
            int blockX = minimumX + localX;
            int blockZ = minimumZ + localZ;
            if (!hasFullHydrologyWeight(engine.getComplex(), blockX, blockZ)) {
                return;
            }
            captureCave(
                    engine,
                    semantics,
                    resolvedCaves,
                    blockX,
                    y,
                    blockZ,
                    cell == null ? null : cell.floodedBiomeKey()
            );
            if (cell != null && cell.fluidProfileKey() != null && !cell.fluidProfileKey().isBlank()) {
                semantics.addRiverProfile(cell.fluidProfileKey());
            }
        });
        mantle.iterateChunk(chunkX, chunkZ, String.class, (localX, y, localZ, marker) -> {
            StructurePlacementMarker.Decoded decoded = StructurePlacementMarker.decode(marker);
            if (decoded != null) {
                semantics.addObject(decoded.objectKey());
            }
        });
    }

    private static boolean hasFullHydrologyWeight(IrisComplex complex, int blockX, int blockZ) {
        TransitionGenerationPlan transition = complex.getTransitionGenerationPlan();
        return transition == null || transition.hydrologyWeightAt(blockX, blockZ) == 1D;
    }

    private static void captureCave(
            Engine engine,
            ChunkGenerationSemantics.Builder semantics,
            Set<CavePosition> resolvedCaves,
            int blockX,
            int y,
            int blockZ,
            String explicitBiomeKey
    ) {
        CavePosition position = new CavePosition(blockX, y, blockZ);
        if (!resolvedCaves.add(position)) {
            return;
        }
        if (explicitBiomeKey != null && !explicitBiomeKey.isBlank()) {
            semantics.addCaveBiome(explicitBiomeKey);
            return;
        }
        IrisBiome caveBiome = engine.getCaveBiome(blockX, y, blockZ);
        if (caveBiome != null && caveBiome.getLoadKey() != null) {
            semantics.addCaveBiome(caveBiome.getLoadKey());
        }
    }

    private static void captureStructures(
            Engine engine,
            int chunkX,
            int chunkZ,
            ChunkGenerationSemantics.Builder semantics
    ) {
        IrisComplex complex = engine.getComplex();
        Mantle<Matter> mantle = engine.getMantle().getMantle();
        if (!complex.allowsNewGenerationChunk(chunkX, chunkZ)
                || !mantle.hasFlag(chunkX, chunkZ, ReservedFlag.JIGSAW)) {
            return;
        }
        for (IrisStructurePlacement placement : StructurePlacementScope.placementsAt(
                engine,
                chunkX,
                chunkZ)) {
            if (placement == null || !placement.hasIrisStructures()) {
                continue;
            }
            IrisStructureLocator.ResolvedPlacement resolved = IrisStructureLocator.resolvePlacement(
                    engine,
                    placement,
                    chunkX,
                    chunkZ
            );
            if (resolved == null || !IrisStructureLocator.allowsResolvedFootprint(engine, resolved)) {
                continue;
            }
            semantics.addStructure(
                    resolved.structureKey(),
                    resolved.originX(),
                    resolved.baseY(),
                    resolved.originZ()
            );
        }
    }

    private record CavePosition(int x, int y, int z) {
    }
}
