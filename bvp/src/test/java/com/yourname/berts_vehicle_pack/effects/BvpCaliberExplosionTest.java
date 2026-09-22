package com.yourname.berts_vehicle_pack.effects;

public final class BvpCaliberExplosionTest {
    public static void main(String[] args) {
        require(BvpCaliberExplosion.diameter(12.7) == 0F);
        require(BvpCaliberExplosion.diameter(14.49) == 0F);
        require(BvpCaliberExplosion.diameter(14.5) > 0F && BvpCaliberExplosion.diameter(14.5) < 0.12F);
        require(BvpCaliberExplosion.diameter(100) == 2F);
        require(BvpCaliberExplosion.diameter(20) >= 0.3F);
        require(BvpCaliberExplosion.diameter(30) >= 0.64F);
        require(BvpCaliberExplosion.diameter(Double.NaN) == 0F);
        float previous = 0F;
        for (double caliber : new double[] {14.5, 20, 23, 25, 30, 40, 57, 76, 100, 120, 125, 155}) {
            float size = BvpCaliberExplosion.diameter(caliber);
            require(size > previous);
            previous = size;
        }
        require(BvpCaliberExplosion.diameter(10000) == 32F);
        System.out.println("PASS caliber visual threshold, diameter anchor, monotonicity and bounds");
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Caliber explosion policy");
    }
}
