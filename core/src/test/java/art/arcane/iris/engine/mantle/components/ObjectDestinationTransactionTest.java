package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.core.link.Identifier;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveAction;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveCell;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ObjectDestinationTransactionTest {
    @Test
    public void overlayReadsEarlierWritesAndCommitsOnlyDestinationChunk() {
        MantleWriter writer = writer();
        Marker prerequisite = new Marker("prerequisite");
        Marker outside = new Marker("outside");
        Marker first = new Marker("first");
        Marker second = new Marker("second");
        when(writer.getPrerequisiteDataIfPresent(0, 4, 0, Marker.class)).thenReturn(prerequisite);
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer, 0, 0);

        assertSame(prerequisite, transaction.getDataIfPresent(0, 4, 0, Marker.class));
        transaction.setData(-1, 4, 0, outside);
        transaction.setData(0, 4, 0, first);
        transaction.setData(0, 4, 0, second);
        assertSame(outside, transaction.getDataIfPresent(-1, 4, 0, Marker.class));
        assertSame(second, transaction.getDataIfPresent(0, 4, 0, Marker.class));

        transaction.commit();

        verify(writer, never()).setData(-1, 4, 0, outside);
        InOrder order = inOrder(writer);
        order.verify(writer).setData(0, 4, 0, first);
        order.verify(writer).setData(0, 4, 0, second);
    }

    @Test
    public void sourcePlanCapturesOnlyTheSourceMutationTail() {
        MantleWriter writer = writer();
        Marker predecessor = new Marker("predecessor");
        Marker source = new Marker("source");
        ObjectDestinationTransaction scratch = new ObjectDestinationTransaction(writer, 0, 0);
        scratch.setData(0, 4, 0, predecessor);
        int checkpoint = scratch.mutationCheckpoint();
        scratch.setData(1, 4, 0, source);
        ObjectSourcePlan plan = scratch.sourcePlanSince(checkpoint);
        ObjectDestinationTransaction destination = new ObjectDestinationTransaction(writer, 0, 0);

        destination.apply(plan);

        assertNull(destination.getDataIfPresent(0, 4, 0, Marker.class));
        assertSame(source, destination.getDataIfPresent(1, 4, 0, Marker.class));
        destination.commit();
        verify(writer, never()).setData(0, 4, 0, predecessor);
        verify(writer).setData(1, 4, 0, source);
    }

    @Test
    public void sourcePlanRejectsAnInvalidCheckpoint() {
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer(), 0, 0);

        assertThrows(IllegalArgumentException.class, () -> transaction.sourcePlanSince(1));
    }

    @Test
    public void carvedColumnComposesPrerequisiteHydrologyAndEarlierOriginWrites() {
        MantleWriter writer = writer();
        byte[] prerequisite = new byte[8];
        prerequisite[4] = 1;
        when(writer.getPrerequisiteCarvedColumn(0, 0, 8)).thenReturn(prerequisite);
        when(writer.isPrerequisiteCarved(0, 4, 0)).thenReturn(true);
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer, 0, 0);
        HydrologyCaveCell sealed = HydrologyCaveCell.of(HydrologyCaveAction.SEAL_GUARD);
        MatterCavern cavern = new MatterCavern(true, "", (byte) 0);

        transaction.setData(0, 4, 0, sealed);
        transaction.setData(0, 5, 0, cavern);

        byte[] expected = new byte[8];
        expected[5] = 1;
        assertArrayEquals(expected, transaction.getCarvedColumn(0, 0, 8));
        assertFalse(transaction.isCarved(0, 4, 0));
        assertTrue(transaction.isCarved(0, 5, 0));
        assertArrayEquals(new byte[]{0, 0}, transaction.getCarvedColumn(0, 0, 2));
    }

    @Test
    public void failedCommitRestoresEveryTouchedDestinationCell() {
        MantleWriter writer = writer();
        Marker firstOriginal = new Marker("first-original");
        Marker secondOriginal = new Marker("second-original");
        Marker first = new Marker("first");
        Marker second = new Marker("second");
        when(writer.getPrerequisiteDataIfPresent(0, 4, 0, Marker.class)).thenReturn(firstOriginal);
        when(writer.getPrerequisiteDataIfPresent(1, 4, 0, Marker.class)).thenReturn(secondOriginal);
        doThrow(new IllegalStateException("publication failed"))
                .when(writer).setData(1, 4, 0, second);
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer, 0, 0);
        transaction.setData(0, 4, 0, first);
        transaction.setData(1, 4, 0, second);

        assertThrows(IllegalStateException.class, transaction::commit);

        verify(writer).clearData(0, 4, 0, Marker.class);
        verify(writer).clearData(1, 4, 0, Marker.class);
        verify(writer).setData(0, 4, 0, firstOriginal);
        verify(writer).setData(1, 4, 0, secondOriginal);
    }

    @Test
    public void rejectedWriterMutationsNeverEnterTheOverlay() {
        MantleWriter writer = writer();
        PlatformBlockState prerequisite = mock(PlatformBlockState.class);
        PlatformBlockState rejected = mock(PlatformBlockState.class);
        MatterCavern cavern = new MatterCavern(true, "", (byte) 0);
        when(writer.getPrerequisiteBlock(0, 0, 0)).thenReturn(prerequisite);
        when(writer.getEngine().getDimension().isBedrock()).thenReturn(true);
        when(writer.getPrerequisiteDataIfPresent(1, 4, 0, HydrologyCaveCell.class))
                .thenReturn(HydrologyCaveCell.of(HydrologyCaveAction.SEAL_GUARD));
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer, 0, 0);

        transaction.setData(0, 0, 0, rejected);
        transaction.setData(1, 4, 0, cavern);

        assertSame(prerequisite, transaction.get(0, 0, 0));
        assertFalse(transaction.isCarved(1, 4, 0));
        transaction.commit();
        verify(writer, never()).setData(0, 0, 0, rejected);
        verify(writer, never()).setData(1, 4, 0, cavern);
    }

    @Test
    public void acceptedCustomPlacementPublishesBlockAndIdentifierAsOneMutation() {
        MantleWriter writer = writer();
        PlatformBlockState custom = mock(PlatformBlockState.class);
        PlatformBlockState base = mock(PlatformBlockState.class);
        Identifier identifier = Identifier.fromString("iris:custom_block");
        when(custom.isCustom()).thenReturn(true);
        when(custom.deferredPlacementKey()).thenReturn(identifier.toString());
        when(custom.placementBaseState()).thenReturn(base);
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer, 0, 0);

        transaction.set(1, 4, 2, custom);

        assertSame(base, transaction.get(1, 4, 2));
        assertEquals(identifier, transaction.getDataIfPresent(1, 4, 2, Identifier.class));
        transaction.commit();
        verify(writer).set(1, 4, 2, custom);
        verify(writer, never()).setData(1, 4, 2, base);
        verify(writer, never()).setData(1, 4, 2, identifier);
    }

    @Test
    public void rejectedCustomPlacementStagesNeitherBlockNorIdentifier() {
        MantleWriter writer = writer();
        PlatformBlockState custom = mock(PlatformBlockState.class);
        PlatformBlockState base = mock(PlatformBlockState.class);
        when(custom.isCustom()).thenReturn(true);
        when(custom.deferredPlacementKey()).thenReturn("iris:custom_block");
        when(custom.placementBaseState()).thenReturn(base);
        when(writer.getEngine().getDimension().isBedrock()).thenReturn(true);
        when(writer.getPrerequisiteDataIfPresent(1, 4, 0, HydrologyCaveCell.class))
                .thenReturn(HydrologyCaveCell.of(HydrologyCaveAction.SEAL_GUARD));
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer, 0, 0);

        transaction.set(0, 0, 0, custom);
        transaction.set(1, 4, 0, custom);
        transaction.set(2, 64, 0, custom);

        assertNull(transaction.getDataIfPresent(0, 0, 0, Identifier.class));
        assertNull(transaction.getDataIfPresent(1, 4, 0, Identifier.class));
        assertNull(transaction.getDataIfPresent(2, 64, 0, Identifier.class));
        transaction.commit();
        verify(writer, never()).set(anyInt(), anyInt(), anyInt(), any(PlatformBlockState.class));
        verify(writer, never()).setData(anyInt(), anyInt(), anyInt(), any(Identifier.class));
    }

    @Test
    public void blockReplacementClearsOverlayIdentifierAndRestoresItAfterFailure() {
        MantleWriter writer = writer();
        PlatformBlockState block = mock(PlatformBlockState.class);
        Marker failure = new Marker("failure");
        Identifier originalIdentifier = Identifier.fromString("iris:deferred");
        when(writer.getPrerequisiteDataIfPresent(0, 4, 0, Identifier.class)).thenReturn(originalIdentifier);
        doThrow(new IllegalStateException("publication failed"))
                .when(writer).setData(1, 4, 0, failure);
        ObjectDestinationTransaction transaction = new ObjectDestinationTransaction(writer, 0, 0);

        transaction.setData(0, 4, 0, block);
        assertNull(transaction.getDataIfPresent(0, 4, 0, Identifier.class));
        transaction.setData(1, 4, 0, failure);

        assertThrows(IllegalStateException.class, transaction::commit);
        verify(writer).clearData(0, 4, 0, Identifier.class);
        verify(writer).setData(0, 4, 0, originalIdentifier);
    }

    @SuppressWarnings("unchecked")
    private static MantleWriter writer() {
        MantleWriter writer = mock(MantleWriter.class);
        Mantle<Matter> mantle = mock(Mantle.class);
        Engine engine = mock(Engine.class);
        IrisDimension dimension = mock(IrisDimension.class);
        when(mantle.getWorldHeight()).thenReturn(64);
        when(writer.getMantle()).thenReturn(mantle);
        when(writer.getEngine()).thenReturn(engine);
        when(engine.getDimension()).thenReturn(dimension);
        when(writer.getPrerequisiteCarvedColumn(anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> new byte[invocation.getArgument(2)]);
        when(writer.getPrerequisiteDataIfPresent(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(null);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(2);
            task.run();
            return null;
        }).when(writer).withChunkFence(anyInt(), anyInt(), any(Runnable.class));
        return writer;
    }

    private record Marker(String value) {
    }
}
