package art.arcane.iris.engine.actuator;

import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydrologyColumnSample;
import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import art.arcane.iris.engine.object.IrisDecorationStep;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisTerrainNormalActuatorHydrologyTest {
    @Test
    public void hydrologyOwnedFluidBypassesBiomeSeaLayers() {
        PlatformBlockState water = mock(PlatformBlockState.class);
        PlatformBlockState ice = mock(PlatformBlockState.class);
        KList<PlatformBlockState> seaLayers = new KList<>();
        seaLayers.add(ice);

        assertSame(water, HydrologyFluidLayerSelector.select(seaLayers, 0, water, true));
        assertSame(ice, HydrologyFluidLayerSelector.select(seaLayers, 0, water, false));
        assertSame(water, HydrologyFluidLayerSelector.select(seaLayers, 1, water, false));
    }

    @Test
    public void frozenTerrainPublishesSourceWaterBeforeStandardTopLayerFreezing() {
        PlatformBlockState water = mock(PlatformBlockState.class);
        PlatformBlockState ice = mock(PlatformBlockState.class);
        when(water.key()).thenReturn("minecraft:water");
        when(water.isWater()).thenReturn(true);
        when(ice.key()).thenReturn("minecraft:ice");
        KList<PlatformBlockState> frozenSeaLayers = new KList<>();
        frozenSeaLayers.add(ice);
        HydrologyFeatureRef feature = new HydrologyFeatureRef(
                11L,
                HydrologyFeatureType.SURFACE_POOL,
                12L,
                13L,
                4,
                70,
                6,
                1,
                0,
                true
        );
        HydrologyColumnLayer acceptedLayer = new HydrologyColumnLayer(
                feature,
                67,
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
                "default",
                "frozen_river",
                "frozen_mouth",
                "frozen_shore",
                "frozen_dry",
                "frozen_cave"
        );
        HydrologyColumnSample acceptedColumn = new HydrologyColumnSample(
                4,
                6,
                76,
                63,
                false,
                "frozen_parent",
                List.of(acceptedLayer)
        );
        boolean hydrologyOwned = acceptedColumn.primarySurfaceFluidLayer().isPresent();
        ArrayList<String> stages = new ArrayList<>();

        PlatformBlockState state = HydrologyFluidLayerSelector.select(
                frozenSeaLayers,
                0,
                water,
                hydrologyOwned
        );
        stages.add("hydrology");
        assertTrue(hydrologyOwned);
        assertSame(water, state);
        assertEquals("minecraft:water", state.key());
        assertTrue(state.isWater());
        assertFalse(acceptedLayer.fallingFluid());

        for (IrisDecorationStep step : IrisDecorationStep.values()) {
            if (step == IrisDecorationStep.TOP_LAYER_MODIFICATION) {
                assertSame(water, state);
                state = ice;
                stages.add(step.getSerializedName());
            }
        }

        assertEquals(List.of("hydrology", "top_layer_modification"), stages);
        assertSame(ice, state);
        assertEquals("minecraft:ice", state.key());
        assertFalse(state.isWater());
    }
}
