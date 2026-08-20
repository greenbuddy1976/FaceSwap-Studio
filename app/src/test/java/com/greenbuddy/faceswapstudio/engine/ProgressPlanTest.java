package com.greenbuddy.faceswapstudio.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProgressPlanTest {
    @Test
    public void stagesAreStrictlyIncreasingAndFinishAtOneHundred() {
        int previous = -1;
        int[] stages = ProgressPlan.orderedStages();
        for (int stage : stages) {
            assertTrue(stage > previous);
            assertNotEquals("No processing stage may masquerade as the old 4% deadlock", 4, stage);
            previous = stage;
        }
        assertEquals(100, stages[stages.length - 1]);
    }
}
