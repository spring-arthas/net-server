package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.model.file.FileUploadContext;
import org.apache.commons.lang.StringUtils;

final class UploadResponseTaskIdResolver {

    private UploadResponseTaskIdResolver() {
    }

    static String resolve(FileUploadContext uploadContext, String requestTaskId) {
        if (uploadContext != null && StringUtils.isNotBlank(uploadContext.getRequestTaskId())) {
            return uploadContext.getRequestTaskId();
        }
        return requestTaskId;
    }
}
