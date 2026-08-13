package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftLockTest {

    private final SoftLock lock = new SoftLock(SoftLock.Config.defaults());

    @Test
    void zeroInsideRange() {
        assertEquals(0f, lock.torqueNm(0, 0, 450));
        assertEquals(0f, lock.torqueNm(449.9, 500, 450));
        assertEquals(0f, lock.torqueNm(-449.9, -500, 450));
        assertEquals(0f, lock.torqueNm(450, 0, 450));
    }

    @Test
    void pushesBackOutsideRange() {
        assertTrue(lock.torqueNm(460, 0, 450) < 0, "past +lock must push negative (back toward center)");
        assertTrue(lock.torqueNm(-460, 0, 450) > 0, "past -lock must push positive");
        // 10 degrees over at default 0.5 Nm/deg
        assertEquals(-5.0f, lock.torqueNm(460, 0, 450), 1e-4);
    }

    @Test
    void symmetric() {
        assertEquals(lock.torqueNm(470, 0, 450), -lock.torqueNm(-470, 0, 450), 1e-6);
    }

    @Test
    void dampingOnlyResistsMotionIntoTheStop() {
        float still = lock.torqueNm(460, 0, 450);
        float pushingIn = lock.torqueNm(460, 200, 450);
        float pullingOut = lock.torqueNm(460, -200, 450);
        assertTrue(pushingIn < still, "moving further in must add resistance");
        assertEquals(still, pullingOut, 1e-6, "moving back toward center must not be resisted");
    }

    @Test
    void stiffnessScalesWithOvershoot() {
        float at5 = lock.torqueNm(455, 0, 450);
        float at20 = lock.torqueNm(470, 0, 450);
        assertEquals(4.0, at20 / at5, 1e-3);
    }
}
