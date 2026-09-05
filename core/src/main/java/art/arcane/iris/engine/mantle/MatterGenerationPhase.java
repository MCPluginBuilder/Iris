package art.arcane.iris.engine.mantle;

public enum MatterGenerationPhase {
    ALL,
    TERRAIN,
    CONTENT;

    public boolean includes(MantleComponent component) {
        return this == ALL || component.getGenerationPhase() == this;
    }
}
