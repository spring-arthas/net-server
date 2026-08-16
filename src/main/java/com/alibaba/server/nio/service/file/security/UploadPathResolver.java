package com.alibaba.server.nio.service.file.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 将客户端上传标识解析为认证目录内的单层文件路径。
 */
public final class UploadPathResolver {
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private UploadPathResolver() {
    }

    /**
     * 校验客户端可控的任务标识和文件名。
     *
     * @param taskId 上传任务标识
     * @param fileName 原始文件名
     */
    public static void validateSegments(String taskId, String fileName) {
        if (taskId == null || !TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("上传任务ID无效");
        }
        if (fileName == null || fileName.trim().isEmpty() || fileName.length() > MAX_FILE_NAME_LENGTH) {
            throw new IllegalArgumentException("上传文件名无效");
        }
        if (".".equals(fileName) || "..".equals(fileName)
                || fileName.contains("/") || fileName.contains("\\")
                || containsControlCharacter(fileName)) {
            throw new IllegalArgumentException("上传文件名包含非法路径字符");
        }
        try {
            Path fileNamePath = Paths.get(fileName);
            if (fileNamePath.isAbsolute() || fileNamePath.getNameCount() != 1) {
                throw new IllegalArgumentException("上传文件名不能包含路径");
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("上传文件名无效", e);
        }
    }

    /**
     * 构建并验证最终上传路径。
     *
     * @param authenticatedDirectory 已通过用户权限校验的目录
     * @param taskId 上传任务标识
     * @param fileName 原始文件名
     * @return 认证目录内的规范化绝对路径
     */
    public static Path resolve(Path authenticatedDirectory, String taskId, String fileName) {
        if (authenticatedDirectory == null) {
            throw new IllegalArgumentException("上传目录不能为空");
        }
        validateSegments(taskId, fileName);

        Path directory = authenticatedDirectory.toAbsolutePath().normalize();
        if (Files.exists(directory)) {
            try {
                directory = directory.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException("无法解析上传目录", e);
            }
        }

        Path target = directory.resolve(taskId + "_" + fileName).normalize();
        if (!target.startsWith(directory) || !directory.equals(target.getParent())) {
            throw new IllegalArgumentException("上传路径越出认证目录");
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("上传目标不能是符号链接");
        }
        return target;
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
