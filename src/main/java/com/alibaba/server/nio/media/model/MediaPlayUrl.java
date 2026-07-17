package com.alibaba.server.nio.media.model;

public class MediaPlayUrl {
    private final Long fileId;
    private final String playUrl;
    private final Long fileSize;
    private final String mimeType;
    private final long expiresIn;
    private final boolean playable;

    public MediaPlayUrl(Long fileId, String playUrl, Long fileSize, String mimeType, long expiresIn, boolean playable) {
        this.fileId = fileId;
        this.playUrl = playUrl;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.expiresIn = expiresIn;
        this.playable = playable;
    }

    public Long getFileId() {
        return fileId;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public boolean isPlayable() {
        return playable;
    }
}
