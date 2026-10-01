package com.alibaba.server.nio.service.thumbnail;

import lombok.extern.slf4j.Slf4j;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端缩略图服务。
 *
 * 职责：
 * 1. 上传完成后异步生成图片/视频缩略图，存到磁盘
 * 2. 客户端请求缩略图时读取已生成的缩略图；不存在则触发异步生成（不阻塞调用方）
 * 3. 缩略图统一为 JPEG，最大边 480px
 *
 * 安全设计：
 * - getIfExists 只读不生成，调用方（控制端口 worker）不会被缩略图生成阻塞
 * - generateAsync 异步提交到单线程生成器，避免大文件抽帧占用过多 CPU
 * - 生成中去重：同一 fileId 并发请求只生成一次
 * - 失败缓存：生成失败的 fileId 5 分钟内不重试，避免损坏文件反复消耗 CPU
 * - 视频抽帧 30 秒超时，防止 jcodec 挂死
 * - 图片解码限制最大源尺寸 2000px，防止超大图片 OOM
 *
 * 存储路径：{文件所在目录}/_thumbnails/{fileId}.jpg
 */
@Slf4j
public final class ThumbnailService {

    /** 缩略图最大边（像素） */
    public static final int THUMBNAIL_MAX_DIMENSION = 480;

    /** 图片解码时限制的最大源尺寸（像素），防止超大图片 OOM */
    private static final int MAX_SOURCE_DIMENSION = 2000;

    /** 视频抽帧超时（秒） */
    private static final long VIDEO_FRAME_TIMEOUT_SECONDS = 30;

    /** 生成失败后冷却时间（毫秒），5 分钟内不重试 */
    private static final long FAILURE_COOLDOWN_MS = 5 * 60 * 1000;

    /** 缩略图子目录名 */
    private static final String THUMBNAIL_DIR_NAME = "_thumbnails";

    /** 支持的图片扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "tif"
    ));

    /** 支持的视频扩展名 */
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "mov", "avi", "mkv", "flv", "wmv", "webm", "m4v", "3gp", "ts", "mpg", "mpeg"
    ));

    /** 正在生成中的 fileId（去重，避免并发重复生成） */
    private static final ConcurrentHashMap<Long, Boolean> GENERATING = new ConcurrentHashMap<>();

    /** 最近生成失败的 fileId → 失败时间戳（冷却期内不重试） */
    private static final ConcurrentHashMap<Long, Long> FAILED = new ConcurrentHashMap<>();

    /** 内存缓存：最近访问的缩略图字节数据（LRU，最多 200 张，约 4-10MB），避免每次读磁盘 */
    private static final java.util.Map<Long, byte[]> MEM_CACHE = Collections.synchronizedMap(
            new java.util.LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, byte[]> eldest) {
                    return size() > 200;
                }
            });

    /** 缩略图生成专用线程池（单线程顺序处理，避免大文件抽帧占用过多 CPU） */
    private static final ExecutorService GENERATOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "THUMBNAIL_GEN_" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 视频抽帧专用线程池（独立于 GENERATOR，超时后可取消，不阻塞生成器） */
    private static final ExecutorService VIDEO_FRAME_EXTRACTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "THUMBNAIL_VIDEO_EXTRACT_" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private ThumbnailService() {
    }

    /**
     * 判断文件是否支持生成缩略图。
     */
    public static boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String ext = getExtension(fileName).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext);
    }

    /**
     * [修复] 只读取已生成的缩略图，不触发生成（不阻塞调用方线程）。
     * 客户端请求时调用：如果缩略图已存在直接返回；不存在则触发异步生成并返回 null，
     * 客户端回退到 range_pull 本地生成，下次请求时服务端缩略图通常已就绪。
     *
     * @return JPEG 缩略图字节数据，或 null（不存在/不支持/读取失败）
     */
    public static byte[] getIfExists(Long fileId, String filePath, String fileName) {
        if (fileId == null || filePath == null || !supports(fileName)) {
            return null;
        }
        // [优化] 先查内存缓存（LRU 200 张），命中直接返回，避免读磁盘
        byte[] cached = MEM_CACHE.get(fileId);
        if (cached != null) {
            return cached;
        }
        Path thumbPath = thumbnailPath(filePath, fileId);
        try {
            if (Files.exists(thumbPath)) {
                byte[] data = Files.readAllBytes(thumbPath);
                if (data != null && data.length > 0) {
                    MEM_CACHE.put(fileId, data);
                    return data;
                }
            }
        } catch (IOException e) {
            log.warn("读取缩略图失败: fileId={}, error={}", fileId, e.getMessage());
        }
        // 不存在则触发异步生成（不阻塞），客户端下次请求时可用
        generateAsync(fileId, filePath, fileName);
        return null;
    }

    /**
     * 异步生成缩略图（上传完成后调用，不阻塞上传流程）。
     * 如果缩略图已存在或正在生成则跳过。
     */
    public static void generateAsync(Long fileId, String filePath, String fileName) {
        if (fileId == null || filePath == null || !supports(fileName)) {
            return;
        }
        // 失败冷却期内不重试
        Long failedAt = FAILED.get(fileId);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) {
            return;
        }
        // 生成中去重
        if (GENERATING.putIfAbsent(fileId, Boolean.TRUE) != null) {
            return; // 已在生成中
        }
        Path thumbPath = thumbnailPath(filePath, fileId);
        if (Files.exists(thumbPath)) {
            GENERATING.remove(fileId);
            return; // 已生成过
        }
        GENERATOR.submit(() -> {
            try {
                boolean success = generateInternal(fileId, filePath, fileName);
                if (!success) {
                    FAILED.put(fileId, System.currentTimeMillis());
                } else {
                    FAILED.remove(fileId);
                }
            } finally {
                GENERATING.remove(fileId);
            }
        });
    }

    /**
     * 检查缩略图是否已存在。
     */
    public static boolean exists(Long fileId, String filePath) {
        if (fileId == null || filePath == null) {
            return false;
        }
        return Files.exists(thumbnailPath(filePath, fileId));
    }

    // ==================== 内部实现 ====================

    private static Path thumbnailPath(String filePath, Long fileId) {
        Path parent = Paths.get(filePath).getParent();
        if (parent == null) {
            parent = Paths.get(".");
        }
        return parent.resolve(THUMBNAIL_DIR_NAME).resolve(fileId + ".jpg");
    }

    private static boolean generateInternal(Long fileId, String filePath, String fileName) {
        Path thumbPath = thumbnailPath(filePath, fileId);
        try {
            // 双重检查
            if (Files.exists(thumbPath)) {
                return true;
            }
            Files.createDirectories(thumbPath.getParent());

            String ext = getExtension(fileName).toLowerCase();
            BufferedImage image;

            if (IMAGE_EXTENSIONS.contains(ext)) {
                image = generateImageThumbnail(filePath);
            } else if (VIDEO_EXTENSIONS.contains(ext)) {
                image = generateVideoThumbnail(filePath);
            } else {
                return false;
            }

            if (image == null) {
                return false;
            }

            // 缩放
            BufferedImage scaled = scaleToMaxDimension(image, THUMBNAIL_MAX_DIMENSION);

            // 写出 JPEG
            File outFile = thumbPath.toFile();
            boolean written = ImageIO.write(scaled, "jpg", outFile);
            if (written && outFile.exists()) {
                log.info("缩略图生成成功: fileId={}, fileName={}, size={}KB",
                        fileId, fileName, outFile.length() / 1024);
                return true;
            } else {
                log.warn("缩略图写出失败: fileId={}, fileName={}", fileId, fileName);
                return false;
            }
        } catch (Exception e) {
            log.warn("缩略图生成异常: fileId={}, fileName={}, error={}",
                    fileId, fileName, e.getMessage());
            return false;
        }
    }

    /**
     * [修复] 图片解码限制最大源尺寸，防止超大图片 OOM。
     * 使用 ImageReader + setSourceRenderSize 在解码时直接缩放到限制尺寸，
     * 避免 ImageIO.read() 全尺寸解码。
     */
    private static BufferedImage generateImageThumbnail(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        // 超过 50MB 的图片直接跳过，避免 OOM
        if (file.length() > 50 * 1024 * 1024) {
            log.warn("图片过大跳过缩略图生成: filePath={}, size={}MB", filePath, file.length() / 1024 / 1024);
            return null;
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                // 回退到 ImageIO.read
                return ImageIO.read(file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                ImageReadParam param = reader.getDefaultReadParam();
                // 如果图片超过限制尺寸，解码时直接缩放到限制尺寸
                if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) {
                    double scale = (double) MAX_SOURCE_DIMENSION / Math.max(width, height);
                    int targetWidth = (int) Math.round(width * scale);
                    int targetHeight = (int) Math.round(height * scale);
                    param.setSourceRenderSize(new java.awt.Dimension(targetWidth, targetHeight));
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            log.warn("图片解码失败: filePath={}, error={}", filePath, e.getMessage());
            return null;
        } catch (Exception e) {
            // 某些格式 ImageReader 不支持 setSourceRenderSize，回退
            try {
                return ImageIO.read(file);
            } catch (IOException ioe) {
                return null;
            }
        }
    }

    /**
     * [修复] 视频抽帧加 30 秒超时，防止 jcodec 挂死。
     * 使用独立线程池执行抽帧，超时后取消并返回 null。
     */
    private static BufferedImage generateVideoThumbnail(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        // 超过 2GB 的视频跳过抽帧
        if (file.length() > 2L * 1024 * 1024 * 1024) {
            log.warn("视频过大跳过缩略图生成: filePath={}, size={}GB", filePath, file.length() / 1024 / 1024 / 1024);
            return null;
        }
        Future<BufferedImage> future = VIDEO_FRAME_EXTRACTOR.submit(() -> {
            try {
                // jcodec 抽帧：取第 1 秒的帧（大部分视频 1 秒处已有内容）
                Picture picture = FrameGrab.getFrameFromFile(file, 1);
                if (picture == null) {
                    // 回退到第 0 秒
                    picture = FrameGrab.getFrameFromFile(file, 0);
                }
                if (picture == null) {
                    return null;
                }
                return AWTUtil.toBufferedImage(picture);
            } catch (Exception e) {
                log.warn("视频抽帧失败: filePath={}, error={}", filePath, e.getMessage());
                return null;
            }
        });
        try {
            return future.get(VIDEO_FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("视频抽帧超时({}s): filePath={}", VIDEO_FRAME_TIMEOUT_SECONDS, filePath);
            return null;
        } catch (Exception e) {
            log.warn("视频抽帧任务异常: filePath={}, error={}", filePath, e.getMessage());
            return null;
        }
    }

    private static BufferedImage scaleToMaxDimension(BufferedImage original, int maxDimension) {
        int width = original.getWidth();
        int height = original.getHeight();
        if (width <= maxDimension && height <= maxDimension) {
            return original;
        }
        double scale = (double) maxDimension / Math.max(width, height);
        int thumbWidth = (int) Math.round(width * scale);
        int thumbHeight = (int) Math.round(height * scale);

        BufferedImage scaled = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, thumbWidth, thumbHeight, null);
        g.dispose();
        return scaled;
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }
}
