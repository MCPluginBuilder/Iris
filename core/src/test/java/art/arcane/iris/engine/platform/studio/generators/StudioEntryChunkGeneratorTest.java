package art.arcane.iris.engine.platform.studio.generators;

import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.spi.PlatformBlockState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StudioEntryChunkGeneratorTest {
    @Test
    public void generatesFloorAndLightsThroughoutTheBootstrapLobby() throws Exception {
        PlatformBlockState floor = mock(PlatformBlockState.class);
        PlatformBlockState perimeter = mock(PlatformBlockState.class);
        PlatformBlockState light = mock(PlatformBlockState.class);
        TerrainChunk chunk = mock(TerrainChunk.class);
        when(chunk.getMinHeight()).thenReturn(-64);
        when(chunk.getMaxHeight()).thenReturn(320);
        StudioEntryChunkGenerator generator = new StudioEntryChunkGenerator(floor, perimeter, light);

        generator.generateChunk(mock(Engine.class), chunk, 1, -1);

        verify(chunk).setRegion(0, 95, 0, 16, 96, 16, floor);
        verify(chunk).setBlock(3, 95, 3, light);
        verify(chunk).setBlock(12, 95, 3, light);
        verify(chunk).setBlock(3, 95, 12, light);
        verify(chunk).setBlock(12, 95, 12, light);
        verify(chunk, never()).setRegion(0, 95, 0, 16, 96, 1, perimeter);
        verify(chunk, never()).setRegion(0, 95, 15, 16, 96, 16, perimeter);
        verify(chunk, never()).setRegion(0, 95, 1, 1, 96, 15, perimeter);
        verify(chunk, never()).setRegion(15, 95, 1, 16, 96, 15, perimeter);
    }

    @Test
    public void addsPerimeterOnlyToTheOuterLobbyBoundary() throws Exception {
        PlatformBlockState floor = mock(PlatformBlockState.class);
        PlatformBlockState perimeter = mock(PlatformBlockState.class);
        PlatformBlockState light = mock(PlatformBlockState.class);
        TerrainChunk chunk = mock(TerrainChunk.class);
        when(chunk.getMinHeight()).thenReturn(-64);
        when(chunk.getMaxHeight()).thenReturn(320);
        StudioEntryChunkGenerator generator = new StudioEntryChunkGenerator(floor, perimeter, light);

        generator.generateChunk(mock(Engine.class), chunk, -2, 2);

        verify(chunk).setRegion(0, 95, 0, 16, 96, 16, floor);
        verify(chunk).setRegion(0, 95, 15, 16, 96, 16, perimeter);
        verify(chunk).setRegion(0, 95, 1, 1, 96, 15, perimeter);
        verify(chunk, never()).setRegion(0, 95, 0, 16, 96, 1, perimeter);
        verify(chunk, never()).setRegion(15, 95, 1, 16, 96, 15, perimeter);
    }

    @Test
    public void clampsEntryAndPlatformToShortDimensionRanges() {
        assertEquals(96, StudioEntryChunkGenerator.resolveEntryY(-64, 320));
        assertEquals(95, StudioEntryChunkGenerator.resolvePlatformY(-64, 320));
        assertEquals(62, StudioEntryChunkGenerator.resolveEntryY(-64, 64));
        assertEquals(61, StudioEntryChunkGenerator.resolvePlatformY(-64, 64));
        assertEquals(101, StudioEntryChunkGenerator.resolveEntryY(100, 320));
        assertEquals(100, StudioEntryChunkGenerator.resolvePlatformY(100, 320));
    }

    @Test
    public void acceptsRadiusTwoAndRejectsChunksBeyondTheLobby() throws Exception {
        PlatformBlockState block = mock(PlatformBlockState.class);
        TerrainChunk chunk = mock(TerrainChunk.class);
        when(chunk.getMinHeight()).thenReturn(-64);
        when(chunk.getMaxHeight()).thenReturn(320);
        StudioEntryChunkGenerator generator = new StudioEntryChunkGenerator(block, block, block);

        assertTrue(StudioEntryChunkGenerator.isBootstrapChunk(2, -2));
        assertTrue(StudioEntryChunkGenerator.isBootstrapChunk(-2, 2));
        assertFalse(StudioEntryChunkGenerator.isBootstrapChunk(3, 0));
        assertFalse(StudioEntryChunkGenerator.isBootstrapChunk(0, -3));
        generator.generateChunk(mock(Engine.class), chunk, 2, -2);
        assertThrows(IllegalArgumentException.class,
                () -> generator.generateChunk(mock(Engine.class), chunk, 3, 0));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generateChunk(mock(Engine.class), chunk, 0, -3));
    }
}
