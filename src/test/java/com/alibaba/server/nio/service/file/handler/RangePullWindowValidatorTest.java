package com.alibaba.server.nio.service.file.handler;

import org.junit.Assert;
import org.junit.Test;

public class RangePullWindowValidatorTest {

    @Test
    public void shouldAcceptWindowWithinFileSize() {
        RangePullWindowValidator.ValidationResult result = RangePullWindowValidator.validate(1024L, 100L, 200L);
        Assert.assertTrue(result.isValid());
        Assert.assertEquals(200L, result.getActualLength());
    }

    @Test
    public void shouldRejectOutOfBoundsWindow() {
        RangePullWindowValidator.ValidationResult result = RangePullWindowValidator.validate(1024L, 1024L, 1L);
        Assert.assertFalse(result.isValid());
        Assert.assertEquals(41601, result.getCode());
    }

    @Test
    public void shouldClipWindowToEof() {
        RangePullWindowValidator.ValidationResult result = RangePullWindowValidator.validate(1024L, 900L, 300L);
        Assert.assertTrue(result.isValid());
        Assert.assertEquals(124L, result.getActualLength());
    }
}
