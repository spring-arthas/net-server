package com.alibaba.server.nio.repository.dynamic.service.impl;

import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicDO;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicInteractionDO;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicInteractionRepository;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicMediaAccessRepository;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicRepository;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicActionResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicCreateResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicDetailResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicTimelinePage;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicMediaDTO;
import com.alibaba.server.nio.repository.dynamic.service.param.UserDynamicCreateParam;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UserDynamicServiceImplTest {

    private UserDynamicRepositorySpy dynamicRepository;
    private UserDynamicInteractionRepositorySpy interactionRepository;
    private UserDynamicMediaAccessRepositorySpy mediaAccessRepository;
    private UserDynamicServiceImpl service;

    @Before
    public void setUp() throws Exception {
        dynamicRepository = new UserDynamicRepositorySpy();
        interactionRepository = new UserDynamicInteractionRepositorySpy();
        mediaAccessRepository = new UserDynamicMediaAccessRepositorySpy();
        service = new UserDynamicServiceImpl();
        inject(service, "userDynamicRepository", dynamicRepository);
        inject(service, "interactionRepository", interactionRepository);
        inject(service, "mediaAccessRepository", mediaAccessRepository);
    }

    @Test
    public void timelineUsesLoggedInUserFriendScopeAndCursorLimit() {
        dynamicRepository.timelineRows = Arrays.asList(
                visibleDynamic(120L, 7L), visibleDynamic(119L, 9L), visibleDynamic(118L, 9L));

        DynamicTimelinePage page = service.timeline(7L, "FOLLOWING", 121L, 2);

        Assert.assertEquals(Long.valueOf(7L), dynamicRepository.timelineUserId);
        Assert.assertEquals("FOLLOWING", dynamicRepository.timelineScope);
        Assert.assertEquals(Long.valueOf(121L), dynamicRepository.timelineBeforeId);
        Assert.assertEquals(3, dynamicRepository.timelineLimit);
        Assert.assertEquals(2, page.getPosts().size());
        Assert.assertTrue(page.isHasMore());
        Assert.assertEquals(Long.valueOf(119L), page.getNextBeforeId());
    }

    @Test
    public void mineTimelineKeepsScopeAndClampsLimit() {
        service.timeline(7L, "MINE", null, 500);

        Assert.assertEquals("MINE", dynamicRepository.timelineScope);
        Assert.assertEquals(51, dynamicRepository.timelineLimit);
    }

    @Test(expected = IllegalArgumentException.class)
    public void timelineRejectsUnknownScope() {
        service.timeline(7L, "PUBLIC", null, 20);
    }

    @Test
    public void createAllowsFourOwnedImagesAndPersistsJsonWithoutLoggingPayload() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("");
        param.setMedia(Arrays.asList(
                media("IMAGE", 11L), media("IMAGE", 12L),
                media("IMAGE", 13L), media("IMAGE", 14L)));
        mediaAccessRepository.accessibleIds = Arrays.asList(11L, 12L, 13L, 14L);
        mediaAccessRepository.files = Arrays.asList(
                file(11L, "11.jpg", "jpg"), file(12L, "12.jpg", "jpg"),
                file(13L, "13.jpg", "jpg"), file(14L, "14.jpg", "jpg"));

        DynamicCreateResult result = service.create(7L, param);

        Assert.assertEquals(Long.valueOf(501L), result.getDynamicId());
        Assert.assertEquals(Long.valueOf(501L), result.getPost().getId());
        Assert.assertEquals(Long.valueOf(7L), dynamicRepository.inserted.getUserId());
        Assert.assertTrue(dynamicRepository.inserted.getMediaJson().contains("\"fileId\":11"));
        Assert.assertEquals(Arrays.asList(11L, 12L, 13L, 14L), mediaAccessRepository.requestedIds);
        Assert.assertEquals(1, mediaAccessRepository.calls);
    }

    @Test
    public void createResponseReturnsTheCanonicalAuthorAndCreatedTimestamp() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("hello");
        dynamicRepository.insertedResult = visibleDynamic(501L, 7L);
        dynamicRepository.insertedResult.setGmtCreated(new java.util.Date(1_786_500_000_000L));

        DynamicCreateResult result = service.create(7L, param);

        Assert.assertEquals("user7", result.getPost().getAuthor().getUsername());
        Assert.assertEquals("User 7", result.getPost().getAuthor().getNickname());
        Assert.assertEquals(1_786_500_000_000L, result.getPost().getCreatedAt());
    }

    @Test
    public void createDynamicKeepsLegacyNonNumericImagePathsWithoutFileIdValidation() {
        String legacyImagePaths = "/legacy/photos/first.jpg,/legacy/photos/second.png";

        Long dynamicId = service.createDynamic(7L, "legacy post", legacyImagePaths);

        Assert.assertEquals(Long.valueOf(501L), dynamicId);
        Assert.assertEquals(legacyImagePaths, dynamicRepository.inserted.getImagePaths());
        Assert.assertNull(dynamicRepository.inserted.getMediaJson());
        Assert.assertEquals(0, mediaAccessRepository.calls);
    }

    @Test
    public void explicitEmptyMediaStillValidatesNumericLegacyImagePaths() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("legacy ids");
        param.setImagePaths("11,12");
        param.setMedia(Collections.<DynamicMediaDTO>emptyList());
        mediaAccessRepository.accessibleIds = Arrays.asList(11L, 12L);
        mediaAccessRepository.files = Arrays.asList(file(11L, "one.jpg", "jpg"), file(12L, "two.png", "png"));

        service.create(7L, param);

        Assert.assertEquals(Arrays.asList(11L, 12L), mediaAccessRepository.requestedIds);
        Assert.assertEquals(2, dynamicRepository.inserted.getMediaJson().split("fileId").length - 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mediaTypeUsesStoredFileMetadataInsteadOfClientKind() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("spoofed");
        param.setMedia(Arrays.asList(media("IMAGE", 11L), media("IMAGE", 12L)));
        mediaAccessRepository.accessibleIds = Arrays.asList(11L, 12L);
        mediaAccessRepository.files = Arrays.asList(file(11L, "one.jpg", "jpg"), file(12L, "movie.mp4", "mp4"));

        service.create(7L, param);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedStoredFileTypeCannotBePublishedAsImage() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("spoofed file");
        param.setMedia(Collections.singletonList(media("IMAGE", 11L)));
        mediaAccessRepository.accessibleIds = Collections.singletonList(11L);
        mediaAccessRepository.files = Collections.singletonList(file(11L, "archive.dmg", "dmg"));

        service.create(7L, param);
    }

    @Test(expected = IllegalArgumentException.class)
    public void createRejectsMixedImageAndVideo() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("mixed");
        param.setMedia(Arrays.asList(media("IMAGE", 11L), media("VIDEO", 12L)));

        service.create(7L, param);
    }

    @Test(expected = IllegalArgumentException.class)
    public void createRejectsMoreThanFourImages() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("too many");
        param.setMedia(Arrays.asList(
                media("IMAGE", 11L), media("IMAGE", 12L), media("IMAGE", 13L),
                media("IMAGE", 14L), media("IMAGE", 15L)));

        service.create(7L, param);
    }

    @Test(expected = SecurityException.class)
    public void createRejectsAttachmentNotAccessibleToCurrentUser() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("private file");
        param.setMedia(Collections.singletonList(media("IMAGE", 11L)));
        mediaAccessRepository.accessibleIds = Collections.emptyList();
        mediaAccessRepository.files = Collections.emptyList();

        service.create(7L, param);
    }

    @Test(expected = SecurityException.class)
    public void createRejectsReferenceMediaNotAccessibleToCurrentUser() {
        UserDynamicCreateParam param = new UserDynamicCreateParam();
        param.setContent("shared file");
        com.alibaba.server.nio.repository.dynamic.service.dto.DynamicReferenceDTO reference =
                new com.alibaba.server.nio.repository.dynamic.service.dto.DynamicReferenceDTO();
        reference.setSourceType("driveFile");
        reference.setSourceId("88");
        reference.setMedia(Collections.singletonList(media("FILE", 88L)));
        param.setReference(reference);
        mediaAccessRepository.accessibleIds = Collections.emptyList();
        mediaAccessRepository.files = Collections.emptyList();

        service.create(7L, param);
    }

    @Test
    public void likeIsIdempotentAndReturnsCanonicalCounts() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 9L);
        interactionRepository.likeCount = 5;
        interactionRepository.replyCount = 2;
        interactionRepository.repostCount = 3;

        DynamicActionResult result = service.action(7L, 101L, "LIKE", null);
        service.action(7L, 101L, "LIKE", null);

        Assert.assertEquals(2, interactionRepository.upsertLikeCalls);
        Assert.assertEquals(5, result.getLikeCount());
        Assert.assertTrue(result.isLiked());
        Assert.assertEquals("LIKE", result.getAction());
    }

    @Test
    public void unlikeOnlyDeactivatesCurrentUsersLike() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 9L);

        DynamicActionResult result = service.action(7L, 101L, "UNLIKE", null);

        Assert.assertEquals(Long.valueOf(101L), interactionRepository.deactivatedDynamicId);
        Assert.assertEquals(Long.valueOf(7L), interactionRepository.deactivatedUserId);
        Assert.assertEquals("LIKE", interactionRepository.deactivatedActionType);
        Assert.assertFalse(result.isLiked());
    }

    @Test
    public void repostAndUnrepostAreIdempotentPerCurrentUser() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 9L);
        interactionRepository.repostCount = 1;

        DynamicActionResult repost = service.action(7L, 101L, "REPOST", null);
        DynamicActionResult unrepost = service.action(7L, 101L, "UNREPOST", null);

        Assert.assertEquals(1, interactionRepository.upsertRepostCalls);
        Assert.assertTrue(repost.isReposted());
        Assert.assertEquals("REPOST", repost.getAction());
        Assert.assertEquals("REPOST", interactionRepository.deactivatedActionType);
        Assert.assertFalse(unrepost.isReposted());
    }

    @Test
    public void replyPersistsTrimmedContentAndReturnsCanonicalCount() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 9L);
        interactionRepository.replyCount = 4;

        DynamicActionResult result = service.action(7L, 101L, "REPLY", "  收到  ");

        Assert.assertEquals("收到", interactionRepository.lastReplyContent);
        Assert.assertEquals("收到", result.getContent());
        Assert.assertEquals(4, result.getReplyCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void replyRejectsContentLongerThanTwoHundredEightyCharacters() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 9L);
        char[] chars = new char[281];
        Arrays.fill(chars, 'a');
        service.action(7L, 101L, "REPLY", new String(chars));
    }

    @Test
    public void detailReturnsVisiblePostAndCursorPagedReplies() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 9L);
        interactionRepository.replies = Arrays.asList(
                reply(202L, 101L, 8L, "second"),
                reply(201L, 101L, 7L, "first"),
                reply(200L, 101L, 8L, "extra"));

        DynamicDetailResult result = service.detail(7L, 101L, 203L, 2);

        Assert.assertEquals(Long.valueOf(101L), result.getPost().getId());
        Assert.assertEquals(2, result.getReplies().size());
        Assert.assertTrue(result.isHasMore());
        Assert.assertEquals(Long.valueOf(201L), result.getNextBeforeReplyId());
        Assert.assertEquals(Long.valueOf(203L), interactionRepository.replyBeforeId);
        Assert.assertEquals(3, interactionRepository.replyLimit);
    }

    @Test(expected = SecurityException.class)
    public void actionRejectsDynamicOutsideCurrentUsersVisibility() {
        dynamicRepository.visibleDynamic = null;
        service.action(7L, 101L, "LIKE", null);
    }

    @Test(expected = SecurityException.class)
    public void deleteRejectsDynamicOwnedByAnotherUser() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 9L);
        service.delete(7L, 101L);
    }

    @Test
    public void deleteLogicallyDeletesOwnDynamic() {
        dynamicRepository.visibleDynamic = visibleDynamic(101L, 7L);
        service.delete(7L, 101L);
        Assert.assertEquals(Long.valueOf(101L), dynamicRepository.deletedId);
        Assert.assertEquals(Long.valueOf(7L), dynamicRepository.deletedOwnerId);
    }

    private static DynamicMediaDTO media(String kind, Long fileId) {
        DynamicMediaDTO value = new DynamicMediaDTO();
        value.setKind(kind);
        value.setFileId(fileId);
        value.setFileName(fileId + ".jpg");
        value.setFileSize(100L);
        value.setMimeType("IMAGE".equals(kind) ? "image/jpeg" : "video/mp4");
        return value;
    }

    private static com.alibaba.server.nio.repository.file.repository.dataobject.FileDo file(
            Long id, String fileName, String fileType) {
        com.alibaba.server.nio.repository.file.repository.dataobject.FileDo value =
                new com.alibaba.server.nio.repository.file.repository.dataobject.FileDo();
        value.setId(id);
        value.setFileName(fileName);
        value.setFileType(fileType);
        value.setFileSize(100L);
        return value;
    }

    private static UserDynamicDO visibleDynamic(Long id, Long userId) {
        UserDynamicDO value = new UserDynamicDO();
        value.setId(id);
        value.setUserId(userId);
        value.setContent("hello");
        value.setUserName("user" + userId);
        value.setNickName("User " + userId);
        value.setDel("N");
        return value;
    }

    private static UserDynamicInteractionDO reply(Long id, Long dynamicId, Long userId, String content) {
        UserDynamicInteractionDO value = new UserDynamicInteractionDO();
        value.setId(id);
        value.setDynamicId(dynamicId);
        value.setUserId(userId);
        value.setActionType("REPLY");
        value.setContent(content);
        value.setUserName("user" + userId);
        value.setNickName("User " + userId);
        return value;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class UserDynamicRepositorySpy implements UserDynamicRepository {
        private Long timelineUserId;
        private String timelineScope;
        private Long timelineBeforeId;
        private int timelineLimit;
        private List<UserDynamicDO> timelineRows = Collections.emptyList();
        private UserDynamicDO visibleDynamic;
        private UserDynamicDO inserted;
        private UserDynamicDO insertedResult;
        private Long deletedId;
        private Long deletedOwnerId;

        @Override
        public void insertDynamic(UserDynamicDO dynamic) {
            inserted = dynamic;
            dynamic.setId(501L);
        }

        @Override
        public UserDynamicDO selectOwnedById(Long userId, Long dynamicId) {
            return insertedResult == null ? inserted : insertedResult;
        }

        @Override
        public List<UserDynamicDO> selectVisibleTimeline(Long userId, String scope, Long beforeId, int limit) {
            timelineUserId = userId;
            timelineScope = scope;
            timelineBeforeId = beforeId;
            timelineLimit = limit;
            return timelineRows;
        }

        @Override
        public UserDynamicDO selectVisibleById(Long userId, Long dynamicId) {
            return visibleDynamic;
        }

        @Override
        public int logicalDeleteOwned(Long dynamicId, Long userId) {
            deletedId = dynamicId;
            deletedOwnerId = userId;
            return 1;
        }

        @Override
        public int countVisibleMediaReferences(Long userId, Long fileId) {
            return 0;
        }
    }

    private static class UserDynamicInteractionRepositorySpy implements UserDynamicInteractionRepository {
        private int upsertLikeCalls;
        private int upsertRepostCalls;
        private String lastReplyContent;
        private int likeCount;
        private int replyCount;
        private int repostCount;
        private Long deactivatedDynamicId;
        private Long deactivatedUserId;
        private String deactivatedActionType;
        private List<UserDynamicInteractionDO> replies = Collections.emptyList();
        private Long replyBeforeId;
        private int replyLimit;

        @Override
        public int upsertActive(UserDynamicInteractionDO interaction) {
            if ("LIKE".equals(interaction.getActionType())) {
                upsertLikeCalls++;
            }
            if ("REPOST".equals(interaction.getActionType())) {
                upsertRepostCalls++;
            }
            if ("REPLY".equals(interaction.getActionType())) {
                lastReplyContent = interaction.getContent();
            }
            return 1;
        }

        @Override
        public int deactivate(Long dynamicId, Long userId, String actionType) {
            deactivatedDynamicId = dynamicId;
            deactivatedUserId = userId;
            deactivatedActionType = actionType;
            return 1;
        }

        @Override
        public int countActive(Long dynamicId, String actionType) {
            if ("LIKE".equals(actionType)) return likeCount;
            if ("REPLY".equals(actionType)) return replyCount;
            if ("REPOST".equals(actionType)) return repostCount;
            return 0;
        }

        @Override
        public int existsActive(Long dynamicId, Long userId, String actionType) {
            if (("LIKE".equals(actionType) || "REPOST".equals(actionType))
                    && deactivatedDynamicId != null && actionType.equals(deactivatedActionType)) return 0;
            return 1;
        }

        @Override
        public List<UserDynamicInteractionDO> selectReplies(Long dynamicId, Long beforeId, int limit) {
            replyBeforeId = beforeId;
            replyLimit = limit;
            return replies;
        }
    }

    private static class UserDynamicMediaAccessRepositorySpy implements UserDynamicMediaAccessRepository {
        private int calls;
        private List<Long> requestedIds = new ArrayList<Long>();
        private List<Long> accessibleIds = Collections.emptyList();
        private List<com.alibaba.server.nio.repository.file.repository.dataobject.FileDo> files =
                Collections.emptyList();

        @Override
        public List<Long> selectAccessibleFileIds(Long userId, List<Long> fileIds) {
            calls++;
            requestedIds = new ArrayList<Long>(fileIds);
            return accessibleIds;
        }

        @Override
        public List<com.alibaba.server.nio.repository.file.repository.dataobject.FileDo> selectAccessibleFiles(
                Long userId, List<Long> fileIds) {
            calls++;
            requestedIds = new ArrayList<Long>(fileIds);
            return files;
        }
    }
}
