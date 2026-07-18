package com.alibaba.server.nio.service.file.adaptive;

import com.alibaba.server.nio.service.file.config.FileUploadConfig;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UploadBackpressureControllerTest {

    private UploadBackpressureController controller;
    private FileUploadConfig config;

    @Before
    public void setUp() {
        config = new FileUploadConfig();
        config.setAdaptiveChunkMinBytes(32 * 1024);
        config.setAdaptiveChunkInitialBytes(64 * 1024);
        config.setAdaptiveChunkMaxBytes(512 * 1024);
        config.setAdaptiveAckInitialBytes(1024 * 1024);
        config.setAdaptiveAckMaxBytes(8 * 1024 * 1024);
        config.setPerConnectionRateBps(50L * 1024 * 1024);
        config.setGlobalRateBps(100L * 1024 * 1024);
        controller = new UploadBackpressureController(config);
    }

    @Test
    public void shouldRecommendMaximumWindowWhenServerIsHealthy() {
        UploadBackpressureDecision decision = controller.decide(
                ResourcePressureLevel.NORMAL, 12L, 0.10D, 2, 0.80D);

        assertEquals("normal", decision.getServerState());
        assertEquals(512 * 1024, decision.getRecommendedChunkSize());
        assertEquals(8 * 1024 * 1024, decision.getRecommendedAckWindow());
        assertEquals(0L, decision.getRetryAfterMs());
    }

    @Test
    public void shouldSlowDownForHighPressureOrWorkerQueueGrowth() {
        UploadBackpressureDecision pressureDecision = controller.decide(
                ResourcePressureLevel.HIGH, 80L, 0.20D, 4, 0.30D);
        UploadBackpressureDecision queueDecision = controller.decide(
                ResourcePressureLevel.NORMAL, 30L, 0.70D, 4, 0.30D);

        assertEquals("slow_down", pressureDecision.getServerState());
        assertEquals("slow_down", queueDecision.getServerState());
        assertTrue(pressureDecision.getRecommendedChunkSize() <= 64 * 1024);
        assertTrue(pressureDecision.getRecommendedAckWindow() <= 1024 * 1024);
    }

    @Test
    public void shouldPauseWhenResourcePressureIsCritical() {
        UploadBackpressureDecision decision = controller.decide(
                ResourcePressureLevel.CRITICAL, 10L, 0.20D, 3, 0.90D);

        assertEquals("pause", decision.getServerState());
        assertTrue(decision.getRetryAfterMs() > 0L);
        assertEquals(32 * 1024, decision.getRecommendedChunkSize());
        assertEquals(1024 * 1024, decision.getRecommendedAckWindow());
    }

    @Test
    public void shouldPauseWhenWorkerQueueIsAlmostFull() {
        UploadBackpressureDecision decision = controller.decide(
                ResourcePressureLevel.NORMAL, 10L, 0.90D, 3, 0.90D);

        assertEquals("pause", decision.getServerState());
    }

    @Test
    public void shouldClampInvalidMetricsConservatively() {
        UploadBackpressureDecision decision = controller.decide(
                ResourcePressureLevel.NORMAL, -1L, Double.NaN, 0, Double.POSITIVE_INFINITY);

        assertEquals("slow_down", decision.getServerState());
        assertTrue(decision.getRecommendedChunkSize() >= 32 * 1024);
        assertTrue(decision.getRecommendedAckWindow() >= 1024 * 1024);
    }

    @Test
    public void shouldCalculateFairRateFromCurrentActiveUploads() {
        assertEquals(50L * 1024 * 1024, config.calculateDynamicRate(1));
        assertEquals(50L * 1024 * 1024, config.calculateDynamicRate(2));
        assertEquals(25L * 1024 * 1024, config.calculateDynamicRate(4));
    }
}
