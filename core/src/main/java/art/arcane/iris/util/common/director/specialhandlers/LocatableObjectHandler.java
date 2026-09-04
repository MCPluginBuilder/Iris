package art.arcane.iris.util.common.director.specialhandlers;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.history.GenerationFindCatalog;
import art.arcane.volmlib.util.collection.KList;

public class LocatableObjectHandler extends ObjectHandler {
    @Override
    public KList<String> getPossibilities() {
        Engine activeEngine = engine();
        return activeEngine == null ? super.getPossibilities() : GenerationFindCatalog.objectKeys(activeEngine);
    }
}
