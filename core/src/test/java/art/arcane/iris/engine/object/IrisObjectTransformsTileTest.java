package art.arcane.iris.engine.object;

import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.util.common.math.IrisBlockVector;
import art.arcane.iris.util.common.math.Vector3i;
import art.arcane.volmlib.util.collection.KMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisObjectTransformsTileTest {
    @Before
    public void bindPlatform() {
        IrisPlatforms.unbind();
        PlatformRegistries registries = mock(PlatformRegistries.class);
        Map<String, PlatformBlockState> states = new HashMap<>();
        when(registries.block(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return states.computeIfAbsent(key, IrisObjectTransformsTileTest::state);
        });
        IrisPlatform platform = mock(IrisPlatform.class);
        when(platform.registries()).thenReturn(registries);
        IrisPlatforms.bind(platform);
    }

    @After
    public void unbindPlatform() {
        IrisPlatforms.unbind();
    }

    @Test
    public void enlargedTilesFollowEveryExpandedBlockAndCloneProperties() {
        IrisObject source = new IrisObject(1, 1, 1);
        TileData original = tile("minecraft:chest", "saved inventory");
        source.setUnsigned(0, 0, 0, state("minecraft:chest"));
        source.setUnsignedTile(0, 0, 0, original);

        IrisObject scaled = source.scaled(2, IrisObjectPlacementScaleInterpolator.NONE);

        assertEquals(8, scaled.getBlocks().size());
        assertEquals(8, scaled.getStates().size());
        Set<TileData> copies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<IrisBlockVector, PlatformBlockState> block : scaled.getBlocks()) {
            TileData copy = scaled.getStates().get(block.getKey());
            assertNotNull(copy);
            assertNotSame(original, copy);
            assertNotSame(original.getProperties(), copy.getProperties());
            assertEquals(original, copy);
            assertTrue(copies.add(copy));
        }
    }

    @Test
    public void reducedTilesStayWithTheWinningBlocks() {
        IrisObject source = new IrisObject(7, 1, 1);
        for (int x = 0; x < 7; x++) {
            String material = x % 2 == 0 ? "minecraft:chest" : "minecraft:barrel";
            source.setUnsigned(x, 0, 0, state(material));
            source.setUnsignedTile(x, 0, 0, tile(material, material));
        }

        IrisObject scaled = source.scaled(0.5, IrisObjectPlacementScaleInterpolator.NONE);

        assertTrue(scaled.getBlocks().size() < source.getBlocks().size());
        assertEquals(scaled.getBlocks().size(), scaled.getStates().size());
        for (Map.Entry<IrisBlockVector, PlatformBlockState> block : scaled.getBlocks()) {
            TileData copy = scaled.getStates().get(block.getKey());
            assertNotNull(copy);
            assertEquals(block.getValue().materialKey(), copy.getMaterialKey());
            assertEquals(block.getValue().materialKey(), copy.getProperties().get("name"));
        }
    }

    @Test
    public void interpolatedTilesOnlyRemainOnTheirMaterial() {
        IrisObject source = new IrisObject(3, 3, 3);
        source.setUnsigned(1, 1, 1, state("minecraft:chest"));
        source.setUnsignedTile(1, 1, 1, tile("minecraft:chest", "saved inventory"));
        source.setUnsigned(2, 1, 1, state("minecraft:stone"));
        for (IrisObjectPlacementScaleInterpolator interpolation : new IrisObjectPlacementScaleInterpolator[] {
                IrisObjectPlacementScaleInterpolator.TRILINEAR,
                IrisObjectPlacementScaleInterpolator.TRICUBIC,
                IrisObjectPlacementScaleInterpolator.TRIHERMITE
        }) {
            IrisObject scaled = source.scaled(2, interpolation);

            assertFalse(scaled.getStates().isEmpty());
            for (Map.Entry<IrisBlockVector, TileData> tile : scaled.getStates()) {
                PlatformBlockState block = scaled.getBlocks().get(tile.getKey());
                assertNotNull(block);
                assertEquals(tile.getValue().getMaterialKey(), block.materialKey());
            }
        }
    }

    @Test
    public void incompatibleSourceTileDoesNotSurviveScaling() {
        IrisObject source = new IrisObject(1, 1, 1);
        source.setUnsigned(0, 0, 0, state("minecraft:stone"));
        source.setUnsignedTile(0, 0, 0, tile("minecraft:chest", "saved inventory"));

        IrisObject scaled = source.scaled(2, IrisObjectPlacementScaleInterpolator.NONE);

        assertTrue(scaled.getStates().isEmpty());
        assertEquals(1, source.getStates().size());
    }

    @Test
    public void enlargedAsymmetricObjectKeepsItsSavedOrigin() {
        IrisObject source = asymmetricObject();

        IrisObject scaled = source.scaledAroundOrigin(2, IrisObjectPlacementScaleInterpolator.NONE);

        assertEquals(new Vector3i(10, 2, 4), scaled.getCenter());
        assertEquals(14, scaled.getW());
        assertEquals(10, scaled.getH());
        assertEquals(6, scaled.getD());
        assertEquals("minecraft:chest", scaled.getBlocks().get(new IrisBlockVector(0, 0, 0)).materialKey());
        assertEquals("minecraft:chest", scaled.getBlocks().get(new IrisBlockVector(1, 1, 1)).materialKey());
        assertEquals("minecraft:stone", scaled.getBlocks().get(new IrisBlockVector(-8, 0, 0)).materialKey());
        assertEquals("minecraft:barrel", scaled.getBlocks().get(new IrisBlockVector(2, 4, 0)).materialKey());
        assertEquals("origin", scaled.getStates().get(new IrisBlockVector(0, 0, 0)).getProperties().get("name"));
        assertEquals(-10, scaled.getAABB().min().getX());
        assertEquals(3, scaled.getAABB().max().getX());
        assertEquals(new Vector3i(5, 1, 2), source.getCenter());
    }

    @Test
    public void reducedAsymmetricObjectKeepsItsSavedOriginAndTiles() {
        IrisObject source = asymmetricObject();

        IrisObject scaled = source.scaledAroundOrigin(0.5, IrisObjectPlacementScaleInterpolator.NONE);

        assertEquals("minecraft:chest", scaled.getBlocks().get(new IrisBlockVector(0, 0, 0)).materialKey());
        assertEquals("minecraft:stone", scaled.getBlocks().get(new IrisBlockVector(-2, 0, 0)).materialKey());
        assertEquals("minecraft:barrel", scaled.getBlocks().get(new IrisBlockVector(0, 1, 0)).materialKey());
        assertEquals("origin", scaled.getStates().get(new IrisBlockVector(0, 0, 0)).getProperties().get("name"));
        assertEquals(3, scaled.getBlocks().size());
        assertEquals(2, scaled.getStates().size());
    }

    @Test
    public void fractionalEnlargementScalesBothSidesOfTheSavedOrigin() {
        IrisObject source = new IrisObject(5, 1, 1);
        source.setUnsigned(0, 0, 0, state("minecraft:stone"));
        source.setUnsigned(2, 0, 0, state("minecraft:chest"));
        source.setUnsigned(4, 0, 0, state("minecraft:barrel"));

        IrisObject scaled = source.scaledAroundOrigin(1.5, IrisObjectPlacementScaleInterpolator.NONE);

        assertEquals("minecraft:stone", scaled.getBlocks().get(new IrisBlockVector(-3, 0, 0)).materialKey());
        assertEquals("minecraft:stone", scaled.getBlocks().get(new IrisBlockVector(-2, 0, 0)).materialKey());
        assertEquals("minecraft:chest", scaled.getBlocks().get(new IrisBlockVector(0, 0, 0)).materialKey());
        assertEquals("minecraft:barrel", scaled.getBlocks().get(new IrisBlockVector(3, 0, 0)).materialKey());
        assertEquals("minecraft:barrel", scaled.getBlocks().get(new IrisBlockVector(4, 0, 0)).materialKey());
    }

    @Test
    public void untypedTilesRemainAttachedToTheirScaledSourceMaterial() {
        IrisObject source = new IrisObject(1, 1, 1);
        TileData tile = mock(TileData.class);
        when(tile.clone()).thenReturn(tile);
        source.setUnsigned(0, 0, 0, state("minecraft:chest"));
        source.setUnsignedTile(0, 0, 0, tile);

        IrisObject scaled = source.scaledAroundOrigin(2, IrisObjectPlacementScaleInterpolator.NONE);

        assertEquals(8, scaled.getStates().size());
    }

    private static IrisObject asymmetricObject() {
        IrisObject source = new IrisObject(7, 5, 3);
        source.setCenter(new Vector3i(5, 1, 2));
        source.setUnsigned(5, 1, 2, state("minecraft:chest"));
        source.setUnsignedTile(5, 1, 2, tile("minecraft:chest", "origin"));
        source.setUnsigned(1, 1, 2, state("minecraft:stone"));
        source.setUnsigned(6, 3, 2, state("minecraft:barrel"));
        source.setUnsignedTile(6, 3, 2, tile("minecraft:barrel", "upper"));
        return source;
    }

    private static TileData tile(String material, String name) {
        KMap<String, Object> properties = new KMap<>();
        properties.put("name", name);
        return new TileData(material, properties);
    }

    private static PlatformBlockState state(String key) {
        PlatformBlockState state = mock(PlatformBlockState.class);
        when(state.key()).thenReturn(key);
        when(state.materialKey()).thenReturn(key);
        when(state.isAir()).thenReturn(key.contains("air"));
        return state;
    }
}
