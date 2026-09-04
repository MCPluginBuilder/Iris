package art.arcane.iris.engine.actuator;

import art.arcane.iris.engine.hydrology.cave.HydrologyCaveCell;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterSlice;
import org.junit.Test;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IrisDimensionStackActuatorMetadataTest {
    @Test
    @SuppressWarnings("unchecked")
    public void overwrittenStackCellsClearHostHydrologyCaveOwnership() {
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        Matter section = mock(Matter.class);
        MatterSlice<HydrologyCaveCell> hydrology = mock(MatterSlice.class);
        when(chunk.get(1)).thenReturn(section);
        doReturn(hydrology).when(section).getSlice(HydrologyCaveCell.class);

        IrisDimensionStackActuator.MetadataCleaner cleaner =
                new IrisDimensionStackActuator.MetadataCleaner(chunk, 64);
        cleaner.clear(3, 20, 7);

        verify(hydrology).set(3, 4, 7, null);
    }
}
