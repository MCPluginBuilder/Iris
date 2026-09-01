package art.arcane.iris.util.common.director.specialhandlers;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.hydrology.HydrologyFeatureQuery;
import art.arcane.iris.engine.hydrology.runtime.IrisHydrologyRuntime;
import art.arcane.iris.util.common.director.DirectorParameterHandler;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;

import java.util.List;

public final class HydrologyTypeHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
        Engine activeEngine = engine();
        if (activeEngine == null) {
            return new KList<>(HydrologyFeatureQuery.suggestions(List.of()));
        }
        IrisHydrologyRuntime runtime = activeEngine.getComplex().getHydrologyRuntime();
        return new KList<>(runtime == null
                ? HydrologyFeatureQuery.suggestions(List.of())
                : runtime.featureQueryKeys());
    }

    @Override
    public String toString(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String parse(String input, boolean force) throws DirectorParsingException {
        String normalized = input == null ? "" : input.trim();
        if (normalized.isEmpty()) {
            throw new DirectorParsingException("Hydrology feature type must not be blank.");
        }
        for (String possibility : getPossibilities(normalized)) {
            if (possibility.equalsIgnoreCase(normalized)) {
                return possibility;
            }
        }
        return normalized;
    }

    @Override
    public boolean supports(Class<?> type) {
        return type == String.class;
    }

    @Override
    public String getRandomDefault() {
        return "surface";
    }
}
