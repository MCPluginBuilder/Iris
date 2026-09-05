package art.arcane.iris.engine.mantle;

import art.arcane.iris.util.project.matter.IrisMatterSupport;
import art.arcane.iris.util.project.matter.PreObjectMatterCell;
import art.arcane.iris.util.project.matter.slices.PreObjectMatterTest;
import art.arcane.volmlib.util.function.Consumer4;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.IrisMatter;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TerrainMatterViewTest {
    @BeforeClass
    public static void registerMatter() {
        PreObjectMatterTest.setUpBukkit();
        IrisMatterSupport.ensureRegistered();
    }

    @Test
    public void persistedJournalRestoresDeletedAndReplacedTerrainAndExcludesNewContent() throws IOException {
        Matter matter = new IrisMatter(16, 16, 16);
        MatterCavern natural = new MatterCavern(true, "iris:natural", (byte) 0);
        MatterCavern content = new MatterCavern(true, "iris:object", (byte) 0);
        matter.<MatterCavern>slice(MatterCavern.class).set(1, 3, 2, content);
        matter.<MatterCavern>slice(MatterCavern.class).set(2, 3, 2, content);
        matter.<MatterCavern>slice(MatterCavern.class).set(4, 3, 2, natural);
        matter.<PreObjectMatterCell>slice(PreObjectMatterCell.class).set(1, 3, 2, PreObjectMatterCell.cavern(null));
        matter.<PreObjectMatterCell>slice(PreObjectMatterCell.class).set(2, 3, 2, PreObjectMatterCell.cavern(natural));
        matter.<PreObjectMatterCell>slice(PreObjectMatterCell.class).set(3, 3, 2, PreObjectMatterCell.cavern(natural));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        matter.write(bytes);
        Matter restored = Matter.read(new ByteArrayInputStream(bytes.toByteArray()));
        MantleChunk<Matter> chunk = chunk(restored);
        Map<Integer, MatterCavern> values = new HashMap<>();
        TerrainMatterView.iterate(chunk, MatterCavern.class, (x, y, z, cavern) -> {
            assertEquals(3, y.intValue());
            assertEquals(2, z.intValue());
            assertNull(values.put(x, cavern));
        });

        assertEquals(Map.of(2, natural, 3, natural, 4, natural), values);
        assertNull(TerrainMatterView.get(chunk, 1, 3, 2, MatterCavern.class));
        assertEquals(natural, TerrainMatterView.get(chunk, 3, 3, 2, MatterCavern.class));
    }

    @SuppressWarnings("unchecked")
    private static MantleChunk<Matter> chunk(Matter matter) {
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        when(chunk.exists(0)).thenReturn(true);
        when(chunk.get(0)).thenReturn(matter);
        doAnswer(invocation -> {
            Class<Object> type = invocation.getArgument(0);
            Consumer4<Integer, Integer, Integer, Object> consumer = invocation.getArgument(1);
            if (!matter.hasSlice(type)) {
                return null;
            }
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        Object value = matter.getSlice(type).get(x, y, z);
                        if (value != null) {
                            consumer.accept(x, y, z, value);
                        }
                    }
                }
            }
            return null;
        }).when(chunk).iterate(any(), any());
        return chunk;
    }
}
