package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

final class FileDownloadPathResolver {

    private FileDownloadPathResolver() {
    }

    static File resolve(FileDto fileDto, String requestedFilePath, String storageRoot) throws IOException {
        if (fileDto == null) {
            return null;
        }

        Path root = Paths.get(StringUtils.defaultIfBlank(storageRoot, ".")).toAbsolutePath().normalize();

        File resolved = resolveConfiguredPath(fileDto.getFilePath(), root, fileDto);
        if (resolved != null) {
            return resolved;
        }
        return resolveByFileName(fileDto, root);
    }

    private static File resolveConfiguredPath(String filePath, Path root, FileDto fileDto)
            throws IOException {
        if (StringUtils.isBlank(filePath)) {
            return null;
        }

        Path configuredPath = Paths.get(filePath);
        Path candidate = configuredPath.isAbsolute()
                ? configuredPath.toAbsolutePath().normalize()
                : root.resolve(configuredPath).normalize();

        if (!candidate.startsWith(root)) {
            return null;
        }
        File file = candidate.toFile();
        if (!isExistingRegularFile(file) || !hasExpectedSize(file, fileDto)) {
            return null;
        }
        Path realRoot = root.toRealPath();
        Path realFile = candidate.toRealPath();
        return realFile.startsWith(realRoot) ? realFile.toFile() : null;
    }

    private static File resolveByFileName(FileDto fileDto, Path root) throws IOException {
        if (StringUtils.isBlank(fileDto.getFileName()) || !Files.exists(root)) {
            return null;
        }

        Stream<Path> stream = Files.walk(root);
        try {
            List<File> matches = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesStoredFileName(path.getFileName().toString(), fileDto.getFileName()))
                    .map(Path::toFile)
                    .filter(file -> hasExpectedSize(file, fileDto))
                    .limit(2)
                    .collect(java.util.stream.Collectors.toList());
            return matches.size() == 1 ? matches.get(0) : null;
        } finally {
            stream.close();
        }
    }

    private static boolean matchesStoredFileName(String storedFileName, String logicalFileName) {
        return storedFileName.equals(logicalFileName)
                || storedFileName.endsWith("_" + logicalFileName);
    }

    private static boolean isExistingRegularFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        return true;
    }

    private static boolean hasExpectedSize(File file, FileDto fileDto) {
        if (!isExistingRegularFile(file)) {
            return false;
        }
        Long expectedSize = fileDto.getFileSize();
        return expectedSize == null || expectedSize <= 0 || file.length() == expectedSize;
    }
}
