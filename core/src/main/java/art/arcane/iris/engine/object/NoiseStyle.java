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

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.iris.util.project.noise.CNGFactory;
import art.arcane.iris.util.project.noise.NoiseType;
import art.arcane.iris.util.project.stream.ProceduralStream;
@Desc("Styles of noise")
public enum NoiseStyle {
    @Desc("White Noise is like static. Useful for block scattering but not terrain.")
    STATIC(rng -> new CNG(rng, NoiseType.WHITE, 1D, 1), NoiseType.WHITE),

    @Desc("White Noise is like static. Useful for block scattering but not terrain.")
    STATIC_BILINEAR(rng -> new CNG(rng, NoiseType.WHITE_BILINEAR, 1D, 1), NoiseType.WHITE_BILINEAR),

    @Desc("White Noise is like static. Useful for block scattering but not terrain.")
    STATIC_BICUBIC(rng -> new CNG(rng, NoiseType.WHITE_BICUBIC, 1D, 1), NoiseType.WHITE_BICUBIC),

    @Desc("White Noise is like static. Useful for block scattering but not terrain.")
    STATIC_HERMITE(rng -> new CNG(rng, NoiseType.WHITE_HERMITE, 1D, 1), NoiseType.WHITE_HERMITE),

    @Desc("Wispy Perlin-looking simplex noise. The 'iris' style noise.")
    IRIS(rng -> CNG.signature(rng)),

    @Desc("Clover Noise")
    CLOVER(rng -> new CNG(rng, NoiseType.CLOVER, 1D, 1).bake(), NoiseType.CLOVER),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_STARCAST_3(rng -> new CNG(rng, NoiseType.CLOVER_STARCAST_3, 1D, 1), NoiseType.CLOVER_STARCAST_3),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_STARCAST_6(rng -> new CNG(rng, NoiseType.CLOVER_STARCAST_6, 1D, 1), NoiseType.CLOVER_STARCAST_6),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_STARCAST_9(rng -> new CNG(rng, NoiseType.CLOVER_STARCAST_9, 1D, 1), NoiseType.CLOVER_STARCAST_9),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_STARCAST_12(rng -> new CNG(rng, NoiseType.CLOVER_STARCAST_12, 1D, 1), NoiseType.CLOVER_STARCAST_12),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_BILINEAR_STARCAST_3(rng -> new CNG(rng, NoiseType.CLOVER_BILINEAR_STARCAST_3, 1D, 1), NoiseType.CLOVER_BILINEAR_STARCAST_3),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_BILINEAR_STARCAST_6(rng -> new CNG(rng, NoiseType.CLOVER_BILINEAR_STARCAST_6, 1D, 1), NoiseType.CLOVER_BILINEAR_STARCAST_6),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_BILINEAR_STARCAST_9(rng -> new CNG(rng, NoiseType.CLOVER_BILINEAR_STARCAST_9, 1D, 1), NoiseType.CLOVER_BILINEAR_STARCAST_9),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_BILINEAR_STARCAST_12(rng -> new CNG(rng, NoiseType.CLOVER_BILINEAR_STARCAST_12, 1D, 1), NoiseType.CLOVER_BILINEAR_STARCAST_12),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_HERMITE_STARCAST_3(rng -> new CNG(rng, NoiseType.CLOVER_HERMITE_STARCAST_3, 1D, 1), NoiseType.CLOVER_HERMITE_STARCAST_3),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_HERMITE_STARCAST_6(rng -> new CNG(rng, NoiseType.CLOVER_HERMITE_STARCAST_6, 1D, 1), NoiseType.CLOVER_HERMITE_STARCAST_6),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_HERMITE_STARCAST_9(rng -> new CNG(rng, NoiseType.CLOVER_HERMITE_STARCAST_9, 1D, 1), NoiseType.CLOVER_HERMITE_STARCAST_9),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_HERMITE_STARCAST_12(rng -> new CNG(rng, NoiseType.CLOVER_HERMITE_STARCAST_12, 1D, 1), NoiseType.CLOVER_HERMITE_STARCAST_12),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_BILINEAR(rng -> new CNG(rng, NoiseType.CLOVER_BILINEAR, 1D, 1), NoiseType.CLOVER_BILINEAR),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_BICUBIC(rng -> new CNG(rng, NoiseType.CLOVER_BICUBIC, 1D, 1), NoiseType.CLOVER_BICUBIC),

    @Desc("Clover noise smoothed with the selected interpolation kernel.")
    CLOVER_HERMITE(rng -> new CNG(rng, NoiseType.CLOVER_HERMITE, 1D, 1), NoiseType.CLOVER_HERMITE),

    @Desc("Vascular noise gets higher as the position nears a cell border.")
    VASCULAR(rng -> new CNG(rng, NoiseType.VASCULAR, 1D, 1), NoiseType.VASCULAR),

    @Desc("It always returns 1.0")
    FLAT(rng -> new CNG(rng, NoiseType.FLAT, 1D, 1), NoiseType.FLAT),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR(rng -> new CNG(rng, NoiseType.CELLULAR, 1D, 1), NoiseType.CELLULAR),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_STARCAST_3(rng -> new CNG(rng, NoiseType.CELLULAR_STARCAST_3, 1D, 1), NoiseType.CELLULAR_STARCAST_3),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_STARCAST_6(rng -> new CNG(rng, NoiseType.CELLULAR_STARCAST_6, 1D, 1), NoiseType.CELLULAR_STARCAST_6),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_STARCAST_9(rng -> new CNG(rng, NoiseType.CELLULAR_STARCAST_9, 1D, 1), NoiseType.CELLULAR_STARCAST_9),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_STARCAST_12(rng -> new CNG(rng, NoiseType.CELLULAR_STARCAST_12, 1D, 1), NoiseType.CELLULAR_STARCAST_12),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_BILINEAR_STARCAST_3(rng -> new CNG(rng, NoiseType.CELLULAR_BILINEAR_STARCAST_3, 1D, 1), NoiseType.CELLULAR_BILINEAR_STARCAST_3),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_BILINEAR_STARCAST_6(rng -> new CNG(rng, NoiseType.CELLULAR_BILINEAR_STARCAST_6, 1D, 1), NoiseType.CELLULAR_BILINEAR_STARCAST_6),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_BILINEAR_STARCAST_9(rng -> new CNG(rng, NoiseType.CELLULAR_BILINEAR_STARCAST_9, 1D, 1), NoiseType.CELLULAR_BILINEAR_STARCAST_9),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_BILINEAR_STARCAST_12(rng -> new CNG(rng, NoiseType.CELLULAR_BILINEAR_STARCAST_12, 1D, 1), NoiseType.CELLULAR_BILINEAR_STARCAST_12),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_HERMITE_STARCAST_3(rng -> new CNG(rng, NoiseType.CELLULAR_HERMITE_STARCAST_3, 1D, 1), NoiseType.CELLULAR_HERMITE_STARCAST_3),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_HERMITE_STARCAST_6(rng -> new CNG(rng, NoiseType.CELLULAR_HERMITE_STARCAST_6, 1D, 1), NoiseType.CELLULAR_HERMITE_STARCAST_6),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_HERMITE_STARCAST_9(rng -> new CNG(rng, NoiseType.CELLULAR_HERMITE_STARCAST_9, 1D, 1), NoiseType.CELLULAR_HERMITE_STARCAST_9),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_HERMITE_STARCAST_12(rng -> new CNG(rng, NoiseType.CELLULAR_HERMITE_STARCAST_12, 1D, 1), NoiseType.CELLULAR_HERMITE_STARCAST_12),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_BILINEAR(rng -> new CNG(rng, NoiseType.CELLULAR_BILINEAR, 1D, 1), NoiseType.CELLULAR_BILINEAR),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_BICUBIC(rng -> new CNG(rng, NoiseType.CELLULAR_BICUBIC, 1D, 1), NoiseType.CELLULAR_BICUBIC),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders.")
    CELLULAR_HERMITE(rng -> new CNG(rng, NoiseType.CELLULAR_HERMITE, 1D, 1), NoiseType.CELLULAR_HERMITE),

    @Desc("Solid regular hexagons colored by a coherent simplex field.")
    HEXAGON(rng -> new CNG(rng, NoiseType.HEXAGON, 1D, 1), NoiseType.HEXAGON),

    @Desc("Recursive contained hexagons with alternating subdivision probabilities and simplex colors.")
    HEX_JAMES(rng -> new CNG(rng, NoiseType.HEX_JAMES, 1D, 1), NoiseType.HEX_JAMES),

    @Desc("Interlocked solid hex cells with per-cell values from a smooth simplex heatmap.")
    HEX_SIMPLEX(rng -> new CNG(rng, NoiseType.HEX_SIMPLEX, 1D, 1), NoiseType.HEX_SIMPLEX),

    @Desc("Finite-depth hexagonal subdivision selected by a seeded field, with coherent simplex colors.")
    HEX_RANDOM_SIZE(rng -> new CNG(rng, NoiseType.HEX_RANDOM_SIZE, 1D, 1), NoiseType.HEX_RANDOM_SIZE),

    @Desc("Finite-depth equilateral Sierpinski triangles colored by simplex heat.")
    SIERPINSKI_TRIANGLE(rng -> new CNG(rng, NoiseType.SIERPINSKI_TRIANGLE, 1D, 1), NoiseType.SIERPINSKI_TRIANGLE),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE(rng -> CNG.signaturePerlin(rng).bake()),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_CELLULAR(rng -> CNG.signaturePerlin(rng, NoiseType.CELLULAR).bake(), NoiseType.CELLULAR),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_CLOVER(rng -> CNG.signaturePerlin(rng, NoiseType.CLOVER).bake(), NoiseType.CLOVER),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_HEXAGON(rng -> CNG.signaturePerlin(rng, NoiseType.HEXAGON).bake(), NoiseType.HEXAGON),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_HEX_JAMES(rng -> CNG.signaturePerlin(rng, NoiseType.HEX_JAMES).bake(), NoiseType.HEX_JAMES),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_HEX_SIMPLEX(rng -> CNG.signaturePerlin(rng, NoiseType.HEX_SIMPLEX).bake(), NoiseType.HEX_SIMPLEX),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_HEX_RANDOM_SIZE(rng -> CNG.signaturePerlin(rng, NoiseType.HEX_RANDOM_SIZE).bake(), NoiseType.HEX_RANDOM_SIZE),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_SIERPINSKI_TRIANGLE(rng -> CNG.signaturePerlin(rng, NoiseType.SIERPINSKI_TRIANGLE).bake(), NoiseType.SIERPINSKI_TRIANGLE),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_SIMPLEX(rng -> CNG.signaturePerlin(rng, NoiseType.SIMPLEX).bake(), NoiseType.SIMPLEX),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_GLOB(rng -> CNG.signaturePerlin(rng, NoiseType.GLOB).bake(), NoiseType.GLOB),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_VASCULAR(rng -> CNG.signaturePerlin(rng, NoiseType.VASCULAR).bake(), NoiseType.VASCULAR),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_CUBIC(rng -> CNG.signaturePerlin(rng, NoiseType.CUBIC).bake(), NoiseType.CUBIC),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_SUPERFRACTAL(rng -> CNG.signaturePerlin(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX).bake(), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Perlin-warped noise with broad, flowing distortion.")
    NOWHERE_FRACTAL(rng -> CNG.signaturePerlin(rng, NoiseType.FRACTAL_BILLOW_PERLIN).bake(), NoiseType.FRACTAL_BILLOW_PERLIN),

    @Desc("Wispy Perlin-looking simplex noise. The 'iris' style noise.")
    IRIS_DOUBLE(rng -> CNG.signatureDouble(rng)),

    @Desc("Wispy Perlin-looking simplex noise. The 'iris' style noise.")
    IRIS_THICK(rng -> CNG.signatureThick(rng)),

    @Desc("Wispy Perlin-looking simplex noise. The 'iris' style noise.")
    IRIS_HALF(rng -> CNG.signatureHalf(rng)),

    @Desc("Basic, Smooth & Fast Simplex noise.")
    SIMPLEX(rng -> new CNG(rng, 1D, 1)),

    @Desc("Very Detailed smoke using simplex fractured with fractal billow simplex at high octaves.")
    FRACTAL_SMOKE(rng -> new CNG(rng, 1D, 1).fractureWith(new CNG(rng.nextParallelRNG(1), NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 8).scale(0.2), 1000), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("Thinner Veins.")
    VASCULAR_THIN(rng -> new CNG(rng.nextParallelRNG(1), NoiseType.VASCULAR, 1D, 1).scale(1).pow(1D / 0.65D), NoiseType.VASCULAR),

    @Desc("Cells of simplex noise")
    SIMPLEX_CELLS(rng -> new CNG(rng.nextParallelRNG(1), NoiseType.SIMPLEX, 1D, 1).scale(1).fractureWith(new CNG(rng.nextParallelRNG(8), NoiseType.CELLULAR, 1D, 1).scale(1), 200), NoiseType.SIMPLEX),

    @Desc("Veins of simplex noise")
    SIMPLEX_VASCULAR(rng -> new CNG(rng.nextParallelRNG(1), NoiseType.SIMPLEX, 1D, 1).scale(1).fractureWith(new CNG(rng.nextParallelRNG(8), NoiseType.VASCULAR, 1D, 1).scale(1), 200), NoiseType.SIMPLEX),

    @Desc("Very Detailed fluid using simplex fractured with fractal billow simplex at high octaves.")
    FRACTAL_WATER(rng -> new CNG(rng, 1D, 1).fractureWith(new CNG(rng.nextParallelRNG(1), NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 9).scale(0.03), 9900), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Perlin. Like simplex but more natural")
    PERLIN(rng -> new CNG(rng, NoiseType.PERLIN, 1D, 1), NoiseType.PERLIN),

    @Desc("Perlin. Like simplex but more natural")
    PERLIN_IRIS(rng -> CNG.signature(rng, NoiseType.PERLIN), NoiseType.PERLIN),

    @Desc("Perlin. Like simplex but more natural")
    PERLIN_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.PERLIN), NoiseType.PERLIN),

    @Desc("Perlin. Like simplex but more natural")
    PERLIN_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.PERLIN), NoiseType.PERLIN),

    @Desc("Perlin. Like simplex but more natural")
    PERLIN_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.PERLIN), NoiseType.PERLIN),

    @Desc("Billow Fractal Perlin Noise.")
    FRACTAL_BILLOW_PERLIN(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_PERLIN, 1D, 1), NoiseType.FRACTAL_BILLOW_PERLIN),

    @Desc("Billow Fractal Perlin Noise. 2 Octaves")
    BIOCTAVE_FRACTAL_BILLOW_PERLIN(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_PERLIN, 1D, 2), NoiseType.FRACTAL_BILLOW_PERLIN),

    @Desc("Billow Fractal Simplex Noise. Single octave.")
    FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 1), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. Single octave.")
    FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 1), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Billow Fractal Iris Noise. Single octave.")
    FRACTAL_BILLOW_IRIS(rng -> CNG.signature(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Iris Noise. Single octave.")
    FRACTAL_FBM_IRIS(rng -> CNG.signature(rng, NoiseType.FRACTAL_FBM_SIMPLEX), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Billow Fractal Iris Noise. Single octave.")
    FRACTAL_BILLOW_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Iris Noise. Single octave.")
    FRACTAL_FBM_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.FRACTAL_FBM_SIMPLEX), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Billow Fractal Iris Noise. Single octave.")
    FRACTAL_BILLOW_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Iris Noise. Single octave.")
    FRACTAL_FBM_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.FRACTAL_FBM_SIMPLEX), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Fractal hexagonal cell noise.")
    FRACTAL_HEXAGON(rng -> new CNG(rng, NoiseType.HEXAGON, 1D, 4), NoiseType.HEXAGON),

    @Desc("Recursive contained hexagons colored by fractal simplex noise.")
    FRACTAL_HEX_JAMES(rng -> new CNG(rng, NoiseType.HEX_JAMES, 1D, 4), NoiseType.HEX_JAMES),

    @Desc("Interlocked solid hex cells with per-cell fractal simplex heatmap values.")
    FRACTAL_HEX_SIMPLEX(rng -> new CNG(rng, NoiseType.HEX_SIMPLEX, 1D, 4), NoiseType.HEX_SIMPLEX),

    @Desc("Recursive hexagonal subdivision colored by fractal simplex noise.")
    FRACTAL_HEX_RANDOM_SIZE(rng -> new CNG(rng, NoiseType.HEX_RANDOM_SIZE, 1D, 4), NoiseType.HEX_RANDOM_SIZE),

    @Desc("Finite-depth equilateral Sierpinski triangles colored by fractal simplex heat.")
    FRACTAL_SIERPINSKI_TRIANGLE(rng -> new CNG(rng, NoiseType.SIERPINSKI_TRIANGLE, 1D, 4), NoiseType.SIERPINSKI_TRIANGLE),

    @Desc("Rigid Multi Fractal Simplex Noise. Single octave.")
    FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 1), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 2 octaves.")
    BIOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 2), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 2 octaves.")
    BIOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 2), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 2 octaves.")
    BIOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 2), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 3 octaves.")
    TRIOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 3), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 3 octaves.")
    TRIOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 3), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 3 octaves.")
    TRIOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 3), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 4 octaves.")
    QUADOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 4), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 4 octaves.")
    QUADOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 4), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 4 octaves.")
    QUADOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 4), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 5 octaves.")
    QUINTOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 5), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 5 octaves.")
    QUINTOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 5), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 5 octaves.")
    QUINTOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 5), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 6 octaves.")
    SEXOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 6), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 6 octaves.")
    SEXOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 6), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 6 octaves.")
    SEXOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 6), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 7 octaves.")
    SEPTOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 7), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 7 octaves.")
    SEPTOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 7), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 7 octaves.")
    SEPTOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 7), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 8 octaves.")
    OCTOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 8), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 8 octaves.")
    OCTOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 8), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 8 octaves.")
    OCTOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 8), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 9 octaves.")
    NONOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 9), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 9 octaves.")
    NONOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 9), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 9 octaves.")
    NONOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 9), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Rigid Multi Fractal Simplex Noise. 10 octaves.")
    VIGOCTAVE_FRACTAL_RM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 10), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX),

    @Desc("Billow Fractal Simplex Noise. 10 octaves.")
    VIGOCTAVE_FRACTAL_BILLOW_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 10), NoiseType.FRACTAL_BILLOW_SIMPLEX),

    @Desc("FBM Fractal Simplex Noise. 10 octaves.")
    VIGOCTAVE_FRACTAL_FBM_SIMPLEX(rng -> new CNG(rng, NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 10), NoiseType.FRACTAL_FBM_SIMPLEX),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 2 octaves")
    BIOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 2)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 3 octaves")
    TRIOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 3)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 4 octaves")
    QUADOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 4)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 5 octaves")
    QUINTOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 5)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 6 octaves")
    SEXOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 6)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 7 octaves")
    SEPTOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 7)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 8 octaves")
    OCTOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 8)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 9 octaves")
    NONOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 9)),

    @Desc("Basic, Smooth & Fast Simplex noise. Uses 10 octaves")
    VIGOCTAVE_SIMPLEX(rng -> new CNG(rng, 1D, 10)),

    @Desc("Glob noise is like cellular, but with globs...")
    GLOB(rng -> new CNG(rng, NoiseType.GLOB, 1D, 1), NoiseType.GLOB),

    @Desc("Glob noise is like cellular, but with globs...")
    GLOB_IRIS(rng -> CNG.signature(rng, NoiseType.GLOB), NoiseType.GLOB),

    @Desc("Glob noise is like cellular, but with globs...")
    GLOB_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.GLOB), NoiseType.GLOB),

    @Desc("Glob noise is like cellular, but with globs...")
    GLOB_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.GLOB), NoiseType.GLOB),

    @Desc("Glob noise is like cellular, but with globs...")
    GLOB_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.GLOB), NoiseType.GLOB),

    @Desc("Cubic Noise")
    CUBIC(rng -> new CNG(rng, NoiseType.CUBIC, 1D, 1), NoiseType.CUBIC),

    @Desc("Fractal Cubic Noise")
    FRACTAL_CUBIC(rng -> new CNG(rng, NoiseType.FRACTAL_CUBIC, 1D, 1), NoiseType.FRACTAL_CUBIC),

    @Desc("Fractal Cubic Noise With Iris Swirls")
    FRACTAL_CUBIC_IRIS(rng -> CNG.signature(rng, NoiseType.FRACTAL_CUBIC), NoiseType.FRACTAL_CUBIC),

    @Desc("Fractal Cubic Noise With Iris Swirls")
    FRACTAL_CUBIC_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.FRACTAL_CUBIC), NoiseType.FRACTAL_CUBIC),

    @Desc("Fractal Cubic Noise With Iris Swirls")
    FRACTAL_CUBIC_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.FRACTAL_CUBIC), NoiseType.FRACTAL_CUBIC),

    @Desc("Fractal Cubic Noise With Iris Swirls")
    FRACTAL_CUBIC_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.FRACTAL_CUBIC), NoiseType.FRACTAL_CUBIC),

    @Desc("Fractal Cubic Noise, 2 Octaves")
    BIOCTAVE_FRACTAL_CUBIC(rng -> new CNG(rng, NoiseType.FRACTAL_CUBIC, 1D, 2), NoiseType.FRACTAL_CUBIC),

    @Desc("Fractal Cubic Noise, 3 Octaves")
    TRIOCTAVE_FRACTAL_CUBIC(rng -> new CNG(rng, NoiseType.FRACTAL_CUBIC, 1D, 3), NoiseType.FRACTAL_CUBIC),

    @Desc("Fractal Cubic Noise, 4 Octaves")
    QUADOCTAVE_FRACTAL_CUBIC(rng -> new CNG(rng, NoiseType.FRACTAL_CUBIC, 1D, 4), NoiseType.FRACTAL_CUBIC),

    @Desc("Cubic Noise")
    CUBIC_IRIS(rng -> CNG.signature(rng, NoiseType.CUBIC), NoiseType.CUBIC),

    @Desc("Cubic Noise")
    CUBIC_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.CUBIC), NoiseType.CUBIC),

    @Desc("Cubic Noise")
    CUBIC_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.CUBIC), NoiseType.CUBIC),

    @Desc("Cubic Noise")
    CUBIC_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.CUBIC), NoiseType.CUBIC),

    @Desc("Hexagonal cell noise distorted using Iris styled wispy noise.")
    HEXAGON_IRIS(rng -> CNG.signature(rng, NoiseType.HEXAGON), NoiseType.HEXAGON),

    @Desc("Hexagonal cell noise distorted using Iris styled wispy noise.")
    HEXAGON_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.HEXAGON), NoiseType.HEXAGON),

    @Desc("Hexagonal cell noise distorted using Iris styled wispy noise.")
    HEXAGON_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.HEXAGON), NoiseType.HEXAGON),

    @Desc("Hexagonal cell noise distorted using Iris styled wispy noise.")
    HEXAGON_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.HEXAGON), NoiseType.HEXAGON),

    @Desc("Hex James substitution pattern distorted using Iris styled wispy noise.")
    HEX_JAMES_IRIS(rng -> CNG.signature(rng, NoiseType.HEX_JAMES), NoiseType.HEX_JAMES),

    @Desc("Hex James substitution pattern distorted using Iris styled wispy noise.")
    HEX_JAMES_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.HEX_JAMES), NoiseType.HEX_JAMES),

    @Desc("Hex James substitution pattern distorted using Iris styled wispy noise.")
    HEX_JAMES_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.HEX_JAMES), NoiseType.HEX_JAMES),

    @Desc("Hex James substitution pattern distorted using Iris styled wispy noise.")
    HEX_JAMES_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.HEX_JAMES), NoiseType.HEX_JAMES),

    @Desc("Interlocked solid hex-cell simplex heatmap distorted using Iris styled wispy noise.")
    HEX_SIMPLEX_IRIS(rng -> CNG.signature(rng, NoiseType.HEX_SIMPLEX), NoiseType.HEX_SIMPLEX),

    @Desc("Interlocked solid hex-cell simplex heatmap distorted using Iris styled wispy noise.")
    HEX_SIMPLEX_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.HEX_SIMPLEX), NoiseType.HEX_SIMPLEX),

    @Desc("Interlocked solid hex-cell simplex heatmap distorted using Iris styled wispy noise.")
    HEX_SIMPLEX_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.HEX_SIMPLEX), NoiseType.HEX_SIMPLEX),

    @Desc("Interlocked solid hex-cell simplex heatmap distorted using Iris styled wispy noise.")
    HEX_SIMPLEX_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.HEX_SIMPLEX), NoiseType.HEX_SIMPLEX),

    @Desc("Hexagonal random-size gradient noise and distorted using Iris styled wispy noise.")
    HEX_RANDOM_SIZE_IRIS(rng -> CNG.signature(rng, NoiseType.HEX_RANDOM_SIZE), NoiseType.HEX_RANDOM_SIZE),

    @Desc("Hexagonal random-size gradient noise and distorted using Iris styled wispy noise.")
    HEX_RANDOM_SIZE_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.HEX_RANDOM_SIZE), NoiseType.HEX_RANDOM_SIZE),

    @Desc("Hexagonal random-size gradient noise and distorted using Iris styled wispy noise.")
    HEX_RANDOM_SIZE_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.HEX_RANDOM_SIZE), NoiseType.HEX_RANDOM_SIZE),

    @Desc("Hexagonal random-size gradient noise and distorted using Iris styled wispy noise.")
    HEX_RANDOM_SIZE_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.HEX_RANDOM_SIZE), NoiseType.HEX_RANDOM_SIZE),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders. Cells are distorted using Iris styled wispy noise.")
    CELLULAR_IRIS(rng -> CNG.signature(rng, NoiseType.CELLULAR), NoiseType.CELLULAR),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders. Cells are distorted using Iris styled wispy noise.")
    CELLULAR_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.CELLULAR), NoiseType.CELLULAR),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders. Cells are distorted using Iris styled wispy noise.")
    CELLULAR_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.CELLULAR), NoiseType.CELLULAR),

    @Desc("Cellular noise creates the same noise level for cells, changes noise level on cell borders. Cells are distorted using Iris styled wispy noise.")
    CELLULAR_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.CELLULAR), NoiseType.CELLULAR),

    @Desc("Inverse of vascular, height gets to 1.0 as it approaches the center of a cell")
    CELLULAR_HEIGHT(rng -> new CNG(rng, NoiseType.CELLULAR_HEIGHT, 1D, 1), NoiseType.CELLULAR_HEIGHT),

    @Desc("Inverse of vascular, height gets to 1.0 as it approaches the center of a cell, using the iris style.")
    CELLULAR_HEIGHT_IRIS(rng -> CNG.signature(rng, NoiseType.CELLULAR_HEIGHT), NoiseType.CELLULAR_HEIGHT),

    @Desc("Inverse of vascular, height gets to 1.0 as it approaches the center of a cell, using the iris style.")
    CELLULAR_HEIGHT_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.CELLULAR_HEIGHT), NoiseType.CELLULAR_HEIGHT),

    @Desc("Inverse of vascular, height gets to 1.0 as it approaches the center of a cell, using the iris style.")
    CELLULAR_HEIGHT_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.CELLULAR_HEIGHT), NoiseType.CELLULAR_HEIGHT),

    @Desc("Inverse of vascular, height gets to 1.0 as it approaches the center of a cell, using the iris style.")
    CELLULAR_HEIGHT_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.CELLULAR_HEIGHT), NoiseType.CELLULAR_HEIGHT),

    @Desc("Vascular noise gets higher as the position nears a cell border. Cells are distorted using Iris styled wispy noise.")
    VASCULAR_IRIS(rng -> CNG.signature(rng, NoiseType.VASCULAR), NoiseType.VASCULAR),

    @Desc("Vascular noise gets higher as the position nears a cell border. Cells are distorted using Iris styled wispy noise.")
    VASCULAR_IRIS_DOUBLE(rng -> CNG.signatureDouble(rng, NoiseType.VASCULAR), NoiseType.VASCULAR),

    @Desc("Vascular noise gets higher as the position nears a cell border. Cells are distorted using Iris styled wispy noise.")
    VASCULAR_IRIS_THICK(rng -> CNG.signatureThick(rng, NoiseType.VASCULAR), NoiseType.VASCULAR),

    @Desc("Vascular noise gets higher as the position nears a cell border. Cells are distorted using Iris styled wispy noise.")
    VASCULAR_IRIS_HALF(rng -> CNG.signatureHalf(rng, NoiseType.VASCULAR), NoiseType.VASCULAR),

    @Desc("Warped gyroid sheets form rounded mazes in 2D and interconnected labyrinths in 3D.")
    GYROID(rng -> new CNG(rng, NoiseType.GYROID, 1D, 1), NoiseType.GYROID),

    @Desc("Fivefold wave interference forms non-repeating stars, rosettes, and crystalline contours.")
    QUASICRYSTAL(rng -> new CNG(rng, NoiseType.QUASICRYSTAL, 1D, 1), NoiseType.QUASICRYSTAL),

    @Desc("Connected quarter-circle ribbons form tiled loops that twist continuously through height.")
    TRUCHET(rng -> new CNG(rng, NoiseType.TRUCHET, 1D, 1), NoiseType.TRUCHET),

    @Desc("Scattered bowls with raised rims form crater fields in 2D and hollow shells in 3D.")
    CRATER(rng -> new CNG(rng, NoiseType.CRATER, 1D, 1), NoiseType.CRATER),

    @Desc("Seeded spiral arms form swirling eddies in 2D and winding funnels in 3D.")
    VORTEX(rng -> new CNG(rng, NoiseType.VORTEX, 1D, 1), NoiseType.VORTEX),
    ;

    private final CNGFactory f;
    private final NoiseType type;

    NoiseStyle(CNGFactory f) {
        this(f, NoiseType.SIMPLEX);
    }

    NoiseStyle(CNGFactory f, NoiseType type) {
        this.f = f;
        this.type = type;
    }

    public ProceduralStream<Double> stream(RNG seed) {
        return create(seed).stream();
    }

    public ProceduralStream<Double> stream(long seed) {
        return create(new RNG(seed)).stream();
    }

    public CNG create(RNG seed) {
        CNG cng = f.create(seed).bake().scale(type.getCoordinateScale()).bake();
        cng.setLeakStyle(this);
        return cng;
    }

    public IrisGeneratorStyle style() {
        return new IrisGeneratorStyle(this);
    }
}
