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

package art.arcane.iris.engine;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.image.IrisImageMapRuntime;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.data.DataProvider;

public class UpperDimensionContext implements DataProvider {
    private final DimensionTerrainContext terrainContext;
    private final int chunkHeight;

    private UpperDimensionContext(DimensionTerrainContext terrainContext, int chunkHeight) {
        this.terrainContext = terrainContext;
        this.chunkHeight = chunkHeight;
    }

    public static UpperDimensionContext create(Engine engine, IrisDimension upperDimension) {
        return new UpperDimensionContext(
                DimensionTerrainContext.forUpper(engine, upperDimension),
                engine.getHeight()
        );
    }

    static IrisRegion mappedRegion(
            IrisImageMapRuntime imageMapRuntime,
            IrisRegion proceduralRegion,
            double worldX,
            double worldZ
    ) {
        return DimensionTerrainContext.mappedRegion(
                imageMapRuntime, proceduralRegion, worldX, worldZ);
    }

    static double mappedTerrainHeight(
            IrisImageMapRuntime imageMapRuntime,
            double proceduralHeight,
            double worldX,
            double worldZ
    ) {
        return DimensionTerrainContext.mappedTerrainHeight(
                imageMapRuntime, proceduralHeight, worldX, worldZ);
    }

    static IrisBiome mappedBiome(
            IrisImageMapRuntime imageMapRuntime,
            IrisBiome proceduralBiome,
            double worldX,
            double worldZ
    ) {
        return DimensionTerrainContext.mappedBiome(
                imageMapRuntime, proceduralBiome, worldX, worldZ);
    }

    static PlatformBlockState mappedSurfaceBlock(
            IrisImageMapRuntime imageMapRuntime,
            PlatformBlockState proceduralBlock,
            double worldX,
            double worldZ
    ) {
        return DimensionTerrainContext.mappedSurfaceBlock(
                imageMapRuntime, proceduralBlock, worldX, worldZ);
    }

    public int getUpperSurfaceY(int x, int z) {
        return chunkHeight - 1 - (int) Math.round(terrainContext.getNormalTerrainHeight(x, z));
    }

    public IrisBiome getUpperBiome(int x, int z) {
        return terrainContext.getBiome(x, z);
    }

    public IrisRegion getUpperRegion(int x, int z) {
        return terrainContext.getRegion(x, z);
    }

    public PlatformBlockState getRockBlock(int x, int z) {
        return terrainContext.getRockBlock(x, z);
    }

    public PlatformBlockState getSurfaceBlock(int x, int z) {
        return terrainContext.getSurfaceBlock(x, z);
    }

    public IrisDimension getDimension() {
        return terrainContext.getDimension();
    }

    @Override
    public IrisData getData() {
        return terrainContext.getData();
    }

    public boolean isSelfReferencing() {
        return terrainContext.isSelfReferencing();
    }
}
