package art.arcane.iris.util.common.director.specialhandlers;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReachableBiomeHandlerTest {
    @Test
    public void exposesAndParsesOnlyReachableBiomes() throws Exception {
        Engine engine = mock(Engine.class);
        IrisBiome natural = new IrisBiome();
        natural.setLoadKey("natural");
        IrisBiome river = new IrisBiome();
        river.setLoadKey("river");
        KList<IrisBiome> reachable = new KList<>();
        reachable.add(natural);
        reachable.add(river);
        when(engine.getAllBiomes()).thenReturn(reachable);

        TestReachableBiomeHandler handler = new TestReachableBiomeHandler(engine);

        assertEquals(reachable, handler.getPossibilities());
        assertEquals(river, handler.parse("river", false));
        assertThrows(DirectorParsingException.class, () -> handler.parse("unused", false));
    }

    private static final class TestReachableBiomeHandler extends ReachableBiomeHandler {
        private final Engine engine;

        private TestReachableBiomeHandler(Engine engine) {
            this.engine = engine;
        }

        @Override
        public Engine engine() {
            return engine;
        }
    }
}
