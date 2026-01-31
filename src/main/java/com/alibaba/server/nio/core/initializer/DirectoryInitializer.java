package com.alibaba.server.nio.core.initializer;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.common.OSinfo;
import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 目录初始化器
 * 在服务启动时自动创建基础目录并写入数据库
 * 
 * @author spring
 */
@Slf4j
public class DirectoryInitializer {

    /**
     * 根目录的父ID
     */
    private static final Long ROOT_PARENT_ID = -1L;

    /**
     * 执行目录初始化
     * 
     * @throws Exception 初始化失败时抛出异常
     */
    public static void initialize(UserDTO userDTO) throws Exception {
        log.info("[ {} ] DirectoryInitializer | --> 开始执行目录初始化...", 
                LocalTime.formatDate(LocalDateTime.now()));

        try {
            // 1. 获取基础目录路径
            String baseDirectoryPath = getBaseDirectoryPath() + File.separator + userDTO.getUserName();
            if (baseDirectoryPath == null || baseDirectoryPath.trim().isEmpty()) {
                log.warn("DirectoryInitializer: 未配置基础目录路径，跳过初始化");
                return;
            }
            log.info("DirectoryInitializer: 基础目录路径 = {}", baseDirectoryPath);

            // 2. 确保文件系统目录存在
            ensureDirectoryExists(baseDirectoryPath);

            // 3. 确保数据库记录存在
            ensureDatabaseRecordExists(baseDirectoryPath);

            log.info("[ {} ] DirectoryInitializer | --> 目录初始化完成", 
                    LocalTime.formatDate(LocalDateTime.now()));

        } catch (Exception e) {
            log.error("DirectoryInitializer: 目录初始化失败", e);
            throw e;
        }
    }

    /**
     * 根据操作系统类型获取基础目录路径
     * 
     * @return 基础目录路径
     */
    private static String getBaseDirectoryPath() {
        String path = null;
        
        // 判断操作系统类型
        if (OSinfo.isWindows()) {
            // Windows 系统
            path = NioServerContext.getValue(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS);
            log.info("DirectoryInitializer: 检测到 Windows 操作系统，使用配置项 NIO.FILE.BASE.PATH.WINDOWS");
        } else if (OSinfo.isLinux() || OSinfo.isMacOS() || OSinfo.isMacOSX()) {
            // Linux 或 Mac 系统
            path = NioServerContext.getValue(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC);
            log.info("DirectoryInitializer: 检测到 Linux/Mac 操作系统，使用配置项 NIO.FILE.BASE.PATH.LINUX.MAC");
        } else {
            log.warn("DirectoryInitializer: 未识别的操作系统类型，尝试使用 Linux/Mac 配置");
            path = NioServerContext.getValue(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC);
        }

        return path != null ? path.trim() : null;
    }

    /**
     * 确保文件系统目录存在
     * 
     * @param directoryPath 目录路径
     */
    private static void ensureDirectoryExists(String directoryPath) {
        File directory = new File(directoryPath);
        
        if (directory.exists()) {
            if (directory.isDirectory()) {
                log.info("DirectoryInitializer: 目录已存在 = {}", directoryPath);
            } else {
                log.warn("DirectoryInitializer: 路径存在但不是目录 = {}", directoryPath);
            }
        } else {
            // 创建目录（包括所有必需的父目录）
            boolean created = directory.mkdirs();
            if (created) {
                log.info("DirectoryInitializer: 成功创建目录 = {}", directoryPath);
            } else {
                log.error("DirectoryInitializer: 创建目录失败 = {}", directoryPath);
                throw new RuntimeException("创建目录失败: " + directoryPath);
            }
        }
    }

    /**
     * 确保数据库记录存在
     * 
     * @param directoryPath 目录路径
     */
    private static void ensureDatabaseRecordExists(String directoryPath) {
        try {
            // 获取 FileService 实例
            FileService fileService = NioServerContext.getFileService();
            if (fileService == null) {
                log.error("DirectoryInitializer: 无法获取 FileService 实例");
                throw new RuntimeException("无法获取 FileService 实例");
            }

            // 从路径中提取目录名
            File directory = new File(directoryPath);
            String dirName = directory.getName();

            // 查询数据库中是否已存在该根目录记录
            FileQueryParam queryParam = new FileQueryParam();
            queryParam.setPId(ROOT_PARENT_ID);
            queryParam.setFileName(dirName);
            queryParam.setFilePath(directoryPath);
            queryParam.setIsFile(YesOrNoEnum.N.name());
            queryParam.setIsExist(YesOrNoEnum.Y.name());

            // 检查是否已存在
            if (checkDirectoryRecordExists(fileService, directoryPath)) {
                log.info("DirectoryInitializer: 数据库记录已存在，路径 = {}", directoryPath);
                return;
            }

            // 创建数据库记录
            try {
                FileDto fileDto = fileService.createDirectory(ROOT_PARENT_ID, dirName);
                log.info("DirectoryInitializer: 成功创建数据库记录，目录ID = {}, 目录名 = {}, 路径 = {}", 
                        fileDto.getId(), dirName, directoryPath);
            } catch (IllegalArgumentException e) {
                // 可能是因为已存在同名目录，检查一下
                if (e.getMessage().contains("同级目录已存在同名目录")) {
                    log.info("DirectoryInitializer: 数据库记录已存在（同名检测）");
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            log.error("DirectoryInitializer: 创建数据库记录失败", e);
            throw new RuntimeException("创建数据库记录失败", e);
        }
    }

    /**
     * 检查目录记录是否已存在于数据库
     * 
     * @param fileService FileService 实例
     * @param directoryPath 目录路径
     * @return true 如果记录已存在
     */
    private static boolean checkDirectoryRecordExists(FileService fileService, String directoryPath) {
        try {
            // 通过查询父ID为-1的所有记录来检查
            FileQueryParam queryParam = new FileQueryParam();
            queryParam.setPId(ROOT_PARENT_ID);
            queryParam.setIsFile(YesOrNoEnum.N.name());
            queryParam.setIsExist(YesOrNoEnum.Y.name());
            
            // 注意：这里需要使用 FileService 的查询方法
            // 由于 FileService 接口没有直接的查询方法，我们通过 createDirectory 的异常来判断
            return false;
        } catch (Exception e) {
            log.debug("DirectoryInitializer: 检查记录存在性时出错", e);
            return false;
        }
    }
}
