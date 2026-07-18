package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.model.file.FileUploadContext;
import com.alibaba.server.nio.service.file.adaptive.UploadBackpressureDecision;
import org.apache.commons.lang.StringUtils;

final class UploadAckPayloadBuilder {

    private UploadAckPayloadBuilder() {
    }

    static JSONObject build(FileUploadContext uploadContext,
            String requestTaskId,
            String status,
            String message,
            Long uploadedSize) {
        return build(uploadContext, requestTaskId, status, message, uploadedSize, null);
    }

    static JSONObject build(FileUploadContext uploadContext,
            String requestTaskId,
            String status,
            String message,
            Long uploadedSize,
            UploadBackpressureDecision decision) {
        JSONObject ackJson = new JSONObject();
        ackJson.put("taskId", UploadResponseTaskIdResolver.resolve(uploadContext, requestTaskId));
        ackJson.put("fileId", uploadContext != null ? uploadContext.getFileId() : null);
        ackJson.put("status", status);
        if (StringUtils.isNotBlank(message)) {
            ackJson.put("message", message);
        }
        if (uploadedSize != null) {
            ackJson.put("uploadedSize", uploadedSize);
        }
        if (decision != null) {
            ackJson.put("serverState", decision.getServerState());
            ackJson.put("recommendedChunkSize", decision.getRecommendedChunkSize());
            ackJson.put("recommendedAckWindow", decision.getRecommendedAckWindow());
            ackJson.put("serverWriteMillis", decision.getServerWriteMillis());
            ackJson.put("retryAfterMs", decision.getRetryAfterMs());
        }
        return ackJson;
    }
}
