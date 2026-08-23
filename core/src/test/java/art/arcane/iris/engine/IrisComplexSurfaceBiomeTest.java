package art.arcane.iris.engine;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.util.project.stream.ProceduralStream;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class IrisComplexSurfaceBiomeTest {
    @Test
    public void emptyRiverPoolsKeepWetChannelsAquaticAndDryChannelsLand() {
        assertEquals(InferredType.SEA, IrisComplex.directRiverFallback(sample(RiverRouteState.WET, RiverSection.CHANNEL)));
        assertEquals(InferredType.SEA, IrisComplex.directRiverFallback(sample(RiverRouteState.WET, RiverSection.MOUTH)));
        assertEquals(InferredType.LAND, IrisComplex.directRiverFallback(sample(RiverRouteState.DRY, RiverSection.DRY_BANK)));
        assertNull(IrisComplex.directRiverFallback(sample(RiverRouteState.WET, RiverSection.BANK)));
    }

    @Test
    public void elevatedRiverBankUsesTheLocalWaterHead() {
        IrisBiome base = mock(IrisBiome.class);
        IrisBiome sea = mock(IrisBiome.class);
        IrisRegion region = mock(IrisRegion.class);
        doReturn(false).when(base).isShore();
        doReturn(false).when(base).isAquatic();
        doReturn(3D).when(region).getShoreHeight(12D, 18D);

        IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                68D,
                base,
                region,
                12D,
                18D,
                70D,
                constant(base),
                constant(sea),
                constant(base));

        assertSame(sea, resolved);
    }
    @Test
    public void shorelineHeightSelectsShoreBiome() {
        IrisBiome base = mock(IrisBiome.class);
        IrisBiome shore = mock(IrisBiome.class);
        IrisRegion region = mock(IrisRegion.class);
        doReturn(false).when(base).isShore();
        doReturn(3D).when(region).getShoreHeight(12D, 18D);

        IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                63D,
                base,
                region,
                12D,
                18D,
                63D,
                constant(base),
                constant(base),
                constant(shore));

        assertSame(shore, resolved);
    }

    @Test
    public void raisedAquaticBiomeReturnsToLand() {
        IrisBiome aquatic = mock(IrisBiome.class);
        IrisBiome land = mock(IrisBiome.class);
        IrisRegion region = mock(IrisRegion.class);
        doReturn(false).when(aquatic).isShore();
        doReturn(false).when(aquatic).isLand();
        doReturn(1D).when(region).getShoreHeight(4D, 7D);

        IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                66D,
                aquatic,
                region,
                4D,
                7D,
                63D,
                constant(land),
                constant(aquatic),
                constant(aquatic));

        assertSame(land, resolved);
    }

    private static ProceduralStream<IrisBiome> constant(IrisBiome biome) {
        @SuppressWarnings("unchecked")
        ProceduralStream<IrisBiome> stream = mock(ProceduralStream.class);
        doReturn(biome).when(stream).get(anyDouble(), anyDouble());
        return stream;
    }

    private static RiverSample sample(RiverRouteState state, RiverSection section) {
        return new RiverSample(true, state, section, 0D, 0.5D, 1D, 1, 1, 10D, 5D, 3D, false, null);
    }
}
