package com.alibaba.server.nio.service.thumbnail;

import lombok.extern.slf4j.Slf4j;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端缩略图服务。
 *
 * 职责：
 * 1. 上传完成后异步生成图片/视频缩略图，存到磁盘
 * 2. 客户端请求缩略图时读取已生成的缩略图；不存在则按需懒加载生成
 * 3. 缩略图统一为 JPEG，最大边 480px
 *
 * 存储路径：{文件所在目录}/_thumbnails/{fileId}.jpg
 * （_thumbnails 目录不会出现在文件列表中，因为列表从数据库查询而非文件系统扫描）
 */
@Slf4j
public final class ThumbnailService {

    /** 缩略图最大边（像素） */
    public static final int THUMBNAIL_MAX_DIMENSION = 480;

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
     * 异步生成缩略图（上传完成后调用，不阻塞上传 ACK）。
     * 如果缩略图已存在则跳过。
     */
    public static void generateAsync(Long fileId, String filePath, String fileName) {
        if (fileId == null || filePath == null || !supports(fileName)) {
            return;
        }
        Path thumbPath = thumbnailPath(filePath, fileId);
        if (Files.exists(thumbPath)) {
            return; // 已生成过
        }
        GENERATOR.submit(() -> {
            try {
                generateInternal(fileId, filePath, fileName);
            } catch (Exception e) {
                log.warn("异步生成缩略图失败: fileId={}, fileName={}, error={}",
                        fileId, fileName, e.getMessage());
            }
        });
    }

    /**
     * 获取缩略图字节数据。
     * 如果缩略图不存在则同步生成（懒加载），生成失败返回 null。
     *
     * @return JPEG 缩略图字节数据，或 null（不支持/生成失败）
     */
    public static byte[] getOrGenerate(Long fileId, String filePath, String fileName) {
        if (fileId == null || filePath == null || !supports(fileName)) {
            return null;
        }
        Path thumbPath = thumbnailPath(filePath, fileId);
        try {
            if (Files.exists(thumbPath)) {
                return Files.readAllBytes(thumbPath);
            }
            // 懒加载：同步生成
            if (generateInternal(fileId, filePath, fileName)) {
                if (Files.exists(thumbPath)) {
                    return Files.readAllBytes(thumbPath);
                }
            }
        } catch (IOException e) {
            log.warn("读取缩略图失败: fileId={}, error={}", fileId, e.getMessage());
        }
        return null;
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

    private static BufferedImage generateImageThumbnail(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        return ImageIO.read(file);
    }

    private static BufferedImage generateVideoThumbnail(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
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
