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
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return "image/jpeg";
        }
        if ("png".equals(ext)) {
            return "image/png";
        }
        if ("gif".equals(ext)) {
            return "image/gif";
        }
        if ("webp".equals(ext)) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    public static boolean isPlayableVideo(String fileName) {
        String ext = extension(fileName);
        return "mp4".equals(ext) || "m4v".equals(ext) || "mov".equals(ext);
    }

    /**
     * 是否为可预览图片（浏览器可直接渲染的图片格式）。
     */
    public static boolean isPreviewableImage(String fileName) {
        String ext = extension(fileName);
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "gif".equals(ext) || "webp".equals(ext);
    }

    /**
     * 是否为可预览媒体（图片或可播放视频）。
     */
    public static boolean isPreviewableMedia(String fileName) {
        return isPreviewableImage(fileName) || isPlayableVideo(fileName);
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
