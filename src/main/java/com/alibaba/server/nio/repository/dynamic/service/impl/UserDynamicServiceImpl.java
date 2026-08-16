package com.alibaba.server.nio.repository.dynamic.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicDO;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicInteractionDO;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicInteractionRepository;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicMediaAccessRepository;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicRepository;
import com.alibaba.server.nio.repository.dynamic.service.UserDynamicService;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicActionResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicAuthorDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicCreateResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicDetailResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicMediaDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicPostDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicReferenceDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicTimelinePage;
import com.alibaba.server.nio.repository.dynamic.service.param.UserDynamicCreateParam;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 动态领域实现。事务内只访问本地 Mapper，不进行远程调用。 */
@Slf4j
public class UserDynamicServiceImpl implements UserDynamicService {
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int MAX_REPLY_LENGTH = 280;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_REFERENCE_MEDIA_COUNT = 4;

    @Autowired
    private UserDynamicRepository userDynamicRepository;
    @Autowired
    private UserDynamicInteractionRepository interactionRepository;
    @Autowired
    private UserDynamicMediaAccessRepository mediaAccessRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DynamicCreateResult create(Long userId, UserDynamicCreateParam param) {
        requireUser(userId);
        UserDynamicCreateParam normalized = normalizeCreateParam(param);
        validateCreate(userId, normalized);

        UserDynamicDO dynamic = new UserDynamicDO();
        dynamic.setUserId(userId);
        dynamic.setContent(normalized.getContent());
        dynamic.setImagePaths(normalized.getImagePaths());
        dynamic.setMediaJson(toOptionalJson(normalized.getMedia()));
        dynamic.setReferenceJson(toOptionalJson(normalized.getReference()));
        dynamic.setDel("N");
        userDynamicRepository.insertDynamic(dynamic);

        UserDynamicDO persisted = userDynamicRepository.selectOwnedById(userId, dynamic.getId());
        if (persisted == null) {
            // [修改] 单测桩或极端读延迟下仍返回已写入字段，真实数据库路径优先返回权威作者和时间。
            persisted = dynamic;
        }

        log.info("创建动态成功, userId={}, dynamicId={}, contentLength={}, mediaCount={}, hasReference={}",
                userId, dynamic.getId(), normalized.getContent().length(), normalized.getMedia().size(),
                normalized.getReference() != null);
        return new DynamicCreateResult(dynamic.getId(), toPost(persisted, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDynamic(Long userId, String content, String imagePaths) {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent(content);
        param.setImagePaths(imagePaths);
        // [修改] 旧协议中的 imagePaths 是服务端文件路径，不强转为新协议 fileId。
        param.setMedia(Collections.<DynamicMediaDTO>emptyList());
        return create(userId, param).getDynamicId();
    }

    @Override
    @Transactional(readOnly = true)
    public DynamicTimelinePage timeline(Long userId, String scope, Long beforeId, int limit) {
        requireUser(userId);
        String normalizedScope = normalizeScope(scope);
        int pageSize = normalizeLimit(limit);
        List<UserDynamicDO> rows = safeList(userDynamicRepository.selectVisibleTimeline(
                userId, normalizedScope, beforeId, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<UserDynamicDO> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        List<DynamicPostDTO> posts = pageRows.stream()
                .map(row -> toPost(row, userId))
                .collect(Collectors.toList());
        Long nextBeforeId = hasMore && !pageRows.isEmpty()
                ? pageRows.get(pageRows.size() - 1).getId() : null;
        return new DynamicTimelinePage(posts, nextBeforeId, hasMore);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DynamicActionResult action(Long userId, Long dynamicId, String action, String content) {
        UserDynamicDO dynamic = requireVisibleDynamic(userId, dynamicId);
        String normalizedAction = normalizeAction(action);
        String normalizedContent = null;

        if ("REPLY".equals(normalizedAction)) {
            normalizedContent = normalizeReply(content);
            UserDynamicInteractionDO reply = interaction(dynamicId, userId, "REPLY", normalizedContent,
                    "REPLY:" + UUID.randomUUID().toString());
            interactionRepository.upsertActive(reply);
        } else if ("LIKE".equals(normalizedAction) || "REPOST".equals(normalizedAction)) {
            interactionRepository.upsertActive(interaction(dynamicId, userId, normalizedAction, null,
                    normalizedAction + ":" + dynamicId + ":" + userId));
        } else if ("UNLIKE".equals(normalizedAction)) {
            interactionRepository.deactivate(dynamicId, userId, "LIKE");
        } else if ("UNREPOST".equals(normalizedAction)) {
            interactionRepository.deactivate(dynamicId, userId, "REPOST");
        }

        DynamicActionResult result = canonicalActionResult(userId, dynamic.getId(), normalizedAction);
        result.setContent(normalizedContent);
        log.info("动态互动完成, userId={}, dynamicId={}, action={}, likeCount={}, replyCount={}, repostCount={}",
                userId, dynamicId, normalizedAction, result.getLikeCount(), result.getReplyCount(),
                result.getRepostCount());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public DynamicDetailResult detail(Long userId, Long dynamicId, Long beforeReplyId, int limit) {
        UserDynamicDO dynamic = requireVisibleDynamic(userId, dynamicId);
        int pageSize = normalizeLimit(limit);
        List<UserDynamicInteractionDO> rows = safeList(interactionRepository.selectReplies(
                dynamicId, beforeReplyId, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<UserDynamicInteractionDO> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        List<DynamicPostDTO> replies = pageRows.stream()
                .map(row -> toReply(row, userId))
                .collect(Collectors.toList());
        Long nextBeforeReplyId = hasMore && !pageRows.isEmpty()
                ? pageRows.get(pageRows.size() - 1).getId() : null;
        return new DynamicDetailResult(toPost(dynamic, userId), replies, nextBeforeReplyId, hasMore);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long dynamicId) {
        UserDynamicDO dynamic = requireVisibleDynamic(userId, dynamicId);
        if (!userId.equals(dynamic.getUserId())) {
            throw new SecurityException("只能删除自己的动态");
        }
        if (userDynamicRepository.logicalDeleteOwned(dynamicId, userId) != 1) {
            throw new IllegalStateException("动态删除失败");
        }
        log.info("删除动态成功, userId={}, dynamicId={}", userId, dynamicId);
    }

    private UserDynamicCreateParam normalizeCreateParam(UserDynamicCreateParam param) {
        if (param == null) {
            throw new IllegalArgumentException("动态请求不能为空");
        }
        param.setContent(param.getContent() == null ? "" : param.getContent().trim());
        if (param.getMedia() == null || (param.getMedia().isEmpty()
                && StringUtils.isNotBlank(param.getImagePaths()))) {
            List<DynamicMediaDTO> legacyMedia = mediaFromLegacyImagePaths(param.getImagePaths());
            if (param.getMedia() == null || !legacyMedia.isEmpty()) {
                param.setMedia(legacyMedia);
            }
        }
        if (param.getMedia() == null) {
            param.setMedia(Collections.<DynamicMediaDTO>emptyList());
        }
        if (!param.getMedia().isEmpty()) {
            // [修改] 新协议只从待校验 media 生成 imagePaths，忽略客户端额外塞入的未校验 ID。
            param.setImagePaths(param.getMedia().stream().map(DynamicMediaDTO::getFileId)
                    .filter(id -> id != null).map(String::valueOf).collect(Collectors.joining(",")));
        }
        return param;
    }

    private void validateCreate(Long userId, UserDynamicCreateParam param) {
        if (param.getContent().length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("动态内容不能超过500个字符");
        }
        if (param.getContent().isEmpty() && param.getMedia().isEmpty() && param.getReference() == null) {
            throw new IllegalArgumentException("请输入内容或添加媒体");
        }

        int declaredImageCount = 0;
        int declaredVideoCount = 0;
        List<Long> fileIds = new ArrayList<Long>();
        for (DynamicMediaDTO media : param.getMedia()) {
            if (media == null || media.getFileId() == null || media.getFileId() <= 0L) {
                throw new IllegalArgumentException("动态附件无效");
            }
            String declaredKind = normalizeMediaKind(media.getKind(), media.getMimeType());
            if ("IMAGE".equals(declaredKind)) declaredImageCount++;
            if ("VIDEO".equals(declaredKind)) declaredVideoCount++;
            fileIds.add(media.getFileId());
        }
        if (declaredImageCount > 4) throw new IllegalArgumentException("图片不能超过4张");
        if (declaredVideoCount > 1 || (declaredVideoCount > 0 && param.getMedia().size() > 1)) {
            throw new IllegalArgumentException("视频只能单独选择1个");
        }
        if (declaredImageCount > 0 && declaredImageCount != param.getMedia().size()) {
            throw new IllegalArgumentException("不能同时选择图片和其他文件");
        }
        // [修改] 引用卡片也可携带文件，必须和正文媒体一起批量校验访问权。
        if (param.getReference() != null && param.getReference().getMedia() != null) {
            if (param.getReference().getMedia().size() > MAX_REFERENCE_MEDIA_COUNT) {
                throw new IllegalArgumentException("动态引用附件不能超过4个");
            }
            for (DynamicMediaDTO media : param.getReference().getMedia()) {
                if (media == null || media.getFileId() == null || media.getFileId() <= 0L) {
                    throw new IllegalArgumentException("动态引用附件无效");
                }
                fileIds.add(media.getFileId());
            }
        }

        Map<Long, FileDo> accessibleFiles = validateFileAccess(userId, fileIds);
        int imageCount = 0;
        int videoCount = 0;
        for (DynamicMediaDTO media : param.getMedia()) {
            String kind = applyStoredFileMetadata(media, accessibleFiles.get(media.getFileId()), true);
            if ("IMAGE".equals(kind)) imageCount++;
            if ("VIDEO".equals(kind)) videoCount++;
        }
        if (param.getReference() != null && param.getReference().getMedia() != null) {
            for (DynamicMediaDTO media : param.getReference().getMedia()) {
                applyStoredFileMetadata(media, accessibleFiles.get(media.getFileId()), false);
            }
        }
        if (imageCount > 4) {
            throw new IllegalArgumentException("图片不能超过4张");
        }
        if (videoCount > 1 || (videoCount > 0 && param.getMedia().size() > 1)) {
            throw new IllegalArgumentException("视频只能单独选择1个");
        }
        if (imageCount > 0 && imageCount != param.getMedia().size()) {
            throw new IllegalArgumentException("不能同时选择图片和其他文件");
        }
    }

    private Map<Long, FileDo> validateFileAccess(Long userId, List<Long> fileIds) {
        if (fileIds.isEmpty()) return Collections.emptyMap();
        Set<Long> requested = new LinkedHashSet<Long>(fileIds);
        List<FileDo> accessible = safeList(mediaAccessRepository.selectAccessibleFiles(
                userId, new ArrayList<Long>(requested)));
        Map<Long, FileDo> byId = new LinkedHashMap<Long, FileDo>();
        for (FileDo file : accessible) {
            if (file != null && file.getId() != null) byId.put(file.getId(), file);
        }
        if (!byId.keySet().containsAll(requested)) {
            throw new SecurityException("动态附件无访问权限");
        }
        return byId;
    }

    private String applyStoredFileMetadata(DynamicMediaDTO media, FileDo file, boolean bodyMedia) {
        if (file == null) throw new SecurityException("动态附件无访问权限");
        String kind = storedMediaKind(file);
        if (bodyMedia && !"IMAGE".equals(kind) && !"VIDEO".equals(kind)) {
            throw new IllegalArgumentException("动态正文只支持图片或视频");
        }
        media.setKind(kind.toLowerCase(Locale.ROOT));
        media.setFileName(StringUtils.defaultString(file.getFileName()));
        media.setFileSize(file.getFileSize() == null ? 0L : file.getFileSize());
        media.setMimeType(storedMimeType(file, kind));
        return kind;
    }

    private String storedMediaKind(FileDo file) {
        String extension = StringUtils.defaultIfBlank(file.getFileType(), extension(file.getFileName()))
                .trim().toLowerCase(Locale.ROOT);
        if ("jpg".equals(extension) || "jpeg".equals(extension) || "png".equals(extension)
                || "gif".equals(extension) || "webp".equals(extension) || "heic".equals(extension)
                || "heif".equals(extension)) return "IMAGE";
        if ("mp4".equals(extension) || "m4v".equals(extension) || "mov".equals(extension)
                || "webm".equals(extension)) return "VIDEO";
        return "FILE";
    }

    private String storedMimeType(FileDo file, String kind) {
        String extension = StringUtils.defaultIfBlank(file.getFileType(), extension(file.getFileName()))
                .trim().toLowerCase(Locale.ROOT);
        if ("IMAGE".equals(kind)) return "image/" + ("jpg".equals(extension) ? "jpeg" : extension);
        if ("VIDEO".equals(kind)) {
            if ("mov".equals(extension)) return "video/quicktime";
            return "video/" + ("m4v".equals(extension) ? "mp4" : extension);
        }
        return "application/octet-stream";
    }

    private String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
    }

    private UserDynamicDO requireVisibleDynamic(Long userId, Long dynamicId) {
        requireUser(userId);
        if (dynamicId == null || dynamicId <= 0L) {
            throw new IllegalArgumentException("dynamicId不能为空");
        }
        UserDynamicDO dynamic = userDynamicRepository.selectVisibleById(userId, dynamicId);
        if (dynamic == null) {
            throw new SecurityException("动态不存在或无权访问");
        }
        return dynamic;
    }

    private DynamicActionResult canonicalActionResult(Long userId, Long dynamicId, String action) {
        DynamicActionResult result = new DynamicActionResult();
        result.setDynamicId(dynamicId);
        result.setAction(action);
        result.setLikeCount(interactionRepository.countActive(dynamicId, "LIKE"));
        result.setReplyCount(interactionRepository.countActive(dynamicId, "REPLY"));
        result.setRepostCount(interactionRepository.countActive(dynamicId, "REPOST"));
        result.setLiked(interactionRepository.existsActive(dynamicId, userId, "LIKE") > 0);
        result.setReposted(interactionRepository.existsActive(dynamicId, userId, "REPOST") > 0);
        return result;
    }

    private UserDynamicInteractionDO interaction(Long dynamicId, Long userId, String action,
            String content, String idempotencyKey) {
        UserDynamicInteractionDO value = new UserDynamicInteractionDO();
        value.setDynamicId(dynamicId);
        value.setUserId(userId);
        value.setActionType(action);
        value.setContent(content);
        value.setIdempotencyKey(idempotencyKey);
        value.setDel("N");
        return value;
    }

    private DynamicPostDTO toPost(UserDynamicDO dynamic, Long viewerId) {
        DynamicPostDTO value = new DynamicPostDTO();
        value.setId(dynamic.getId());
        value.setAuthor(new DynamicAuthorDTO(dynamic.getUserId(), empty(dynamic.getUserName()),
                firstNonBlank(dynamic.getNickName(), dynamic.getUserName()), dynamic.getAvatar()));
        value.setContent(empty(dynamic.getContent()));
        value.setMedia(parseMedia(dynamic));
        value.setReference(parseJson(dynamic.getReferenceJson(), DynamicReferenceDTO.class));
        value.setLikeCount(number(dynamic.getLikeCount()));
        value.setReplyCount(number(dynamic.getReplyCount()));
        value.setRepostCount(number(dynamic.getRepostCount()));
        value.setLiked(Boolean.TRUE.equals(dynamic.getLiked()));
        value.setReposted(Boolean.TRUE.equals(dynamic.getReposted()));
        value.setCreatedAt(time(dynamic.getGmtCreated()));
        value.setMine(viewerId.equals(dynamic.getUserId()));
        return value;
    }

    private DynamicPostDTO toReply(UserDynamicInteractionDO reply, Long viewerId) {
        DynamicPostDTO value = new DynamicPostDTO();
        value.setId(reply.getId());
        value.setAuthor(new DynamicAuthorDTO(reply.getUserId(), empty(reply.getUserName()),
                firstNonBlank(reply.getNickName(), reply.getUserName()), reply.getAvatar()));
        value.setContent(empty(reply.getContent()));
        value.setCreatedAt(time(reply.getGmtCreated()));
        value.setMine(viewerId.equals(reply.getUserId()));
        return value;
    }

    private List<DynamicMediaDTO> parseMedia(UserDynamicDO dynamic) {
        if (StringUtils.isNotBlank(dynamic.getMediaJson())) {
            try {
                return JSON.parseArray(dynamic.getMediaJson(), DynamicMediaDTO.class);
            } catch (RuntimeException e) {
                log.warn("动态媒体JSON解析失败, dynamicId={}", dynamic.getId());
            }
        }
        return mediaFromLegacyImagePaths(dynamic.getImagePaths());
    }

    private List<DynamicMediaDTO> mediaFromLegacyImagePaths(String imagePaths) {
        if (StringUtils.isBlank(imagePaths)) return new ArrayList<DynamicMediaDTO>();
        List<DynamicMediaDTO> media = new ArrayList<DynamicMediaDTO>();
        for (String part : imagePaths.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            try {
                DynamicMediaDTO item = new DynamicMediaDTO();
                item.setKind("image");
                item.setFileId(Long.valueOf(value));
                item.setFileName("");
                item.setFileSize(0L);
                item.setMimeType("image/*");
                media.add(item);
            } catch (NumberFormatException e) {
                // [修改] 旧客户端会传服务端路径；原样字段继续保留，新媒体列表只转换数字 fileId。
            }
        }
        return media;
    }

    private String normalizeScope(String scope) {
        String value = StringUtils.defaultIfBlank(scope, "FOLLOWING").trim().toUpperCase(Locale.ROOT);
        if (!"FOLLOWING".equals(value) && !"MINE".equals(value)) {
            throw new IllegalArgumentException("scope只支持FOLLOWING或MINE");
        }
        return value;
    }

    private String normalizeAction(String action) {
        String value = StringUtils.defaultString(action).trim().toUpperCase(Locale.ROOT);
        if (!"LIKE".equals(value) && !"UNLIKE".equals(value) && !"REPLY".equals(value)
                && !"REPOST".equals(value) && !"UNREPOST".equals(value)) {
            throw new IllegalArgumentException("不支持的动态操作");
        }
        return value;
    }

    private String normalizeReply(String content) {
        String value = StringUtils.defaultString(content).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("回复内容不能为空");
        if (value.length() > MAX_REPLY_LENGTH) throw new IllegalArgumentException("回复不能超过280个字符");
        return value;
    }

    private String normalizeMediaKind(String kind, String mimeType) {
        String value = StringUtils.defaultString(kind).trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty() && StringUtils.startsWithIgnoreCase(mimeType, "image/")) value = "IMAGE";
        if (value.isEmpty() && StringUtils.startsWithIgnoreCase(mimeType, "video/")) value = "VIDEO";
        if (value.isEmpty()) value = "FILE";
        if (!"IMAGE".equals(value) && !"VIDEO".equals(value) && !"FILE".equals(value)) {
            throw new IllegalArgumentException("不支持的动态附件类型");
        }
        return value;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0L) throw new SecurityException("请先登录");
    }

    private String toOptionalJson(Object value) {
        if (value == null) return null;
        if (value instanceof List && ((List<?>) value).isEmpty()) return null;
        return JSON.toJSONString(value);
    }

    private <T> T parseJson(String value, Class<T> type) {
        if (StringUtils.isBlank(value)) return null;
        try {
            return JSON.parseObject(value, type);
        } catch (RuntimeException e) {
            log.warn("动态引用JSON解析失败, type={}", type.getSimpleName());
            return null;
        }
    }

    private static int number(Integer value) { return value == null ? 0 : value; }
    private static long time(Date value) { return value == null ? 0L : value.getTime(); }
    private static String empty(String value) { return value == null ? "" : value; }
    private static String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : empty(second);
    }
    private static <T> List<T> safeList(List<T> value) {
        return value == null ? Collections.<T>emptyList() : value;
    }
}
