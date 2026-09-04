package art.arcane.iris.engine;

import art.arcane.iris.engine.history.TerrainBoundarySignature;
import art.arcane.iris.engine.history.TransitionGenerationPlan;
import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydrologyColumnSample;
import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import org.junit.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class IrisComplexTransitionTest {
    @Test
    public void blendsDryBoundaryTerrainFromTheFrozenSurface() {
        TransitionGenerationPlan.TerrainSample sample = new TransitionGenerationPlan.TerrainSample(
                8D,
                0.5D,
                0D,
                signature(OptionalInt.empty()),
                64D,
                55D,
                0D
        );

        assertEquals(82D, IrisComplex.blendNaturalTerrainHeight(100D, sample), 0D);
    }

    @Test
    public void blendsSubmergedBoundaryTerrainFromTheFrozenOceanFloor() {
        TransitionGenerationPlan.TerrainSample sample = new TransitionGenerationPlan.TerrainSample(
                8D,
                0.5D,
                0D,
                signature(OptionalInt.of(63)),
                64D,
                55D,
                0D
        );

        assertEquals(75D, IrisComplex.blendNaturalTerrainHeight(95D, sample), 0D);
    }

    @Test
    public void preservesNewTerrainAndHydrologyObjectsAtFullWeight() {
        TransitionGenerationPlan.TerrainSample terrain = new TransitionGenerationPlan.TerrainSample(
                32D,
                1D,
                1D,
                signature(OptionalInt.empty()),
                64D,
                55D,
                0D
        );
        HydrologyColumnSample hydrology = hydrologySample();

        assertEquals(101.25D, IrisComplex.blendNaturalTerrainHeight(101.25D, terrain), 0D);
        assertSame(hydrology, IrisComplex.taperHydrologySample(hydrology, 1D));
    }

    @Test
    public void tapersHydrologyGeometryTowardNaturalTerrain() {
        HydrologyColumnSample tapered = IrisComplex.taperHydrologySample(hydrologySample(), 0.5D);
        HydrologyColumnLayer layer = tapered.layers().getFirst();

        assertEquals(73, layer.bedY());
        assertEquals(75, layer.fluidHeadY());
        assertEquals(75, layer.ceilingY());
        assertEquals(73, tapered.terrainHeight());
        assertEquals("river", layer.profileKey());
    }

    private static TerrainBoundarySignature signature(OptionalInt fluidHeight) {
        return new TerrainBoundarySignature(
                new TerrainBoundarySignature.Column(15, 8, 64, 55, fluidHeight, OptionalInt.empty()),
                new TerrainBoundarySignature.Samples(
                        new TerrainBoundarySignature.VerticalLayout(0, 1, 1),
                        new TerrainBoundarySignature.BiomeEncoding(List.of("iris:frozen"), new short[]{0})
                )
        );
    }

    private static HydrologyColumnSample hydrologySample() {
        HydrologyFeatureRef feature = new HydrologyFeatureRef(
                1L,
                HydrologyFeatureType.SURFACE_POOL,
                2L,
                3L,
                4,
                70,
                6,
                1,
                0,
                true
        );
        HydrologyColumnLayer layer = new HydrologyColumnLayer(
                feature,
                66,
                70,
                70,
                true,
                false,
                false,
                true,
                false,
                false,
                true,
                true,
                false,
                "river",
                "surface",
                "mouth",
                "shore",
                "bank",
                "cave"
        );
        return new HydrologyColumnSample(4, 6, 80, 63, false, "parent", List.of(layer));
    }
}
