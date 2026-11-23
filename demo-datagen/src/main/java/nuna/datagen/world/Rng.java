package nuna.datagen.world;

import java.util.List;
import java.util.SplittableRandom;

/** The world's one source of chance: SplittableRandom, whose sequence the JDK specifies, and Box-Muller normals. */
final class Rng {

    private final SplittableRandom r;

    Rng(long seed) {
        r = new SplittableRandom(seed);
    }

    double uniform() {
        return r.nextDouble();
    }

    int below(int n) {
        return r.nextInt(n);
    }

    int between(int lo, int hiInclusive) {
        return lo + r.nextInt(hiInclusive - lo + 1);
    }

    boolean chance(double p) {
        return r.nextDouble() < p;
    }

    double normal() {
        double u1 = r.nextDouble(), u2 = r.nextDouble();
        return Math.sqrt(-2 * Math.log(1 - u1)) * Math.cos(2 * Math.PI * u2);
    }

    /** A lognormal draw with the given median and spread (sigma of the log). */
    double lognormal(double median, double sigma) {
        return median * Math.exp(sigma * normal());
    }

    int poisson(double mean) {
        double l = Math.exp(-mean), p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return k - 1;
    }

    <T> T pick(List<T> list) {
        return list.get(r.nextInt(list.size()));
    }

    /** An index in [0, n) drawn with weight 1/(i+1)^s: a few popular names, a long tail. */
    int zipf(int n, double s, double[] cumulative) {
        double u = r.nextDouble() * cumulative[n - 1];
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < u) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
