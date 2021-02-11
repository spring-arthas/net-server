package com.alibaba.server.nio.service.file.model;

import lombok.Data;

/**
 * @author spring
 */
@Data
public class DownloadResult {
    private String data;

    private Boolean isFinished;

    private String error;

    public DownloadResult(String data, Boolean isFinished) {
        this.data = data;
        this.isFinished = isFinished;
    }

    public DownloadResult(String data, Boolean isFinished, String error) {
        this.data = data;
        this.isFinished = isFinished;
        this.error = error;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public Boolean getFinished() {
        return isFinished;
    }

    public void setFinished(Boolean finished) {
        isFinished = finished;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public static DownloadResult generate(String data, Boolean isFinished, String error) {
        return new DownloadResult(data, isFinished, error);
    }

    public static DownloadResult generate(String data, Boolean isFinished) {
        return new DownloadResult(data, isFinished);
    }
}
