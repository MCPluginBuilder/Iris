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

    @MinNumber(0)
    @MaxNumber(3)
    @Desc("Blocks the water surface sinks below the lowest natural ground beside the channel; 0 keeps the water flush with the bank, and the bank always meets the water at its own height.")
    private int sink = 0;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Maximum cut below natural terrain at the channel center before a course is rejected.")
    private int maximumIncision = 10;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Coherent variation of the wet outline and bed as a fraction of the channel size.")
    private double roughness = 0.25D;

    @MinNumber(4)
    @MaxNumber(64)
    @Desc("Wavelength in blocks of the outline and bed variation.")
    private int roughnessWavelength = 16;

    @MinNumber(1)
    @MaxNumber(4)
    @Desc("Width of the spring pool at the headwater relative to the channel width; 1 starts the river at its normal width.")
    private double springWidthRatio = 2.5D;

    @MinNumber(4)
    @MaxNumber(96)
    @Desc("Blocks over which the spring pool narrows back to the channel width.")
    private int springLength = 24;

    private static IrisStyledRange range(double min, double max, double zoom) {
        IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom);
        return new IrisStyledRange(min, max, style);
    }
}
