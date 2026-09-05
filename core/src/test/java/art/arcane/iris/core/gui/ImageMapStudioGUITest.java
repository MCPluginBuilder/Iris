package art.arcane.iris.core.gui;

import art.arcane.iris.engine.framework.Engine;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class ImageMapStudioGUITest {
    @Test
    public void exportRequestsHotloadWithoutMutatingTheActiveGenerationData() {
        Engine engine = mock(Engine.class);

        ImageMapStudioGUI.reloadActiveEngine(engine);

        verify(engine).hotloadSilently();
        verifyNoMoreInteractions(engine);
    }
}
