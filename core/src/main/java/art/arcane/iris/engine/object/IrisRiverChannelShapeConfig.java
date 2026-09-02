package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls rounded beds and rough, coherent channel walls.")
@Data
public class IrisRiverChannelShapeConfig {
    @MinNumber(1)
    @MaxNumber(6)
    @Desc("Cross-section exponent. Larger values broaden the rounded U-shaped bed.")
    private double bedRoundness = 2.4D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Coherent vertical variation applied to the bed as a fraction of channel depth.")
    private double bedRoughness = 0.28D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Coherent radial variation applied to carved walls and banks.")
    private double wallRoughness = 0.24D;

    @MinNumber(3)
    @MaxNumber(128)
    @Desc("Wavelength in blocks of bed and wall roughness.")
    private int roughnessWavelength = 11;
}
