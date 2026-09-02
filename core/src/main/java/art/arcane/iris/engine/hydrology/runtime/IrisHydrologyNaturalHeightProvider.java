package art.arcane.iris.engine.hydrology.runtime;

@FunctionalInterface
public interface IrisHydrologyNaturalHeightProvider {
    double sample(int blockX, int blockZ);
}
