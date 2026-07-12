package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DownloadTransferSpecTest {

    @Test
    public void usesRemainingFileBytesForResumeDownload() {
        JSONObject request = new JSONObject();
        request.put("startOffset", 400L);

        DownloadTransferSpec spec = DownloadTransferSpec.from(request, 1000L);

        assertEquals(400L, spec.getStartOffset());
        assertEquals(600L, spec.getTransferLength());
    }

    @Test
    public void limitsRangePullToRequestedLength() {
        JSONObject request = new JSONObject();
        request.put("startOffset", 400L);
        request.put("length", 128L);

        DownloadTransferSpec spec = DownloadTransferSpec.from(request, 1000L);

        assertEquals(400L, spec.getStartOffset());
        assertEquals(128L, spec.getTransferLength());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOffsetBeyondFileSize() {
        JSONObject request = new JSONObject();
        request.put("startOffset", 1001L);

        DownloadTransferSpec.from(request, 1000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeOffset() {
        JSONObject request = new JSONObject();
        request.put("startOffset", -1L);

        DownloadTransferSpec.from(request, 1000L);
    }
}
