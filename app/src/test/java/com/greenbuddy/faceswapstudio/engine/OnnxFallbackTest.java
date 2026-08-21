package com.greenbuddy.faceswapstudio.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class OnnxFallbackTest {
    @Test
    public void retriesNonFiniteAcceleratedOutputExactlyOnceOnCpu() throws Exception {
        AtomicInteger acceleratedCalls = new AtomicInteger();
        AtomicInteger cpuCalls = new AtomicInteger();

        float[] result = OnnxTools.retryNonFinite(
            () -> {
                acceleratedCalls.incrementAndGet();
                throw new NonFiniteModelOutputException();
            },
            () -> {
                cpuCalls.incrementAndGet();
                return new float[] { 0.25f, 0.75f };
            }
        );

        assertArrayEquals(new float[] { 0.25f, 0.75f }, result, 0f);
        assertEquals(1, acceleratedCalls.get());
        assertEquals(1, cpuCalls.get());
    }

    @Test
    public void doesNotRetryAnUnrelatedInputError() throws Exception {
        AtomicInteger cpuCalls = new AtomicInteger();
        try {
            OnnxTools.retryNonFinite(
                () -> {
                    throw new FaceSwapException("input is invalid");
                },
                () -> {
                    cpuCalls.incrementAndGet();
                    return new float[] { 1f };
                }
            );
            fail("unrelated errors must not be hidden by a backend retry");
        } catch (FaceSwapException expected) {
            assertEquals("input is invalid", expected.getMessage());
        }
        assertEquals(0, cpuCalls.get());
    }

    @Test
    public void reportsIfBothBackendsProduceNonFiniteValues() throws Exception {
        try {
            OnnxTools.retryNonFinite(
                () -> {
                    throw new NonFiniteModelOutputException();
                },
                () -> {
                    throw new NonFiniteModelOutputException();
                }
            );
            fail("two numerical failures must be reported");
        } catch (FaceSwapException expected) {
            assertEquals(
                "Auch der stabile CPU-Modus konnte für dieses Bild keine gültige KI-Ausgabe erzeugen.",
                expected.getMessage()
            );
        }
    }
}
