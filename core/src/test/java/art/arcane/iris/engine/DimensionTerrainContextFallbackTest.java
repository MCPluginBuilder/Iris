package art.arcane.iris.engine;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.image.IrisImageMapRuntime;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.stream.ProceduralStream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DimensionTerrainContextFallbackTest {
    @Test
    @SuppressWarnings("unchecked")
    public void selfSamplerUsesNaturalValuesOnlyForNonblockingColumns() {
        Engine engine = mock(Engine.class);
        IrisDimension dimension = mock(IrisDimension.class);
        IrisComplex complex = mock(IrisComplex.class);
        ProceduralStream<Double> naturalHeight = mock(ProceduralStream.class);
        ProceduralStream<Double> resolvedHeight = mock(ProceduralStream.class);
        ProceduralStream<Double> resolvedFluidHeight = mock(ProceduralStream.class);
        ProceduralStream<IrisBiome> naturalBiome = mock(ProceduralStream.class);
        ProceduralStream<IrisBiome> resolvedBiome = mock(ProceduralStream.class);
        ProceduralStream<IrisRegion> region = mock(ProceduralStream.class);
        ProceduralStream<PlatformBlockState> rock = mock(ProceduralStream.class);
        ProceduralStream<PlatformBlockState> configuredFluid = mock(ProceduralStream.class);
        IrisImageMapRuntime imageMapRuntime = mock(IrisImageMapRuntime.class);
        IrisBiome naturalBiomeValue = mock(IrisBiome.class);
        IrisBiome resolvedBiomeValue = mock(IrisBiome.class);
        PlatformBlockState naturalFluid = mock(PlatformBlockState.class);
        PlatformBlockState resolvedFluid = mock(PlatformBlockState.class);

        when(engine.getDimension()).thenReturn(dimension);
        when(engine.getComplex()).thenReturn(complex);
        when(engine.getHeight()).thenReturn(128);
        when(dimension.getLoadKey()).thenReturn("root");
        when(dimension.getFluidHeight()).thenReturn(12);
        when(complex.getNaturalHeightStream()).thenReturn(naturalHeight);
        when(complex.getHeightStream()).thenReturn(resolvedHeight);
        when(complex.getRiverWaterSurfaceStream()).thenReturn(resolvedFluidHeight);
        when(complex.getNaturalTrueBiomeStream()).thenReturn(naturalBiome);
        when(complex.getTrueBiomeStream()).thenReturn(resolvedBiome);
        when(complex.getRegionStream()).thenReturn(region);
        when(complex.getRockStream()).thenReturn(rock);
        when(complex.getFluidStream()).thenReturn(configuredFluid);
        when(complex.getImageMapRuntime()).thenReturn(imageMapRuntime);
        when(naturalHeight.getDouble(12D, -8D)).thenReturn(15D);
        when(resolvedHeight.getDouble(12D, -8D)).thenReturn(18D);
        when(resolvedFluidHeight.getDouble(12D, -8D)).thenReturn(14D);
        when(naturalBiome.get(12D, -8D)).thenReturn(naturalBiomeValue);
        when(resolvedBiome.get(12D, -8D)).thenReturn(resolvedBiomeValue);
        when(configuredFluid.get(12D, -8D)).thenReturn(naturalFluid);
        when(complex.resolveSurfaceFluid(12D, -8D)).thenReturn(resolvedFluid);

        DimensionTerrainContext context = DimensionTerrainContext.forStack(engine, dimension);
        when(engine.answersFromNaturalTerrain(12, -8)).thenReturn(true);
        assertEquals(15D, context.getNormalTerrainHeight(12D, -8D), 0D);
        assertEquals(12D, context.getFluidHeight(12D, -8D), 0D);
        assertSame(naturalBiomeValue, context.getBiome(12D, -8D));
        assertSame(naturalFluid, context.getFluidBlock(12D, -8D));

        when(engine.answersFromNaturalTerrain(12, -8)).thenReturn(false);
        assertEquals(18D, context.getNormalTerrainHeight(12D, -8D), 0D);
        assertEquals(14D, context.getFluidHeight(12D, -8D), 0D);
        assertSame(resolvedBiomeValue, context.getBiome(12D, -8D));
        assertSame(resolvedFluid, context.getFluidBlock(12D, -8D));
    }
}
