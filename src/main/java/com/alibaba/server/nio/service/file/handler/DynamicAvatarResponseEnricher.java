package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicAuthorDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicCreateResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicDetailResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicPostDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicTimelinePage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

/** 将动态作者的服务端本机头像路径转换成客户端可读取的纯 Base64。 */
final class DynamicAvatarResponseEnricher {
    private DynamicAvatarResponseEnricher() {
    }

    // [修改] 动态创建、时间线、详情统一处理作者头像，避免各帧返回格式不一致。
    static void enrich(DynamicCreateResult result) {
        if (result != null) enrich(result.getPost());
    }

    static void enrich(DynamicTimelinePage result) {
        if (result != null) enrich(result.getPosts());
    }

    static void enrich(DynamicDetailResult result) {
        if (result == null) return;
        enrich(result.getPost());
        enrich(result.getReplies());
    }

    private static void enrich(List<DynamicPostDTO> posts) {
        if (posts == null) return;
        for (DynamicPostDTO post : posts) enrich(post);
    }

    private static void enrich(DynamicPostDTO post) {
        if (post == null) return;
        DynamicAuthorDTO author = post.getAuthor();
        if (author != null) author.setAvatar(toClientAvatar(author.getAvatar()));
        enrich(post.getOriginalPost());
    }

    private static String toClientAvatar(String avatarPath) {
        if (avatarPath == null || avatarPath.trim().isEmpty()) return null;
        String value = avatarPath.trim();
        if (value.regionMatches(true, 0, "data:", 0, 5)) return value;
        if (value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8)) return value;

        // [修改] 已经是纯 Base64 的头像直接保留，避免把客户端可用数据误当成本机路径清空。
        byte[] decoded = decodeBase64(value);
        if (decoded != null && decoded.length > 0) return value;

        File avatarFile = new File(value);
        if (!avatarFile.isFile()) return null;
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(avatarFile.toPath()));
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
