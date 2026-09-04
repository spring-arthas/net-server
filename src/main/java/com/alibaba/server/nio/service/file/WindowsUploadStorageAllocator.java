package com.alibaba.server.nio.service.file;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows 上传盘路由器。预占记录在 JVM 内原子更新，避免两个并发任务同时看到同一份剩余空间。
 */
public final class WindowsUploadStorageAllocator {
    private static final String RESERVED_SPACE_KEY = "NIO.FILE.UPLOAD.RESERVED.SPACE.BYTES";
    private static final long DEFAULT_RESERVED_SPACE = 1024L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final Map<Path, Long> RESERVED_BYTES = new ConcurrentHashMap<>();

    private WindowsUploadStorageAllocator() {
    }

    public static Allocation reserve(Path primaryDirectory, long fileSize) throws IOException {
        if (fileSize < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        List<Path> roots = configuredRoots();
        if (roots.isEmpty()) {
            throw new IllegalStateException("Windows 文件存储根目录未配置");
        }
        Path normalizedPrimary = primaryDirectory.toAbsolutePath().normalize();
        Path configuredPrimary = roots.get(0);
        Path relativeDirectory = normalizedPrimary.startsWith(configuredPrimary)
                ? configuredPrimary.relativize(normalizedPrimary)
                : Paths.get("");
        long reservedSpace = configuredReservedSpace();
        synchronized (LOCK) {
            for (Path root : roots) {
                Path directory = root.resolve(relativeDirectory).normalize();
                if (!directory.startsWith(root)) {
                    continue;
                }
                if (!hasCapacity(root, fileSize, reservedSpace)) {
                    continue;
                }
                Files.createDirectories(directory);
                long current = RESERVED_BYTES.containsKey(root) ? RESERVED_BYTES.get(root) : 0L;
                RESERVED_BYTES.put(root, addExact(current, fileSize));
                return new Allocation(root, directory, fileSize);
            }
        }
        throw new IOException("所有 Windows 上传盘空间不足，文件大小=" + fileSize
                + "，预留空间=" + reservedSpace);
    }

    /** 断点续传固定使用原文件所在盘，只为剩余字节建立预占。 */
    public static Allocation reserveAt(Path storagePath, long fileSize) throws IOException {
        if (fileSize < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        Path normalizedPath = storagePath.toAbsolutePath().normalize();
        synchronized (LOCK) {
            for (Path root : configuredRoots()) {
                if (normalizedPath.startsWith(root) && hasCapacity(root, fileSize, 0L)) {
                    long current = RESERVED_BYTES.containsKey(root) ? RESERVED_BYTES.get(root) : 0L;
                    RESERVED_BYTES.put(root, addExact(current, fileSize));
                    return new Allocation(root, normalizedPath.getParent(), fileSize);
                }
            }
        }
        throw new IOException("断点续传原存储盘空间不足: " + normalizedPath);
    }

    public static void release(Allocation allocation) {
        if (allocation == null) {
            return;
        }
        synchronized (LOCK) {
            Path root = allocation.getRoot();
            long current = RESERVED_BYTES.containsKey(root) ? RESERVED_BYTES.get(root) : 0L;
            long remaining = Math.max(0L, current - allocation.getReservedBytes());
            if (remaining == 0L) {
                RESERVED_BYTES.remove(root);
            } else {
                RESERVED_BYTES.put(root, remaining);
            }
        }
    }

    public static List<Path> configuredRoots() {
        List<Path> result = new ArrayList<>();
        for (String configured : StorageRootResolver.resolveWindowsRoots(BasicServer.getMap())) {
            if (StringUtils.isBlank(configured)) {
                continue;
            }
            result.add(Paths.get(configured.trim()).toAbsolutePath().normalize());
        }
        return result;
    }

    private static boolean hasCapacity(Path root, long fileSize, long reservedSpace) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        FileStore store = Files.getFileStore(root);
        long usable = store.getUsableSpace();
        long active = RESERVED_BYTES.containsKey(root) ? RESERVED_BYTES.get(root) : 0L;
        return usable >= fileSize && usable - fileSize - active >= reservedSpace;
    }

    private static long configuredReservedSpace() {
        Object value = BasicServer.getMap().get(RESERVED_SPACE_KEY);
        if (value == null) {
            return DEFAULT_RESERVED_SPACE;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_RESERVED_SPACE;
        }
    }

    private static long addExact(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            throw new IllegalArgumentException("上传预占空间溢出");
        }
        return left + right;
    }

    public static final class Allocation {
        private final Path root;
        private final Path directory;
        private final long reservedBytes;

        private Allocation(Path root, Path directory, long reservedBytes) {
            this.root = root;
            this.directory = directory;
            this.reservedBytes = reservedBytes;
        }

        public Path getRoot() { return root; }
        public Path getDirectory() { return directory; }
        public long getReservedBytes() { return reservedBytes; }
    }
}
