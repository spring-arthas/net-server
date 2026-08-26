package com.alibaba.server.nio.media;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SafeFileResolver {
    private final Path rootPath;

    public SafeFileResolver(String rootPath) {
        this.rootPath = Paths.get(StringUtils.defaultIfBlank(rootPath, ".")).toAbsolutePath().normalize();
    }

    public File resolve(FileDto fileDto) throws IOException {
        if (fileDto == null || StringUtils.isBlank(fileDto.getFilePath())) {
            throw new IOException("file path is blank");
        }

        Path configuredPath = Paths.get(fileDto.getFilePath());
        Path candidate = configuredPath.isAbsolute()
                ? configuredPath.toAbsolutePath().normalize()
                : rootPath.resolve(configuredPath).normalize();

        if (!candidate.startsWith(rootPath)) {
            if (!configuredPath.isAbsolute()) {
                throw new IOException("file path escapes storage root");
            }
            candidate = rootPath.resolve(stripLeadingSeparator(fileDto.getFilePath())).normalize();
            if (!candidate.startsWith(rootPath)) {
                throw new IOException("file path escapes storage root");
            }
        }

        File file = candidate.toFile();
        return file;
    }

    private String stripLeadingSeparator(String value) {
        String result = value;
        while (result.startsWith("/") || result.startsWith("\\")) {
            result = result.substring(1);
        }
        return result;
    }
}
