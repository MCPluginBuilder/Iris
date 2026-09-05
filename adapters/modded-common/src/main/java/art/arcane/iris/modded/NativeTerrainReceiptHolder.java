package art.arcane.iris.modded;

public interface NativeTerrainReceiptHolder {
    long iris$getStructureActivation();

    void iris$setStructureActivation(long activation);

    byte[] iris$getNaturalTerrain();

    void iris$setNaturalTerrain(byte[] receipt);
}
