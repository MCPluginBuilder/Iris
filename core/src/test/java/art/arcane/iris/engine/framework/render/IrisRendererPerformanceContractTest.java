package art.arcane.iris.engine.framework.render;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import java.awt.Color;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class IrisRendererPerformanceContractTest {
    @Test
    public void cancelledRenderStopsBeforeSamplingTheEngine() {
        Engine engine = mock(Engine.class);
        IrisRenderer renderer = new IrisRenderer(engine);

        assertThrows(
                CancellationException.class,
                () -> renderer.render(0D, 0D, 128D, 128, RenderType.BIOME, () -> true)
        );
        verifyNoInteractions(engine);
    }

    @Test
    public void invalidResolutionIsRejectedBeforeSamplingTheEngine() {
        Engine engine = mock(Engine.class);
        IrisRenderer renderer = new IrisRenderer(engine);

        assertThrows(IllegalArgumentException.class, () -> renderer.render(0D, 0D, 128D, 0, RenderType.BIOME));
        verifyNoInteractions(engine);
    }

    @Test
    public void heightPaletteIsNormalizedClampedAndReadable() {
        int deepWater = IrisRenderer.heightColor(0D, 320D, 64D);
        int shallowWater = IrisRenderer.heightColor(64D, 320D, 64D);
        int lowland = IrisRenderer.heightColor(80D, 320D, 64D);
        int highland = IrisRenderer.heightColor(190D, 320D, 64D);
        int snow = IrisRenderer.heightColor(320D, 320D, 64D);

        assertNotEquals(deepWater, shallowWater);
        assertNotEquals(shallowWater, lowland);
        assertNotEquals(lowland, highland);
        assertNotEquals(highland, snow);
        assertEquals(deepWater, IrisRenderer.heightColor(-500D, 320D, 64D));
        assertEquals(snow, IrisRenderer.heightColor(900D, 320D, 64D));
    }

    @Test
    public void samplingGroupsStayWithinAStreamCacheCell() {
        assertEquals(16, IrisRenderer.sampleGroup(1D, 128));
        assertEquals(4, IrisRenderer.sampleGroup(4D, 128));
        assertEquals(1, IrisRenderer.sampleGroup(32D, 128));
        assertEquals(1, IrisRenderer.sampleGroup(0D, 128));
    }

    @Test
    public void studioHeightUsesNaturalTerrainWhileProtocolRenderRemainsExact() {
        Engine engine = mock(Engine.class);
        IrisComplex complex = mock(IrisComplex.class);
        @SuppressWarnings("unchecked")
        ProceduralStream<Double> natural = mock(ProceduralStream.class);
        @SuppressWarnings("unchecked")
        ProceduralStream<Double> exact = mock(ProceduralStream.class);
        when(engine.getComplex()).thenReturn(complex);
        when(engine.getHeight()).thenReturn(320);
        when(complex.getNaturalHeightStream()).thenReturn(natural);
        when(complex.getHeightStream()).thenReturn(exact);
        when(natural.getDouble(0D, 0D)).thenReturn(80D);
        when(exact.getDouble(0D, 0D)).thenReturn(96D);
        IrisRenderer renderer = new IrisRenderer(engine);

        renderer.renderStudio(0D, 0D, 1D, 1, RenderType.HEIGHT, () -> false);

        verify(natural).getDouble(0D, 0D);
        verifyNoInteractions(exact);

        renderer.render(0D, 0D, 1D, 1, RenderType.HEIGHT);

        verify(exact).getDouble(0D, 0D);
    }

    @Test
    public void studioBiomeAndContinentAvoidRiverAdjustedEngineLookups() {
        Engine engine = mock(Engine.class);
        IrisComplex complex = mock(IrisComplex.class);
        IrisBiome biome = mock(IrisBiome.class);
        @SuppressWarnings("unchecked")
        ProceduralStream<IrisBiome> natural = mock(ProceduralStream.class);
        when(engine.getComplex()).thenReturn(complex);
        when(complex.getNaturalTrueBiomeStream()).thenReturn(natural);
        when(natural.get(0D, 0D)).thenReturn(biome);
        when(biome.getColor(engine, RenderType.BIOME)).thenReturn(Color.GREEN);
        when(biome.getGenerators()).thenReturn(new KList<>());
        IrisRenderer renderer = new IrisRenderer(engine);

        renderer.renderStudio(0D, 0D, 1D, 1, RenderType.BIOME, () -> false);
        renderer.renderStudio(0D, 0D, 1D, 1, RenderType.CONTINENT, () -> false);

        verify(natural, times(2)).get(0D, 0D);
        verify(engine, never()).getBiome(anyInt(), anyInt(), anyInt());
    }
}
