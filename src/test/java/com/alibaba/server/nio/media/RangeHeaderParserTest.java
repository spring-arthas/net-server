package com.alibaba.server.nio.media;

import com.alibaba.server.nio.media.model.ByteRange;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RangeHeaderParserTest {

    @Test
    public void parsesOpenEndedRange() {
        ByteRange range = RangeHeaderParser.parse("bytes=100-", 1000);

        assertTrue(range.isPartial());
        assertFalse(range.isInvalid());
        assertEquals(100L, range.getStart());
        assertEquals(999L, range.getEnd());
        assertEquals(900L, range.length());
    }

    @Test
    public void parsesExplicitRangeAndClampsEnd() {
        ByteRange range = RangeHeaderParser.parse("bytes=100-2000", 1000);

        assertTrue(range.isPartial());
        assertFalse(range.isInvalid());
        assertEquals(100L, range.getStart());
        assertEquals(999L, range.getEnd());
    }

    @Test
    public void parsesSuffixRange() {
        ByteRange range = RangeHeaderParser.parse("bytes=-128", 1000);

        assertTrue(range.isPartial());
        assertFalse(range.isInvalid());
        assertEquals(872L, range.getStart());
        assertEquals(999L, range.getEnd());
        assertEquals(128L, range.length());
    }

    @Test
    public void returnsFullRangeWhenRangeHeaderMissing() {
        ByteRange range = RangeHeaderParser.parse(null, 1000);

        assertFalse(range.isPartial());
        assertFalse(range.isInvalid());
        assertEquals(0L, range.getStart());
        assertEquals(999L, range.getEnd());
        assertEquals(1000L, range.length());
    }

    @Test
    public void marksOutOfBoundsRangeInvalid() {
        ByteRange range = RangeHeaderParser.parse("bytes=1000-1200", 1000);

        assertTrue(range.isInvalid());
    }
}
