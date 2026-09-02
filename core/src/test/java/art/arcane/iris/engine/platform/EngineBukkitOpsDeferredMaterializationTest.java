package art.arcane.iris.engine.platform;

import art.arcane.iris.core.link.Identifier;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineMetrics;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.util.project.matter.TileWrapper;
import art.arcane.volmlib.util.function.Consumer4;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.mantle.runtime.MantleDataAdapter;
import art.arcane.volmlib.util.mantle.runtime.MantleHooks;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterUpdate;
import art.arcane.volmlib.util.matter.slices.UpdateMatter;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EngineBukkitOpsDeferredMaterializationTest {
    @Test
    @SuppressWarnings("unchecked")
    public void updateMarkerUsesWorldHeightAndDeletesSliceAfterDispatch() {
        MantleChunk<Matter> mantleChunk = mock(MantleChunk.class);
        doAnswer(invocation -> {
            Consumer4<Integer, Integer, Integer, MatterUpdate> iterator = invocation.getArgument(1);
            iterator.accept(-1, 5, -2, UpdateMatter.ON);
            return null;
        }).when(mantleChunk).iterate(eq(MatterUpdate.class), any());
        IrisWorld irisWorld = mock(IrisWorld.class);
        when(irisWorld.minHeight()).thenReturn(-64);
        Engine engine = mock(Engine.class);
        when(engine.getWorld()).thenReturn(irisWorld);
        when(engine.getMetrics()).thenReturn(new EngineMetrics(8));
        Chunk chunk = mock(Chunk.class);
        ArrayList<String> updates = new ArrayList<>();

        EngineBukkitOps.materializeUpdates(
                engine,
                chunk,
                mantleChunk,
                (x, y, z) -> updates.add(x + "," + y + "," + z)
        );

        assertEquals(List.of("-1,-59,-2"), updates);
        verify(mantleChunk).deleteSlices(MatterUpdate.class);
    }

    @Test
    public void fallingFluidUpdateRetainsLevelEightStateAndRequestsPhysics() {
        Block block = mock(Block.class);
        BlockData falling = mock(BlockData.class);

        EngineBukkitOps.applyPhysicsUpdate(block, falling);

        InOrder order = inOrder(block);
        order.verify(block).setType(Material.AIR, false);
        order.verify(block).setBlockData(falling, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tileSliceIsDeletedAfterIteration() {
        MantleChunk<Matter> mantleChunk = mock(MantleChunk.class);

        EngineBukkitOps.materializeTiles(mock(Engine.class), mock(Chunk.class), mantleChunk);

        InOrder order = inOrder(mantleChunk);
        order.verify(mantleChunk).iterate(eq(TileWrapper.class), any());
        order.verify(mantleChunk).deleteSlices(TileWrapper.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failedTileIterationRetainsSlice() {
        MantleChunk<Matter> mantleChunk = mock(MantleChunk.class);
        IllegalStateException failure = new IllegalStateException("tile failure");
        doThrow(failure).when(mantleChunk).iterate(eq(TileWrapper.class), any());

        assertThrows(IllegalStateException.class,
                () -> EngineBukkitOps.materializeTiles(mock(Engine.class), mock(Chunk.class), mantleChunk));

        verify(mantleChunk, never()).deleteSlices(TileWrapper.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void customSliceIsDeletedAfterIteration() {
        MantleChunk<Matter> mantleChunk = mock(MantleChunk.class);

        EngineBukkitOps.materializeCustomBlocks(mock(Engine.class), mock(Chunk.class), mantleChunk);

        InOrder order = inOrder(mantleChunk);
        order.verify(mantleChunk).iterate(eq(Identifier.class), any());
        order.verify(mantleChunk).deleteSlices(Identifier.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failedCustomPassRetrySkipsCompletedTilePass() {
        MantleDataAdapter<Matter> adapter = mock(MantleDataAdapter.class);
        MantleChunk<Matter> chunk = new MantleChunk<>(1, 0, 0, adapter, MantleHooks.NONE);
        AtomicInteger tileRuns = new AtomicInteger();
        AtomicInteger customRuns = new AtomicInteger();
        AtomicInteger updateRuns = new AtomicInteger();
        Runnable tileTask = tileRuns::incrementAndGet;
        Runnable customTask = () -> {
            if (customRuns.incrementAndGet() == 1) {
                throw new IllegalStateException("custom failure");
            }
        };
        Runnable updateTask = updateRuns::incrementAndGet;

        assertThrows(IllegalStateException.class,
                () -> EngineBukkitOps.runMaterializationPasses(chunk, tileTask, customTask, updateTask));

        assertTrue(chunk.isFlagged(MantleFlag.TILE));
        assertFalse(chunk.isFlagged(MantleFlag.CUSTOM));
        assertFalse(chunk.isFlagged(MantleFlag.UPDATE));
        assertFalse(chunk.isFlagged(MantleFlag.ETCHED));

        EngineBukkitOps.runMaterializationPasses(chunk, tileTask, customTask, updateTask);

        assertTrue(chunk.isFlagged(MantleFlag.TILE));
        assertTrue(chunk.isFlagged(MantleFlag.CUSTOM));
        assertTrue(chunk.isFlagged(MantleFlag.UPDATE));
        assertTrue(chunk.isFlagged(MantleFlag.ETCHED));
        assertEquals(1, tileRuns.get());
        assertEquals(2, customRuns.get());
        assertEquals(1, updateRuns.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failedCustomIterationRetainsSlice() {
        MantleChunk<Matter> mantleChunk = mock(MantleChunk.class);
        doThrow(new IllegalStateException("custom failure"))
                .when(mantleChunk).iterate(eq(Identifier.class), any());

        assertThrows(IllegalStateException.class,
                () -> EngineBukkitOps.materializeCustomBlocks(
                        mock(Engine.class),
                        mock(Chunk.class),
                        mantleChunk
                ));

        verify(mantleChunk, never()).deleteSlices(Identifier.class);
    }
}
