package art.arcane.iris.engine.hydrology.runtime;

@FunctionalInterface
public interface IrisHydrologyNaturalOceanClassifier {
    boolean isOcean(int blockX, int blockZ);
}
