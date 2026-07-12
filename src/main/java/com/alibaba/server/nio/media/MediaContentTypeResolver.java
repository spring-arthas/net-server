package com.alibaba.server.nio.media;

public final class MediaContentTypeResolver {
    private MediaContentTypeResolver() {
    }

    public static String resolve(String fileName) {
        String ext = extension(fileName);
        if ("mp4".equals(ext) || "m4v".equals(ext)) {
            return "video/mp4";
        }
        if ("mov".equals(ext)) {
            return "video/quicktime";
        }
        if ("webm".equals(ext)) {
            return "video/webm";
        }
        return "application/octet-stream";
    }

    public static boolean isPlayableVideo(String fileName) {
        String ext = extension(fileName);
        return "mp4".equals(ext) || "m4v".equals(ext) || "mov".equals(ext);
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
