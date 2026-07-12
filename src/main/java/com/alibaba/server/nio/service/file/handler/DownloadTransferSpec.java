package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;

final class DownloadTransferSpec {
    private final long startOffset;
    private final long transferLength;

    private DownloadTransferSpec(long startOffset, long transferLength) {
        this.startOffset = startOffset;
        this.transferLength = transferLength;
    }

    static DownloadTransferSpec from(JSONObject request, long fileSize) {
        if (fileSize < 0) {
            throw new IllegalArgumentException("文件大小非法");
        }
        Long requestedOffset = request == null ? null : request.getLong("startOffset");
        long startOffset = requestedOffset == null ? 0L : requestedOffset;
        if (startOffset < 0 || startOffset > fileSize) {
            throw new IllegalArgumentException("下载偏移量超出文件范围");
        }
        long remaining = fileSize - startOffset;
        Long requestedLength = request == null ? null : request.getLong("length");
        long transferLength = requestedLength == null ? remaining : requestedLength;
        if (transferLength <= 0 && remaining > 0) {
            throw new IllegalArgumentException("下载长度必须大于0");
        }
        transferLength = Math.min(transferLength, remaining);
        return new DownloadTransferSpec(startOffset, transferLength);
    }

    long getStartOffset() { return startOffset; }
    long getTransferLength() { return transferLength; }
}
