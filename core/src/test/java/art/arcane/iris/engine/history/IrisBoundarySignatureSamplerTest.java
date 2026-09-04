package art.arcane.iris.engine.history;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.UpperDimensionContext;
import art.arcane.iris.util.project.stream.ProceduralStream;
import org.junit.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IrisBoundarySignatureSamplerTest {
    @Test
    public void recordsTheEffectiveCeilingInInternalCoordinatesAndBiomesInWorldCoordinates() throws Exception {
        IrisEngine engine = engine();
        UpperDimensionContext upper = mock(UpperDimensionContext.class);
        when(engine.getUpperContext()).thenReturn(upper);
        when(upper.getEffectiveSurfaceY(-17, 8)).thenReturn(200);

        TerrainBoundarySignature signature = IrisBoundarySignatureSampler.INSTANCE.sample(engine, -17, 8);

        assertEquals(OptionalInt.of(55), signature.upperCeilingDepth());
        assertEquals(64, signature.oceanFloorHeight());
        assertEquals(-64, signature.sampleY(0));
        assertEquals(188, signature.sampleY(63));
        verify(upper).getEffectiveSurfaceY(-17, 8);
    }

    @Test
    public void recordsAbsentUpperMassWithAndWithoutAnUpperContext() throws Exception {
        IrisEngine engine = engine();
        assertTrue(IrisBoundarySignatureSampler.INSTANCE.sample(engine, -17, 8).upperCeilingDepth().isEmpty());
        UpperDimensionContext upper = mock(UpperDimensionContext.class);
        when(engine.getUpperContext()).thenReturn(upper);
        when(upper.getEffectiveSurfaceY(-17, 8)).thenReturn(256);
        assertTrue(IrisBoundarySignatureSampler.INSTANCE.sample(engine, -17, 8).upperCeilingDepth().isEmpty());
    }

    private static IrisEngine engine() {
        IrisEngine engine = mock(IrisEngine.class);
        IrisComplex complex = mock(IrisComplex.class);
        when(engine.getComplex()).thenReturn(complex);
        when(engine.getHeight()).thenReturn(256);
        when(engine.getMinHeight()).thenReturn(-64);
        when(complex.getHeightStream()).thenReturn(ProceduralStream.ofDouble((x, z) -> 64D));
        when(complex.getRiverWaterSurfaceStream()).thenReturn(ProceduralStream.ofDouble((x, z) -> 64D));
        when(complex.historicalPhysicalBiomeKeyAt(anyInt(), anyInt(), anyInt()))
                .thenReturn(Optional.of("minecraft:plains"));
        return engine;
    }
}
