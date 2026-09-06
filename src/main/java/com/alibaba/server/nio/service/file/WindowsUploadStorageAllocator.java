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
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WindowsUploadStorageAllocator.class);

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

        // 第一优先：可用 ≥ 文件大小 + 预留空间 + 已预占（标准安全判定）
        Allocation allocation = tryReserve(roots, relativeDirectory, fileSize, reservedSpace);
        if (allocation != null) {
            return allocation;
        }
        // 降级：只要某个盘可用 ≥ 文件大小 + 已预占，就接收（放弃预留空间，保证能上传）
        log.warn("所有 Windows 上传盘均不满足预留空间要求（预留={}字节），降级选择可用空间足够的盘",
                reservedSpace);
        allocation = tryReserve(roots, relativeDirectory, fileSize, 0L);
        if (allocation != null) {
            return allocation;
        }
        throw new IOException(buildInsufficientSpaceMessage(fileSize, reservedSpace, roots));
    }

    /** 按给定预留空间在候选盘中选第一个可接收的盘；单盘检查/建目录失败时跳过继续尝试，不中断。 */
    private static Allocation tryReserve(List<Path> roots, Path relativeDirectory,
            long fileSize, long reservedSpace) {
        synchronized (LOCK) {
            for (Path root : roots) {
                Path directory = root.resolve(relativeDirectory).normalize();
                if (!directory.startsWith(root)) {
                    continue;
                }
                boolean capacityOk;
                try {
                    capacityOk = hasCapacity(root, fileSize, reservedSpace);
                } catch (IOException e) {
                    log.warn("检查盘空间失败，跳过该盘: {}, error={}", root, e.getMessage());
                    continue;
                }
                if (!capacityOk) {
                    continue;
                }
                try {
                    Files.createDirectories(directory);
                } catch (IOException e) {
                    log.warn("创建上传目录失败，跳过该盘: {}, error={}", directory, e.getMessage());
                    continue;
                }
                long current = RESERVED_BYTES.containsKey(root) ? RESERVED_BYTES.get(root) : 0L;
                RESERVED_BYTES.put(root, addExact(current, fileSize));
                log.info("已选上传盘: root={}, directory={}, fileSize={}, 该盘当前可用(约)={}MB",
                        root, directory, fileSize, usableMbSafely(root));
                return new Allocation(root, directory, fileSize);
            }
        }
        return null;
    }

    /** 读取某盘当前可用空间（MB），失败返回 -1，仅用于日志。 */
    private static long usableMbSafely(Path root) {
        try {
            return Files.getFileStore(root).getUsableSpace() / 1024L / 1024L;
        } catch (IOException e) {
            return -1L;
        }
    }

    /** 汇总各候选盘的真实可用空间与已预占，便于一次定位是哪个盘、差多少。 */
    private static String buildInsufficientSpaceMessage(long fileSize, long reservedSpace, List<Path> roots) {
        StringBuilder sb = new StringBuilder("所有 Windows 上传盘空间不足，文件大小=")
                .append(fileSize).append(" 字节，预留空间=").append(reservedSpace).append(" 字节");
        for (Path root : roots) {
            long active = RESERVED_BYTES.containsKey(root) ? RESERVED_BYTES.get(root) : 0L;
            long usable = -1L;
            try {
                usable = Files.getFileStore(root).getUsableSpace();
            } catch (IOException ignored) {
                // 保留 -1 表示无法读取
            }
            sb.append("；[")
              .append(root)
              .append("] 可用=")
              .append(usable < 0 ? "未知(读取失败)" : (usable / 1024L / 1024L) + "MB")
              .append("，已预占=")
              .append(active / 1024L / 1024L)
              .append("MB，需可用≥")
              .append((fileSize + reservedSpace) / 1024L / 1024L)
              .append("MB");
        }
        return sb.toString();
    }

    /** 断点续传固定使用原文件所在盘，只为剩余字节建立预占。 */
    public static Allocation reserveAt(Path storagePath, long fileSize) throws IOException {
        if (fileSize < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        Path normalizedPath = storagePath.toAbsolutePath().normalize();
        synchronized (LOCK) {
            for (Path root : configuredRoots()) {
                if (!normalizedPath.startsWith(root)) {
                    continue;
                }
                boolean capacityOk;
                try {
                    capacityOk = hasCapacity(root, fileSize, 0L);
                } catch (IOException e) {
                    log.warn("断点续传检查盘空间失败，跳过该盘: {}, error={}", root, e.getMessage());
                    continue;
                }
                if (!capacityOk) {
                    continue;
                }
                long current = RESERVED_BYTES.containsKey(root) ? RESERVED_BYTES.get(root) : 0L;
                RESERVED_BYTES.put(root, addExact(current, fileSize));
                return new Allocation(root, normalizedPath.getParent(), fileSize);
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
        List<String> configuredStrings = StorageRootResolver.resolveWindowsRoots(BasicServer.getMap());
        List<Path> result = new ArrayList<>();
        for (String configured : configuredStrings) {
            if (StringUtils.isBlank(configured)) {
                continue;
            }
            Path normalized = Paths.get(configured.trim()).toAbsolutePath().normalize();
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        // 仅 Windows（配置了主盘）且候选盘不足 2 个时，自动发现机器上其他逻辑盘符，保证空间不足时仍有盘可切。
        if (!configuredStrings.isEmpty() && result.size() < 2) {
            List<Path> discovered = discoverOtherDriveRoots(result);
            if (!discovered.isEmpty()) {
                log.warn("备用盘未配置，已自动发现额外上传盘(按可用空间降序): {}", discovered);
                result.addAll(discovered);
            }
        }
        return result;
    }

    /** 扫描系统其他逻辑盘符，统一在其根下创建 storage/upload/file 作为候选上传目录，按可用空间降序返回。 */
    private static List<Path> discoverOtherDriveRoots(List<Path> configured) {
        java.util.Map<Path, Long> usableByRoot = new java.util.HashMap<>();
        for (java.io.File rootFile : java.io.File.listRoots()) {
            Path rootPath = rootFile.toPath();
            boolean known = false;
            for (Path c : configured) {
                if (rootPath.startsWith(c) || c.startsWith(rootPath)) {
                    known = true;
                    break;
                }
            }
            if (known) {
                continue;
            }
            Path dir = rootPath.resolve("storage").resolve("upload").resolve("file");
            try {
                Files.createDirectories(dir);
                long usable = Files.getFileStore(dir).getUsableSpace();
                if (usable > 0L) {
                    usableByRoot.put(dir.toAbsolutePath().normalize(), usable);
                }
            } catch (IOException ignored) {
                // 跳过不可用的盘（权限不足、盘不存在等）
            }
        }
        List<Path> discovered = new ArrayList<>();
        usableByRoot.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> discovered.add(e.getKey()));
        return discovered;
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
