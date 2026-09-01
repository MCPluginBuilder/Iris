package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls the wet channel of a surface river.")
@Data
public class IrisSurfaceRiverChannelConfig {
    @Desc("Wet channel width in blocks.")
    private IrisStyledRange width = range(4D, 8D, 1024D);

    @Desc("Wet bed depth in blocks at the channel center.")
    private IrisStyledRange depth = range(2D, 4D, 768D);

    @MinNumber(1)
    @MaxNumber(16)
    @Desc("Blocks the water surface sits below the lowest natural bank beside the channel.")
    private int inset = 1;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Maximum cut below natural terrain at the channel center before a course is rejected.")
    private int maximumIncision = 10;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Coherent variation of the wet outline and bed as a fraction of the channel size.")
    private double roughness = 0.25D;

    @MinNumber(3)
    @MaxNumber(128)
    @Desc("Wavelength in blocks of the outline and bed variation.")
    private int roughnessWavelength = 16;

    private static IrisStyledRange range(double min, double max, double zoom) {
        IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom);
        return new IrisStyledRange(min, max, style);
    }
}
