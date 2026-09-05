package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.hydrology.cave.CavePosition;
import art.arcane.iris.engine.hydrology.cave.CaveVoxel;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MantleHydrologyCaveVoxelViewTest {
    @Test
    @SuppressWarnings("unchecked")
    public void authoritativeChunkFlagControlsWhetherCarvingInputIsRequired() {
        Mantle<Matter> mantle = mock(Mantle.class);
        doReturn(mock(MantleChunk.class)).when(mantle).getChunk(anyInt(), anyInt());
        when(mantle.hasFlag(2, -3, ReservedFlag.CARVED)).thenReturn(true);

        assertFalse(MantleHydrologyCaveVoxelView.requiresCarvingInput(mantle, 2, -3));
        assertTrue(MantleHydrologyCaveVoxelView.requiresCarvingInput(mantle, 3, -3));

        verify(mantle).hasFlag(2, -3, ReservedFlag.CARVED);
        verify(mantle).hasFlag(3, -3, ReservedFlag.CARVED);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void mapsCarvedAndStoredMatterAndLoadsEachChunkOnce() {
        Mantle<Matter> mantle = mock(Mantle.class);
        doReturn(mock(MantleChunk.class)).when(mantle).getChunk(anyInt(), anyInt());
        MatterCavern air = new MatterCavern(true, "", (byte) 0);
        MatterCavern water = new MatterCavern(true, "", (byte) 1);
        MatterCavern lava = new MatterCavern(true, "", (byte) 2);
        PlatformBlockState incompatibleFluid = mock(PlatformBlockState.class);
        PlatformBlockState lavaBlock = mock(PlatformBlockState.class);
        PlatformBlockState solid = mock(PlatformBlockState.class);
        doReturn(air).when(mantle).get(0, 20, 0, MatterCavern.class);
        doReturn(water).when(mantle).get(1, 20, 0, MatterCavern.class);
        doReturn(lava).when(mantle).get(2, 20, 0, MatterCavern.class);
        doReturn(incompatibleFluid).when(mantle).get(3, 20, 0, PlatformBlockState.class);
        doReturn(lavaBlock).when(mantle).get(4, 20, 0, PlatformBlockState.class);
        doReturn(solid).when(mantle).get(5, 20, 0, PlatformBlockState.class);
        when(incompatibleFluid.isFluid()).thenReturn(true);
        when(incompatibleFluid.materialKey()).thenReturn("minecraft:water");
        when(lavaBlock.isFluid()).thenReturn(true);
        when(lavaBlock.materialKey()).thenReturn("minecraft:lava");
        when(solid.isFluid()).thenReturn(false);
        List<String> loaded = new ArrayList<>();
        MantleHydrologyCaveVoxelView view = new MantleHydrologyCaveVoxelView(
                mantle,
                128,
                (x, z) -> 60,
                (chunkX, chunkZ) -> loaded.add(chunkX + "," + chunkZ)
        );

        assertEquals(CaveVoxel.CAVE_AIR, view.voxelAt(new CavePosition(0, 20, 0)));
        assertEquals(CaveVoxel.COMPATIBLE_FLUID, view.voxelAt(new CavePosition(1, 20, 0)));
        assertEquals(CaveVoxel.LAVA, view.voxelAt(new CavePosition(2, 20, 0)));
        assertEquals(CaveVoxel.INCOMPATIBLE_FLUID, view.voxelAt(new CavePosition(3, 20, 0)));
        assertEquals(CaveVoxel.LAVA, view.voxelAt(new CavePosition(4, 20, 0)));
        assertEquals(CaveVoxel.SOLID, view.voxelAt(new CavePosition(5, 20, 0)));
        assertEquals(CaveVoxel.SOLID, view.voxelAt(new CavePosition(6, 20, 0)));
        assertEquals(CaveVoxel.CAVE_AIR, view.voxelAt(new CavePosition(7, 61, 0)));
        assertTrue(view.isOpenToSurface(new CavePosition(7, 61, 0)));
        assertEquals(CaveVoxel.SOLID, view.voxelAt(new CavePosition(16, 20, 0)));
        assertEquals(CaveVoxel.CAVE_AIR, view.voxelAt(new CavePosition(16, 61, 0)));

        assertEquals(List.of("0,0", "1,0"), loaded);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void carvedVerticalShaftIsOpenToSurface() {
        Mantle<Matter> mantle = mock(Mantle.class);
        doReturn(mock(MantleChunk.class)).when(mantle).getChunk(anyInt(), anyInt());
        MatterCavern air = new MatterCavern(true, "", (byte) 0);
        doAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            return x == 4 && z == 4 && y >= 20 && y <= 60 ? air : null;
        }).when(mantle).get(anyInt(), anyInt(), anyInt(), eq(MatterCavern.class));
        List<String> loaded = new ArrayList<>();
        MantleHydrologyCaveVoxelView view = new MantleHydrologyCaveVoxelView(
                mantle,
                128,
                (x, z) -> 60,
                (chunkX, chunkZ) -> loaded.add(chunkX + "," + chunkZ)
        );

        assertTrue(view.isOpenToSurface(new CavePosition(4, 20, 4)));
        assertFalse(view.isOpenToSurface(new CavePosition(5, 20, 4)));
        assertEquals(List.of("0,0"), loaded);
    }
}
