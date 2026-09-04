package art.arcane.iris.engine.actuator;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineMetrics;
import art.arcane.iris.engine.framework.SeedManager;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBiomeWriter;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.context.ChunkedDataCache;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterBiomeInject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IrisBiomeActuatorCoordinateTest {
    @After
    public void unbindPlatform() {
        IrisPlatforms.unbind();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void derivativeScatterUsesEachWorldColumnCoordinate() {
        bindPlatform();
        IrisComplex complex = mock(IrisComplex.class);
        when(complex.allowsNewDiscreteContentAt(anyInt(), anyInt())).thenReturn(true);
        Engine engine = engine(complex);
        IrisBiomeActuator actuator = new IrisBiomeActuator(engine);
        Hunk<PlatformBiome> output = mock(Hunk.class);
        when(output.getWidth()).thenReturn(2);
        when(output.getDepth()).thenReturn(2);
        when(output.getHeight()).thenReturn(1);

        List<String> samples = new ArrayList<>();
        IrisBiome biome = mock(IrisBiome.class);
        when(biome.getSkyBiomeKey(any(RNG.class), same(engine), anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(invocation -> {
                    double worldX = invocation.getArgument(2);
                    double worldZ = invocation.getArgument(4);
                    samples.add((int) worldX + "," + (int) worldZ);
                    return "minecraft:plains";
                });

        ChunkedDataCache<IrisBiome> biomeCache = mock(ChunkedDataCache.class);
        when(biomeCache.get(anyInt(), anyInt())).thenReturn(biome);
        ChunkContext context = mock(ChunkContext.class);
        when(context.getComplex()).thenReturn(complex);
        when(context.getBiome()).thenReturn(biomeCache);

        actuator.onActuate(100, -200, output, false, context);

        assertEquals(List.of("100,-200", "100,-199", "101,-200", "101,-199"), samples);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void transitionColumnEmitsFrozenPhysicalKeyWithoutResolvingANewBiome() {
        bindPlatform();
        IrisComplex complex = mock(IrisComplex.class);
        when(complex.allowsNewDiscreteContentAt(100, -200)).thenReturn(false);
        when(complex.historicalPhysicalBiomeKeyAt(100, 0, -200))
                .thenReturn(Optional.of("iris:old-physical"));
        Engine engine = engine(complex);
        IrisBiomeActuator actuator = new IrisBiomeActuator(engine);
        Hunk<PlatformBiome> output = mock(Hunk.class);
        when(output.getWidth()).thenReturn(1);
        when(output.getDepth()).thenReturn(1);
        when(output.getHeight()).thenReturn(1);
        ChunkedDataCache<IrisBiome> biomeCache = mock(ChunkedDataCache.class);
        ChunkContext context = mock(ChunkContext.class);
        when(context.getComplex()).thenReturn(complex);
        when(context.getBiome()).thenReturn(biomeCache);

        actuator.onActuate(100, -200, output, false, context);

        verify(biomeCache, never()).get(anyInt(), anyInt());
        verify(IrisPlatforms.get().registries()).biome("iris:old-physical");
        verify(IrisPlatforms.get().biomeWriter(), never()).biomeIdFor(anyString());
        Mantle<Matter> mantle = engine.getMantle().getMantle();
        verify(mantle).set(eq(100), eq(0), eq(-200), argThat(value -> {
            MatterBiomeInject injection = (MatterBiomeInject) value;
            return !injection.isCustom() && "iris:old-physical".equals(injection.getBiomeKey());
        }));
    }

    @SuppressWarnings("unchecked")
    private Engine engine(IrisComplex complex) {
        Mantle<Matter> mantle = mock(Mantle.class);
        EngineMantle engineMantle = mock(EngineMantle.class);
        when(engineMantle.getMantle()).thenReturn(mantle);
        SeedManager seedManager = mock(SeedManager.class);
        when(seedManager.getBiome()).thenReturn(1337L);
        Engine engine = mock(Engine.class);
        when(engine.getCacheID()).thenReturn(1);
        when(engine.getSeedManager()).thenReturn(seedManager);
        when(engine.getMantle()).thenReturn(engineMantle);
        when(engine.getComplex()).thenReturn(complex);
        when(engine.getMetrics()).thenReturn(new EngineMetrics(16));
        return engine;
    }

    private void bindPlatform() {
        IrisPlatforms.unbind();
        PlatformBiome biome = mock(PlatformBiome.class);
        PlatformBlockState block = mock(PlatformBlockState.class);
        PlatformRegistries registries = mock(PlatformRegistries.class);
        when(registries.biome(anyString())).thenReturn(biome);
        when(registries.block(anyString())).thenReturn(block);
        PlatformBiomeWriter biomeWriter = mock(PlatformBiomeWriter.class);
        when(biomeWriter.biomeIdFor(anyString())).thenReturn(1);
        IrisPlatform platform = mock(IrisPlatform.class);
        when(platform.registries()).thenReturn(registries);
        when(platform.biomeWriter()).thenReturn(biomeWriter);
        IrisPlatforms.bind(platform);
    }
}
