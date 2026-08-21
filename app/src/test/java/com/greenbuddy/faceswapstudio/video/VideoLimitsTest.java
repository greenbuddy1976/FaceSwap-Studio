package com.greenbuddy.faceswapstudio.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoLimitsTest {
    private static final long TEN_MINUTES_US = 10L * 60L * 1_000_000L;
    private static final long FIFTEEN_MINUTES_US = 15L * 60L * 1_000_000L;

    @Test
    public void acceptsAtLeastTenMinutes() {
        assertTrue(VideoLimits.supportsDuration(TEN_MINUTES_US));
        assertTrue(VideoLimits.supportsDuration(FIFTEEN_MINUTES_US));
        assertFalse(VideoLimits.supportsDuration(FIFTEEN_MINUTES_US + 1L));
    }

    @Test
    public void keepsLongVideoOutputResponsiveWithoutUnboundedModelRuns() {
        int outputFrameRate = VideoLimits.chooseOutputFrameRate(30f);
        int tenMinuteFrames = outputFrameRate * 10 * 60;

        assertEquals(12, outputFrameRate);
        assertEquals(7_200, tenMinuteFrames);
        assertEquals(6, VideoLimits.chooseSwapInterval(tenMinuteFrames));
        assertEquals(1_200, VideoLimits.estimatedSwapInferences(tenMinuteFrames));

        int fifteenMinuteFrames = outputFrameRate * 15 * 60;
        assertEquals(10_800, fifteenMinuteFrames);
        assertEquals(9, VideoLimits.chooseSwapInterval(fifteenMinuteFrames));
        assertEquals(1_200, VideoLimits.estimatedSwapInferences(fifteenMinuteFrames));
    }

    @Test
    public void reusesSwapResultsInTheLongDurationDeviceTest() {
        int outputFrameRate = VideoLimits.chooseOutputFrameRate(3f);
        int tenMinuteFrames = outputFrameRate * 10 * 60;

        assertEquals(3, outputFrameRate);
        assertEquals(2, VideoLimits.chooseSwapInterval(tenMinuteFrames));
        assertEquals(900, VideoLimits.estimatedSwapInferences(tenMinuteFrames));
    }
}
