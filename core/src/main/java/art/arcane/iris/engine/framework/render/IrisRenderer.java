/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine.framework.render;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeGeneratorLink;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.runtime.IrisRiverRuntime;
import art.arcane.iris.util.project.stream.ProceduralStream;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class IrisRenderer {
    private static final int BLUE = new Color(45, 91, 156).getRGB();
    private static final int YELLOW = new Color(211, 164, 67).getRGB();
    private static final int GREEN = new Color(78, 137, 83).getRGB();
    private static final int RIVER_CHANNEL = new Color(48, 112, 190).getRGB();
    private static final int RIVER_MOUTH = new Color(54, 164, 205).getRGB();
    private static final int RIVER_BANK = new Color(92, 146, 78).getRGB();
    private static final int DRY_CHANNEL = new Color(171, 128, 68).getRGB();
    private static final int DRY_BANK = new Color(132, 105, 62).getRGB();
    private static final int NO_RIVER = new Color(28, 31, 38).getRGB();
    private static final int DEEP_WATER = new Color(20, 48, 92).getRGB();
    private static final int SHALLOW_WATER = new Color(50, 112, 154).getRGB();
    private static final int LOWLAND = new Color(78, 128, 76).getRGB();
    private static final int HIGHLAND = new Color(151, 139, 92).getRGB();
    private static final int ROCK = new Color(126, 119, 112).getRGB();
    private static final int SNOW = new Color(226, 230, 232).getRGB();

    private final Engine renderer;

    public IrisRenderer(Engine renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public BufferedImage render(double sx, double sz, double size, int resolution, RenderType currentType) {
        return render(sx, sz, size, resolution, currentType, () -> false);
    }

    public BufferedImage render(
            double sx,
            double sz,
            double size,
            int resolution,
            RenderType currentType,
            BooleanSupplier cancelled
    ) {
        return render(sx, sz, size, resolution, currentType, cancelled, false);
    }

    public BufferedImage renderStudio(
            double sx,
            double sz,
            double size,
            int resolution,
            RenderType currentType,
            BooleanSupplier cancelled
    ) {
        return render(sx, sz, size, resolution, currentType, cancelled, true);
    }

    private BufferedImage render(
            double sx,
            double sz,
            double size,
            int resolution,
            RenderType currentType,
            BooleanSupplier cancelled,
            boolean studio
    ) {
        if (!Double.isFinite(sx) || !Double.isFinite(sz) || !Double.isFinite(size)) {
            throw new IllegalArgumentException("Vision render coordinates and size must be finite");
        }
        if (resolution < 1) {
            throw new IllegalArgumentException("Vision render resolution must be positive");
        }
        Objects.requireNonNull(currentType, "currentType");
        Objects.requireNonNull(cancelled, "cancelled");
        checkCancelled(cancelled);

        BufferedImage image = new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        double step = size / resolution;
        PixelShader shader = shader(currentType, step, studio);
        int groupSize = sampleGroup(step, resolution);

        for (int groupZ = 0; groupZ < resolution; groupZ += groupSize) {
            checkCancelled(cancelled);
            int maximumZ = Math.min(resolution, groupZ + groupSize);
            for (int groupX = 0; groupX < resolution; groupX += groupSize) {
                checkCancelled(cancelled);
                int maximumX = Math.min(resolution, groupX + groupSize);
                for (int pixelZ = groupZ; pixelZ < maximumZ; pixelZ++) {
                    double z = sz + step * pixelZ;
                    int row = pixelZ * resolution;
                    for (int pixelX = groupX; pixelX < maximumX; pixelX++) {
                        double x = sx + step * pixelX;
                        pixels[row + pixelX] = shader.color(x, z);
                    }
                }
            }
        }

        return image;
    }

    public static int riverColor(RiverSection section) {
        Objects.requireNonNull(section, "section");
        return switch (section) {
            case CHANNEL -> RIVER_CHANNEL;
            case MOUTH -> RIVER_MOUTH;
            case BANK -> RIVER_BANK;
            case DRY_CHANNEL -> DRY_CHANNEL;
            case DRY_BANK -> DRY_BANK;
            case NONE -> NO_RIVER;
        };
    }

    public static int heightColor(double height, double maximumHeight, double fluidHeight) {
        double boundedMaximum = Math.max(1D, maximumHeight);
        double boundedHeight = clamp(height, 0D, boundedMaximum);
        double boundedFluid = clamp(fluidHeight, 0D, boundedMaximum);
        if (boundedHeight <= boundedFluid && boundedFluid > 0D) {
            return blend(DEEP_WATER, SHALLOW_WATER, boundedHeight / boundedFluid);
        }

        double landRange = Math.max(1D, boundedMaximum - boundedFluid);
        double land = clamp((boundedHeight - boundedFluid) / landRange, 0D, 1D);
        if (land < 0.45D) {
            return blend(LOWLAND, HIGHLAND, land / 0.45D);
        }
        if (land < 0.78D) {
            return blend(HIGHLAND, ROCK, (land - 0.45D) / 0.33D);
        }
        return blend(ROCK, SNOW, (land - 0.78D) / 0.22D);
    }

    static int sampleGroup(double step, int resolution) {
        double absoluteStep = Math.abs(step);
        if (!Double.isFinite(absoluteStep) || absoluteStep <= 0D) {
            return 1;
        }
        return Math.max(1, Math.min(resolution, (int) Math.floor(16D / absoluteStep)));
    }

    private PixelShader shader(RenderType currentType, double step, boolean studio) {
        IrisComplex complex = renderer.getComplex();
        return switch (currentType) {
            case BIOME, DECORATOR_LOAD, OBJECT_LOAD, LAYER_LOAD -> biomeShader(
                    studio ? complex.getNaturalTrueBiomeStream() : complex.getTrueBiomeStream(),
                    currentType
            );
            case BIOME_LAND -> biomeShader(complex.getLandBiomeStream(), currentType);
            case BIOME_SEA -> biomeShader(complex.getSeaBiomeStream(), currentType);
            case REGION -> regionShader(complex, currentType);
            case CAVE_LAND -> biomeShader(complex.getCaveBiomeStream(), currentType);
            case HEIGHT -> heightShader(studio ? complex.getNaturalHeightStream() : complex.getHeightStream());
            case RIVER -> (double x, double z) -> riverColor(complex, x, z, step);
            case CONTINENT -> studio
                    ? continentShader(complex.getNaturalTrueBiomeStream())
                    : this::continentColor;
        };
    }

    private PixelShader biomeShader(ProceduralStream<IrisBiome> stream, RenderType currentType) {
        IdentityHashMap<IrisBiome, Integer> colors = new IdentityHashMap<>();
        return (double x, double z) -> {
            IrisBiome biome = stream.get(x, z);
            Integer color = colors.get(biome);
            if (color == null) {
                color = biome.getColor(renderer, currentType).getRGB();
                colors.put(biome, color);
            }
            return color;
        };
    }

    private PixelShader regionShader(IrisComplex complex, RenderType currentType) {
        ProceduralStream<IrisRegion> stream = complex.getRegionStream();
        IdentityHashMap<IrisRegion, Integer> colors = new IdentityHashMap<>();
        return (double x, double z) -> {
            IrisRegion region = stream.get(x, z);
            Integer color = colors.get(region);
            if (color == null) {
                color = region.getColor(complex, currentType).getRGB();
                colors.put(region, color);
            }
            return color;
        };
    }

    private PixelShader heightShader(ProceduralStream<Double> stream) {
        double maximumHeight = renderer.getHeight();
        IrisDimension dimension = renderer.getDimension();
        double fluidHeight = dimension == null ? 0D : dimension.getFluidHeight();
        return (double x, double z) -> heightColor(stream.getDouble(x, z), maximumHeight, fluidHeight);
    }

    private int riverColor(IrisComplex complex, double x, double z, double step) {
        IrisRiverRuntime runtime = complex.getRiverRuntime();
        if (runtime == null) {
            return riverColor(RiverSection.NONE);
        }
        double endX = x + step;
        double endZ = z + step;
        RiverSample sample = runtime.sampleFootprint(
                StrictMath.min(x, endX),
                StrictMath.min(z, endZ),
                StrictMath.max(x, endX),
                StrictMath.max(z, endZ)
        );
        return riverColor(sample.section());
    }

    private int continentColor(double x, double z) {
        IrisBiome biome = renderer.getBiome(
                (int) Math.round(x),
                renderer.getMaxHeight() - 1,
                (int) Math.round(z)
        );
        return continentColor(biome);
    }

    private PixelShader continentShader(ProceduralStream<IrisBiome> stream) {
        IdentityHashMap<IrisBiome, Integer> colors = new IdentityHashMap<>();
        return (double x, double z) -> {
            IrisBiome biome = stream.get(x, z);
            Integer color = colors.get(biome);
            if (color == null) {
                color = continentColor(biome);
                colors.put(biome, color);
            }
            return color;
        };
    }

    private int continentColor(IrisBiome biome) {
        if (biome == null) {
            return GREEN;
        }
        List<IrisBiomeGeneratorLink> generators = biome.getGenerators();
        if (generators.isEmpty()) {
            return GREEN;
        }
        IrisBiomeGeneratorLink generator = generators.get(0);
        if (generator.getMax() <= 0D) {
            return BLUE;
        }
        if (generator.getMin() < 0D) {
            return YELLOW;
        }
        return GREEN;
    }

    private static int blend(int first, int second, double progress) {
        double bounded = clamp(progress, 0D, 1D);
        int red = (int) Math.round(((first >> 16) & 0xFF) * (1D - bounded) + ((second >> 16) & 0xFF) * bounded);
        int green = (int) Math.round(((first >> 8) & 0xFF) * (1D - bounded) + ((second >> 8) & 0xFF) * bounded);
        int blue = (int) Math.round((first & 0xFF) * (1D - bounded) + (second & 0xFF) * bounded);
        return (red << 16) | (green << 8) | blue;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Vision render cancelled");
        }
    }

    @FunctionalInterface
    private interface PixelShader {
        int color(double x, double z);
    }
}
