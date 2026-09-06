package com.alibaba.server.nio.media;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多盘安全文件解析器。
 * <p>
 * 支持 Windows 主盘/备用盘/自动发现盘共存的场景：文件可能被写入任意一个候选盘，
 * DB 中记录的是写入后的绝对路径。解析时对绝对路径采取「位于任一候选根内，或文件真实存在」即放行，
 * 避免把备用盘上的绝对路径错误拼接到主盘根下导致「文件不存在」。
 */
public class SafeFileResolver {
    private final Path rootPath;
    private final List<Path> roots;

    public SafeFileResolver(String rootPath) {
        this(rootPath == null ? Collections.<String>emptyList() : Collections.singletonList(rootPath));
    }

    public SafeFileResolver(List<String> rootPaths) {
        List<Path> list = new ArrayList<>();
        if (rootPaths != null) {
            for (String rp : rootPaths) {
                if (StringUtils.isBlank(rp)) {
                    continue;
                }
                Path normalized = canonicalPath(Paths.get(rp.trim()).toAbsolutePath().normalize());
                if (!list.contains(normalized)) {
                    list.add(normalized);
                }
            }
        }
        if (list.isEmpty()) {
            list.add(canonicalPath(Paths.get(".").toAbsolutePath().normalize()));
        }
        this.roots = Collections.unmodifiableList(list);
        this.rootPath = list.get(0);
    }

    public File resolve(FileDto fileDto) throws IOException {
        if (fileDto == null || StringUtils.isBlank(fileDto.getFilePath())) {
            throw new IOException("file path is blank");
        }

        Path configuredPath = Paths.get(fileDto.getFilePath());
        if (configuredPath.isAbsolute()) {
            Path candidate = canonicalPath(configuredPath.toAbsolutePath().normalize());
            // 绝对路径：位于任一候选存储根内，或文件真实存在（服务端写入的真实位置），即放行。
            if (withinAnyRoot(candidate) || candidate.toFile().exists()) {
                return candidate.toFile();
            }
            throw new IOException("file path escapes storage root");
        }

        // 相对路径：在主根下解析，并校验不能越界。
        Path candidate = canonicalPath(rootPath.resolve(configuredPath).normalize());
        if (!candidate.startsWith(rootPath)) {
            throw new IOException("file path escapes storage root");
        }
        return candidate.toFile();
    }

    private boolean withinAnyRoot(Path candidate) {
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static Path canonicalPath(Path path) {
        try {
            return path.toFile().getCanonicalFile().toPath().toAbsolutePath().normalize();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }
}
