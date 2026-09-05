package art.arcane.iris.engine;

import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.volmlib.util.math.M;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IrisComplexConstantGeneratorBoundsTest {
    @Test
    public void constantBoundsPreserveFiniteArithmeticAndSkipNoise() {
        double[] bounds = {0D, -0D, 0.1D, -0.1D, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] noises = {0D, -0D, 0.3D, -1D, Double.MAX_VALUE, -Double.MAX_VALUE};
        int[] sizes = {1, 2, 3, 11};
        for (double low : bounds) {
            for (double high : bounds) {
                if (low != high) {
                    continue;
                }
                for (double noise : noises) {
                    for (int size : sizes) {
                        CountingGenerator generator = new CountingGenerator(noise);
                        IrisGenerator[] generators = new IrisGenerator[size];
                        Arrays.fill(generators, generator);
                        double expected = 0D;
                        for (int i = 0; i < size; i++) {
                            expected += M.lerp(low, high, noise);
                        }
                        expected /= size;
                        assertBits(expected, IrisComplex.averageGeneratorHeights(generators, low, high, 12D, -9D, 7331L));
                        assertEquals(0, generator.calls);
                    }
                }
            }
        }
    }

    @Test
    public void inactiveInvalidNoiseMatchesExistingConstantFitContract() {
        for (double noise : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            CountingGenerator generator = new CountingGenerator(noise);
            assertBits(generator.fitDouble(0D, 0D, 7331L, 12D, -9D),
                    IrisComplex.averageGeneratorHeights(new IrisGenerator[]{generator}, 0D, 0D, 12D, -9D, 7331L));
            assertEquals(0, generator.calls);
            assertTrue(Double.isNaN(M.lerp(0D, 0D, noise)));
        }
    }

    @Test
    public void nonconstantAndNonfiniteBoundsRetainNoiseEvaluation() {
        double[][] bounds = {{0D, 1D}, {-9D, 12D}, {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY}, {Double.NaN, Double.NaN}};
        for (double[] pair : bounds) {
            for (double noise : new double[]{0.3D, Double.NaN, Double.POSITIVE_INFINITY}) {
                CountingGenerator generator = new CountingGenerator(noise);
                double expected = (0D + M.lerp(pair[0], pair[1], noise)) / 1;
                assertBits(expected, IrisComplex.averageGeneratorHeights(new IrisGenerator[]{generator},
                        pair[0], pair[1], 12D, -9D, 7331L));
                assertEquals(1, generator.calls);
            }
        }
    }

    private static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static final class CountingGenerator extends IrisGenerator {
        private final double value;
        private int calls;

        private CountingGenerator(double value) {
            this.value = value;
        }

        @Override
        public double getHeight(double x, double z, long seed) {
            calls++;
            return value;
        }
    }
}
