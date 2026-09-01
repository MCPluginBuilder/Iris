package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls the accepted surface-river terrain and biome footprint.")
@Data
public class IrisSurfaceRiverChannelConfig {
    @Desc("Wet channel width in blocks.")
    private IrisStyledRange width = range(4D, 8D, 1024D);

    @Desc("Wet bed depth in blocks.")
    private IrisStyledRange depth = range(1D, 4D, 768D, 2D);

    @Desc("Vertical distance from the natural terrain corridor to the solved water surface.")
    private IrisStyledRange surfaceInset = range(3D, 7D, 768D, 2D);

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Maximum permitted channel incision below natural terrain.")
    private int maximumIncision = 6;

    @MinNumber(1)
    @MaxNumber(2)
    @Desc("Narrow biome-selection band immediately outside the wet channel.")
    private double shoreWidth = 2D;

    @Desc("Wider terrain-grading distance outside the shore band.")
    private IrisStyledRange terrainBlendWidth = range(10D, 24D, 1024D);

    private static IrisStyledRange range(double min, double max, double zoom) {
        return range(min, max, zoom, 1D);
    }

    private static IrisStyledRange range(double min, double max, double zoom, double exponent) {
        IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.IRIS)
                .zoomed(zoom)
                .setExponent(exponent);
        return new IrisStyledRange(min, max, style);
    }
}
