package com.alibaba.server.nio.repository.file.service.exception;

/**
 * 目录或其子目录中仍存在有效文件。
 */
public class DirectoryContainsFileException extends IllegalStateException {

    public DirectoryContainsFileException(String message) {
        super(message);
    }
}
