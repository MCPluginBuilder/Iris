package art.arcane.iris.engine.hydrology.runtime;

@FunctionalInterface
public interface IrisHydrologyNaturalSampleProvider {
    IrisHydrologyNaturalSample sample(int blockX, int blockZ, double naturalHeight);
}
