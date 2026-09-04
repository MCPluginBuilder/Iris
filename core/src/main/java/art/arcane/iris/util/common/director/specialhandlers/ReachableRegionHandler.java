package art.arcane.iris.util.common.director.specialhandlers;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.history.GenerationFindCatalog;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.volmlib.util.collection.KList;

public class ReachableRegionHandler extends RegistrantHandler<IrisRegion> {
    public ReachableRegionHandler() {
        super(IrisRegion.class, true);
    }

    @Override
    public KList<IrisRegion> getPossibilities() {
        Engine activeEngine = engine();
        return activeEngine == null ? super.getPossibilities() : GenerationFindCatalog.regions(activeEngine);
    }

    @Override
    public String getRandomDefault() {
        return "region";
    }
}
