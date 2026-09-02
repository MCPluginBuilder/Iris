package art.arcane.iris.core.pregenerator;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.Position2;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PregenTaskLatticeOrderTest {
    @Test
    public void regionOrderVisitsEveryChunkOnceInStrideSpacedLattices() {
        KList<Position2> order = new KList<>();
        PregenTask.iterateRegion(0, 0, (x, z) -> order.add(new Position2(x, z)));

        assertEquals(1024, order.size());
        Set<Long> unique = new HashSet<>();
        for (Position2 position : order) {
            assertTrue(unique.add(((long) position.getX() << 32) ^ (position.getZ() & 0xffffffffL)));
        }

        int stride = PregenTask.CHUNK_LATTICE_STRIDE;
        int perLattice = 1024 / (stride * stride);
        for (int lattice = 0; lattice < stride * stride; lattice++) {
            Position2 first = order.get(lattice * perLattice);
            for (int index = lattice * perLattice; index < (lattice + 1) * perLattice; index++) {
                Position2 position = order.get(index);
                assertEquals(Math.floorMod(first.getX(), stride), Math.floorMod(position.getX(), stride));
                assertEquals(Math.floorMod(first.getZ(), stride), Math.floorMod(position.getZ(), stride));
            }
        }
    }

    @Test
    public void chunksGeneratedBackToBackNeverShareAMantleWindow() {
        KList<Position2> order = new KList<>();
        PregenTask.iterateRegion(3, -2, (x, z) -> order.add(new Position2(x, z)));

        int perLattice = 1024 / (PregenTask.CHUNK_LATTICE_STRIDE * PregenTask.CHUNK_LATTICE_STRIDE);
        for (int index = 1; index < perLattice; index++) {
            Position2 previous = order.get(index - 1);
            Position2 current = order.get(index);
            int distance = Math.max(Math.abs(previous.getX() - current.getX()), Math.abs(previous.getZ() - current.getZ()));
            assertTrue("consecutive chunks " + previous + " and " + current, distance >= PregenTask.CHUNK_LATTICE_STRIDE);
        }
    }
}
