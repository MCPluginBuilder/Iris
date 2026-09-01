package art.arcane.iris.core.commands;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.util.common.director.specialhandlers.ReachableBiomeHandler;
import art.arcane.volmlib.util.director.annotations.Param;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class CommandFindBiomeContractTest {
    @Test
    public void biomeLookupUsesReachableBiomeHandler() throws Exception {
        Method method = CommandFind.class.getMethod("biome", IrisBiome.class, boolean.class);
        Param parameter = method.getParameters()[0].getAnnotation(Param.class);

        assertEquals(ReachableBiomeHandler.class, parameter.customHandler());
    }
}
