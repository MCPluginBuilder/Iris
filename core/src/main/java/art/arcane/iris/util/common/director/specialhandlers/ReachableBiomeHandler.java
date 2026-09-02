package art.arcane.iris.util.common.director.specialhandlers;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.volmlib.util.collection.KList;

public class ReachableBiomeHandler extends RegistrantHandler<IrisBiome> {
    public ReachableBiomeHandler() {
        super(IrisBiome.class, true);
    }

    @Override
    public KList<IrisBiome> getPossibilities() {
        Engine activeEngine = engine();
        return activeEngine == null
                ? super.getPossibilities()
                : activeEngine.getAllBiomes();
    }

    @Override
    public String getRandomDefault() {
        return "biome";
    }
}
