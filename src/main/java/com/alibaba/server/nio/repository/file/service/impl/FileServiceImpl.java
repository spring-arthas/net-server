package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.common.SnowflakeIdWorkerUtil;
import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.param.PageQueryParam;
import com.alibaba.server.nio.core.result.PageResult;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.repository.file.mapper.FileRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.repository.param.FileDalQueryParam;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.dto.FilePageDto;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.repository.file.service.exception.DirectoryContainsFileException;
import com.alibaba.server.nio.repository.file.service.param.FileCreateParam;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.file.service.param.FileUpdateParam;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.util.LocalTime;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 文件服务
 * 
 * @author spring
 */

@Slf4j
@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private FileRepository fileRepository;

    private final ConcurrentMap<Long, UploadDirectoryLock> uploadDirectoryLocks = new ConcurrentHashMap<>();

    /**
     * @param fileQueryParam   文件查询param
     * @param completeFilePath 文件绝对路径
     * @param relativeFilePath 文件相对路径
     * @return
     */
    @Override
    public FileDto getFolderFileTree(FileQueryParam fileQueryParam, String completeFilePath, String relativeFilePath) {
        List<FileDo> fileDos = this.getAssignFiles(fileQueryParam);
        if (!CollectionUtils.isEmpty(fileDos)) {
            // 当前文件夹不为空，此时只查询子文件夹，不查询文件
            FileDto rootFileDto = this.doToDto(fileDos.get(0));

            // 当前文件夹节点是否含有子节点，不含有子文件夹，则查询是否含有文件信息
            if (!rootFileDto.getHasChild().equals(YesOrNoEnum.Y.name())) {
                PageResult<FileDo> fileDoPageResult = this.queryFilesBelongCurrentFolder(rootFileDto.getId(),
                        fileQueryParam.getCurrentPage(), fileQueryParam.getPageSize());
                if (!CollectionUtils.isEmpty(fileDoPageResult.getModelList())) {
                    rootFileDto.setFileCount(fileDoPageResult.getTotalCount());
                    final List<FileDto> fileDtoList = Lists.newArrayList();
                    fileDoPageResult.getModelList().stream().forEach(fileDo -> {
                        fileDtoList.add(this.doToDto(fileDo));
                    });
                    rootFileDto.setChildFileList(fileDtoList);
                }
                return rootFileDto;
            }

            // 查询当前节点下一节点信息
            FileQueryParam childFileQueryParam = new FileQueryParam();
            childFileQueryParam.setParentId(rootFileDto.getId());
            List<FileDo> childFileDos = this.getAssignFiles(childFileQueryParam);
            if (!CollectionUtils.isEmpty(childFileDos)) {
                List<FileDto> childFileList = Lists.newArrayList();

                for (FileDo childFileDo : childFileDos) {
                    childFileList.add(this.doToDto(childFileDo));
                }

                rootFileDto.setChildFileList(childFileList);
            }

            return rootFileDto;
        } else {
            // 为空，文件系统创建当前文件夹
            File file = new File(completeFilePath);
            if (!file.exists() && !file.isDirectory()) {
                // 创建多级文件夹
                file.mkdirs();
            }

            FileCreateParam fileCreateParam = new FileCreateParam();
            fileCreateParam.setParentId(-1L);
            fileCreateParam.setFileName(fileQueryParam.getFileName());
            fileCreateParam.setUserName(fileQueryParam.getUserName());
            // 保存相对路径
            fileCreateParam.setFilePath(relativeFilePath);
            fileCreateParam.setFileSize(fileQueryParam.getFileSize());
            fileCreateParam.setFileType("NOT_FILE");
            fileCreateParam.setIsFile(fileQueryParam.getIsFile());
            fileCreateParam.setIsExist(YesOrNoEnum.Y.name());
            fileCreateParam.setHasChild(YesOrNoEnum.N.name());
            FileDo fileDo = this.createParamToDo(fileCreateParam);
            this.fileRepository.insertSelective(fileDo);
            return this.doToDto(fileDo);
        }
    }

    /**
     * 查询当前文件夹下的文件
     * 
     * @param id          当前文件夹Id
     * @param currentPage 当前页
     * @param pageSize    数量
     * @return
     */
    private PageResult<FileDo> queryFilesBelongCurrentFolder(Long id, Integer currentPage, Integer pageSize) {
        FileDalQueryParam fileDalQueryParam = new FileDalQueryParam();
        fileDalQueryParam.setParentId(id);
        fileDalQueryParam.setIsExist(YesOrNoEnum.Y.name());
        fileDalQueryParam.setIsFile(YesOrNoEnum.Y.name());
        fileDalQueryParam.setDel(YesOrNoEnum.N.name());
        fileDalQueryParam.setCurrentPage(currentPage);
        fileDalQueryParam.setPageSize(pageSize);

        return this.fileRepository.queryPage(fileDalQueryParam);
    }

    @Override
    public FileDto create(FileQueryParam fileQueryParam, String completeFilePath) {
        // 1、判断当前新创建的文件夹路径是否存在记录，存在则无需创建，直接返回当前存在的文件
        List<FileDo> fileDos = this.getAssignFiles(fileQueryParam);
        if (!CollectionUtils.isEmpty(fileDos)) {
            FileDto fileDto = this.doToDto(fileDos.get(0));
            fileDto.setRepeatCreate(YesOrNoEnum.Y.name());
            return fileDto;
        }

        // 2、判断当前文件夹是否含有文件，含有则不能在当前文件夹路径下创建子文件夹
        FileQueryParam newFileQueryParam = new FileQueryParam();
        newFileQueryParam.setParentId(fileQueryParam.getParentId());
        newFileQueryParam.setFileType("FILE");
        newFileQueryParam.setIsFile(YesOrNoEnum.Y.name());
        newFileQueryParam.setIsExist(YesOrNoEnum.Y.name());
        newFileQueryParam.setCurrentPage(1);
        newFileQueryParam.setPageSize(2);
        PageResult<FileDo> pageResult = this.fileRepository.queryPage(this.createDalParam(newFileQueryParam));
        if (null != pageResult && pageResult.getTotalCount() > 0) {
            FileDto fileDto = new FileDto();
            fileDto.setId(fileQueryParam.getParentId());
            fileDto.setFileName(fileQueryParam.getFilePath());
            fileDto.setFileCount(pageResult.getTotalCount());
            return fileDto;
        }

        // 3、为空，文件系统创建当前文件夹
        File file = new File(completeFilePath);
        if (!file.exists() && !file.isDirectory()) {
            // 创建多级文件夹
            file.mkdirs();
        }

        // 创建新的文件夹节点
        FileCreateParam fileCreateParam = new FileCreateParam();
        fileCreateParam.setParentId(fileQueryParam.getParentId());
        fileCreateParam.setUserName(fileQueryParam.getUserName());
        fileCreateParam.setFileName(fileQueryParam.getFileName());
        fileCreateParam.setFilePath(fileQueryParam.getFilePath());
        fileCreateParam.setFileType(fileQueryParam.getFileType());
        fileCreateParam.setFileSize(fileQueryParam.getFileSize());
        fileCreateParam.setIsFile(fileQueryParam.getIsFile());
        fileCreateParam.setIsExist(YesOrNoEnum.Y.name());
        fileCreateParam.setHasChild(YesOrNoEnum.N.name());
        FileDo fileDo = this.createParamToDo(fileCreateParam);
        this.fileRepository.insertSelective(fileDo);

        // 更新父节点的hasChild状态为true
        fileDo = new FileDo();
        fileDo.setId(fileQueryParam.getParentId());
        fileDo.setHasChild(YesOrNoEnum.Y.name());
        this.fileRepository.updateSelective(fileDo);
        FileDto fileDto = this.doToDto(fileDo);
        fileDto.setRepeatCreate(YesOrNoEnum.N.name());
        return fileDto;
    }

    @Override
    public FileDto update(FileUpdateParam fileUpdateParam, String originFilePath, String completeFilePath) {
        File file = new File(originFilePath);
        if (file.exists() && file.isDirectory()) {
            // 创建多级文件夹
            file.renameTo(new File(completeFilePath));
        }

        FileDo fileDo = this.updateParamToDo(fileUpdateParam);
        this.fileRepository.updateSelective(fileDo);
        return this.doToDto(fileDo);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public FileDto createFile(FileQueryParam fileQueryParam, UserDTO userDTO) {
        List<FileDo> fileDos = this.getAssignFiles(fileQueryParam);
        if (CollectionUtils.isEmpty(fileDos)) {
            FileCreateParam fileCreateParam = new FileCreateParam();
            fileCreateParam.setParentId(fileQueryParam.getParentId());
            fileCreateParam.setFileName(fileQueryParam.getFileName());
            fileCreateParam.setFileSize(fileQueryParam.getFileSize());
            fileCreateParam.setUserName(fileQueryParam.getUserName());
            fileCreateParam.setFilePath(fileQueryParam.getFilePath());
            fileCreateParam.setFileType(fileQueryParam.getFileType());
            fileCreateParam.setIsFile(YesOrNoEnum.Y.name());
            fileCreateParam.setIsExist(YesOrNoEnum.Y.name());
            fileCreateParam.setUserId(Integer.valueOf(String.valueOf(userDTO.getId())));
            fileCreateParam.setUserName(userDTO.getUserName());
            fileCreateParam.setHasChild(YesOrNoEnum.N.name());
            FileDo fileDo = this.createParamToDo(fileCreateParam);
            this.fileRepository.insert(fileDo);
            log.info(
                    "[ " + LocalTime.formatDate(LocalDateTime.now())
                            + " ] FileServiceImpl | --> 当前用户 [{}] 成功持久化上传文件 [{}], 文件存储路径 [{}], thread = {}",
                    fileDo.getUserName(), fileDo.getFileName(), fileDo.getFilePath(),
                    Thread.currentThread().getName());
            return this.doToDto(fileDo);
        }
        return new FileDto();
    }

    @Override
    public Boolean deleteFile(FileQueryParam fileQueryParam, List<Long> fileIdList) {
        if (CollectionUtils.isEmpty(fileIdList)) {
            return Boolean.FALSE;
        }

        final Date date = new Date();
        List<FileDo> fileDoList = Lists.newArrayList();
        fileIdList.stream().forEach(fileId -> {
            FileUpdateParam fileUpdateParam = new FileUpdateParam();
            fileUpdateParam.setId(fileId);
            fileUpdateParam.setGmtModified(date);
            fileUpdateParam.setDel(YesOrNoEnum.Y.name());
            fileUpdateParam.setDelTime(date);

            // fileDoList.add(this.updateParamToDo(fileUpdateParam));
            this.fileRepository.updateSelective(this.updateParamToDo(fileUpdateParam));
        });

        // this.fileRepository.batchUpdateSelective(fileDoList);
        return Boolean.TRUE;
    }

    @Override
    public FileDto getFileById(FileQueryParam fileQueryParam) {
        FileDo fileDo = this.fileRepository.get(fileQueryParam.getId());
        if (Objects.isNull(fileDo)) {
            return null;
        }
        return this.doToDto(fileDo);
    }

    /**
     * 根据fileQueryParam指定的查询条件查询文件信息
     * 
     * @param fileQueryParam
     * @return
     */
    private List<FileDo> getAssignFiles(FileQueryParam fileQueryParam) {
        FileDalQueryParam fileDalQueryParam = this.createDalParam(fileQueryParam);
        return this.fileRepository.getAssignFiles(fileDalQueryParam);
    }

    private FileDo createParamToDo(FileCreateParam param) {
        FileDo fileDo = new FileDo();
        fileDo.setId(SnowflakeIdWorkerUtil.generateId());
        fileDo.setParentId(param.getParentId());
        fileDo.setUserName(param.getUserName());
        fileDo.setUserId(param.getUserId());
        fileDo.setFileName(param.getFileName());
        fileDo.setFilePath(param.getFilePath());
        fileDo.setFileSize(param.getFileSize());
        fileDo.setFileType(param.getFileType());
        fileDo.setIsFile(param.getIsFile());
        fileDo.setIsExist(param.getIsExist());
        fileDo.setHasChild(param.getHasChild());
        fileDo.setDel(YesOrNoEnum.N.name());
        Date date = new Date();
        fileDo.setGmtCreated(date);
        fileDo.setGmtModified(date);
        fileDo.setDelTime(null);
        return fileDo;
    }

    private FileDo updateParamToDo(FileUpdateParam param) {
        FileDo fileDo = new FileDo();
        fileDo.setId(param.getId());
        fileDo.setParentId(param.getParentId());
        fileDo.setUserName(param.getUserName());
        fileDo.setFileName(param.getFileName());
        fileDo.setFilePath(param.getFilePath());
        fileDo.setFileSize(param.getFileSize());
        fileDo.setFileType(param.getFileType());
        fileDo.setIsFile(param.getIsFile());
        fileDo.setIsExist(param.getIsExist());
        fileDo.setHasChild(param.getHasChild());
        fileDo.setDel(param.getDel());
        fileDo.setGmtModified(param.getGmtModified());
        fileDo.setDelTime(param.getDelTime());
        return fileDo;
    }

    private FileDalQueryParam createDalParam(FileQueryParam fileQueryParam) {
        FileDalQueryParam fileDalQueryParam = new FileDalQueryParam();
        fileDalQueryParam.setParentId(fileQueryParam.getParentId());
        fileDalQueryParam.setUserId(fileQueryParam.getUserId());
        fileDalQueryParam.setUserName(fileQueryParam.getUserName());
        if (StringUtils.isNotBlank(fileQueryParam.getFileName())) {
            fileDalQueryParam.setFileName(fileQueryParam.getFileName());
        }
        fileDalQueryParam.setFilePath(fileQueryParam.getFilePath());
        fileDalQueryParam.setFileSize(fileQueryParam.getFileSize());
        fileDalQueryParam.setFileType(fileQueryParam.getFileType());
        fileDalQueryParam.setIsFile(fileQueryParam.getIsFile());
        fileDalQueryParam.setIsExist(fileQueryParam.getIsExist());
        fileDalQueryParam.setHasChild(fileQueryParam.getHasChild());
        fileDalQueryParam.setCurrentPage(fileQueryParam.getCurrentPage());
        fileDalQueryParam.setPageSize(fileQueryParam.getPageSize());
        fileDalQueryParam.setDel(fileQueryParam.getDel() == null ? YesOrNoEnum.N.name() : fileQueryParam.getDel());
        fileDalQueryParam.setOrderBy(fileQueryParam.getOrderBy());
        return fileDalQueryParam;
    }

    private FileDto doToDto(FileDo fileDo) {
        FileDto fileDto = new FileDto();
        fileDto.setId(fileDo.getId());
        fileDto.setParentId(fileDo.getParentId());
        fileDto.setUserName(fileDo.getUserName());
        fileDto.setFileName(fileDo.getFileName());
        fileDto.setFilePath(fileDo.getFilePath());
        fileDto.setFileSize(fileDo.getFileSize());
        fileDto.setFileType(fileDo.getFileType());
        fileDto.setIsFile(fileDo.getIsFile());
        fileDto.setIsExist(fileDo.getIsExist());
        fileDto.setUserId(fileDo.getUserId());
        fileDto.setHasChild(fileDo.getHasChild());
        fileDto.setDel(fileDo.getDel());
        fileDto.setDelTime(fileDo.getDelTime());
        fileDto.setGmtCreated(fileDo.getGmtCreated());
        fileDto.setGmtModified(fileDo.getGmtModified());
        fileDto.setChildFileList(Lists.newArrayList());
        return fileDto;
    }

    @Override
    public void deleteFileById(Long fileId) {
        if (fileId != null) {
            try {
                this.fileRepository.delete(fileId);
                log.info("删除未完成上传的文件记录: fileId={}", fileId);
            } catch (Exception e) {
                log.error("删除文件记录失败: fileId={}", fileId, e);
            }
        }
    }

    @Override
    public FileDo findFileByPath(String filePath, Integer userId) {
        if (StringUtils.isBlank(filePath) || userId == null) {
            return null;
        }
        FileDalQueryParam param = new FileDalQueryParam();
        param.setFilePath(filePath);
        param.setUserId(userId);
        param.setDel(YesOrNoEnum.N.name());
        List<FileDo> files = fileRepository.getAssignFiles(param);
        return CollectionUtils.isEmpty(files) ? null : files.get(0);
    }

    // ========== 目录操作实现 ==========

    /**
     * 顶层目录ID
     */
    private static final Long ROOT_DIR_ID = 7408085068658278401L;

    /**
     * 目录名最大长度
     */
    private static final int MAX_DIR_NAME_LENGTH = 20;

    private static final String CHAT_ATTACHMENT_DIRECTORY_NAME = ".chat-attachments";

    /**
     * 查询用户的完整目录树结构（递归）
     *
     * @param userDTO 用户信息
     * @return 根目录节点（包含完整递归子目录）
     */
    @Override
    public FileDto handleUserTwoLevelDirectory(UserDTO userDTO) throws Exception {
        if (userDTO == null || userDTO.getId() == null) {
            log.warn("handleUserTwoLevelDirectory: 用户信息为空");
            return null;
        }

        try {
            // 1. 查询该用户下所有的目录数据（一次性查询，避免递归查库）
            FileQueryParam queryParam = new FileQueryParam();
            // 使用 userId 查询
            queryParam.setUserId(Integer.valueOf(String.valueOf(userDTO.getId())));
            queryParam.setFileType("NOT_FILE");
            queryParam.setIsFile(YesOrNoEnum.N.name());
            queryParam.setIsExist(YesOrNoEnum.Y.name());
            queryParam.setDel(YesOrNoEnum.N.name());

            // 获取所有目录列表
            List<FileDo> allDirs = this.getAssignFiles(queryParam);

            if (CollectionUtils.isEmpty(allDirs)) {
                log.warn("handleUserTwoLevelDirectory: 用户 {} 未查询到任何目录", userDTO.getUserName());
                throw new IllegalArgumentException("用户目录数据为空");
            }

            // 2. 找到根目录 (pId = -1)
            FileDo rootDirDo = allDirs.stream()
                    .filter(file -> file.getParentId() == -1L)
                    .findFirst()
                    .orElse(null);

            if (rootDirDo == null) {
                log.warn("handleUserTwoLevelDirectory: 用户 {} 根目录不存在", userDTO.getUserName());
                throw new IllegalArgumentException("用户顶层目录不存在");
            }

            // 3. 将所有目录转为 Map<ParentId, List<FileDto>> 以便快速构建树
            // 排除根目录，只处理子节点
            Map<Long, List<FileDto>> parentIdToChildrenMap = allDirs.stream()
                    .filter(file -> file.getParentId() != -1L)
                    .filter(file -> !isChatAttachmentDirectory(file))
                    .map(this::doToDto)
                    .collect(Collectors.groupingBy(FileDto::getParentId));

            // 4. 构建树形结构
            FileDto rootDto = this.doToDto(rootDirDo);
            buildDirectoryTree(rootDto, parentIdToChildrenMap);

            log.info("handleUserTwoLevelDirectory: 用户 {} 完整目录树查询成功，根目录ID={}",
                    userDTO.getUserName(), rootDto.getId());

            return rootDto;

        } catch (Exception e) {
            log.error("handleUserTwoLevelDirectory: 查询用户目录树失败, userName={}", userDTO.getUserName(), e);
            throw e;
        }
    }

    @Override
    public FileDo createByTask(FileTaskDto fileTaskDto) {
        if (Objects.isNull(fileTaskDto)) {
            return null;
        }
        FileDo fileDo = new FileDo();
        fileDo.setParentId(fileTaskDto.getParentId());
        fileDo.setFileName(fileTaskDto.getFileName());
        fileDo.setFilePath(fileTaskDto.getFilePath());
        fileDo.setFileSize(fileTaskDto.getFileSize());
        fileDo.setFileType(fileTaskDto.getFileType());
        fileDo.setUserId(fileTaskDto.getUserId());
        fileDo.setUserName(fileTaskDto.getUserName());
        fileDo.setIsFile(YesOrNoEnum.Y.name());
        fileDo.setIsExist(YesOrNoEnum.Y.name());
        fileDo.setHasChild(YesOrNoEnum.N.name());
        fileDo.setDel(YesOrNoEnum.N.name());
        fileDo.setDelTime(fileDo.getGmtModified());
        fileDo.setGmtCreated(new Date());
        fileDo.setGmtModified(fileDo.getGmtCreated());
        fileDo.setId(SnowflakeIdWorkerUtil.generateId());
        this.fileRepository.insertSelective(fileDo);
        return fileDo;
    }

    /**
     * 递归构建目录树
     * 
     * @param currentDir            当前目录节点
     * @param parentIdToChildrenMap 父ID与子节点列表的映射
     */
    private void buildDirectoryTree(FileDto currentDir, Map<Long, List<FileDto>> parentIdToChildrenMap) {
        // 获取当前目录的子节点列表
        List<FileDto> children = parentIdToChildrenMap.get(currentDir.getId());

        if (CollectionUtils.isEmpty(children)) {
            // 没有子节点，设置为空列表
            currentDir.setChildFileList(Lists.newArrayList());
            // 确保 hasChild 状态正确 (虽然数据库可能有值，但在内存树中最终确认一下也无妨，或者保持数据库原值)
            // currentDir.setHasChild(YesOrNoEnum.N.name());
            return;
        }

        // 设置子节点
        currentDir.setChildFileList(children);
        // currentDir.setHasChild(YesOrNoEnum.Y.name());

        // 对每个子节点递归构建
        for (FileDto child : children) {
            buildDirectoryTree(child, parentIdToChildrenMap);
        }
    }

    @Override
    public FileDto createDirectory(Long parentId, String dirName, UserDTO userDTO) {
        // 1. 校验参数
        if (parentId == null || dirName == null || dirName.trim().isEmpty()) {
            throw new IllegalArgumentException("参数无效");
        }
        dirName = dirName.trim();

        if (CHAT_ATTACHMENT_DIRECTORY_NAME.equals(dirName)) {
            throw new IllegalArgumentException("系统目录名称不可使用");
        }

        // 特殊处理：如果是根目录（parentId = -1），允许较长的目录名
        boolean isRootDirectory = (parentId == -1L);
        if (!isRootDirectory && dirName.length() > MAX_DIR_NAME_LENGTH) {
            throw new IllegalArgumentException("目录名称超过" + MAX_DIR_NAME_LENGTH + "个字符");
        }

        // 2. 校验父目录是否存在且为目录（根目录除外）
        if (!isRootDirectory && !isDirectory(parentId)) {
            throw new IllegalArgumentException("父目录不存在或不是目录类型");
        }

        // 3. 检查同级是否有重名
        if (existsSameName(parentId, dirName, null)) {
            throw new IllegalArgumentException("同级目录已存在同名目录");
        }

        // 4. 构建文件系统路径并创建目录
        String newDirPath;
        if (isRootDirectory) {
            // 根目录：使用配置文件中的完整路径
            String basePath = NioServerContext.getValue(
                    com.alibaba.server.common.OSinfo.isWindows()
                            ? BasicConstant.NIO_FILE_BASE_PATH_WINDOWS
                            : BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC);
            newDirPath = basePath != null ? basePath.trim() + File.separator + dirName : "";
        } else {
            // 非根目录：拼接父路径
            String parentPath = buildDirectoryPath(parentId);
            newDirPath = parentPath + File.separator + dirName;
        }

        File newDir = new File(newDirPath);
        if (!newDir.exists()) {
            if (!newDir.mkdirs()) {
                throw new RuntimeException("文件系统目录创建失败: " + newDirPath);
            }
        }

        // 5. 创建DB记录
        FileCreateParam createParam = new FileCreateParam();
        createParam.setParentId(parentId);
        createParam.setFileName(dirName);
        createParam.setFilePath(newDirPath);
        createParam.setFileType("NOT_FILE");
        createParam.setIsFile(YesOrNoEnum.N.name());
        createParam.setIsExist(YesOrNoEnum.Y.name());
        createParam.setHasChild(YesOrNoEnum.N.name());
        createParam.setUserName(userDTO.getUserName());
        createParam.setUserId(Integer.valueOf(String.valueOf(userDTO.getId())));
        FileDo fileDo = this.createParamToDo(createParam);
        this.fileRepository.insertSelective(fileDo);

        // 6. 更新父目录的hasChild状态（根目录除外）
        if (!isRootDirectory) {
            FileDo parentUpdate = new FileDo();
            parentUpdate.setId(parentId);
            parentUpdate.setHasChild(YesOrNoEnum.Y.name());
            this.fileRepository.updateSelective(parentUpdate);
        }

        log.info("创建目录成功: id={}, name={}, path={}, isRoot={}",
                fileDo.getId(), dirName, newDirPath, isRootDirectory);
        return this.doToDto(fileDo);
    }

    /**
     * 删除目录及其空目录树。只有目录树中不存在有效文件时才允许删除。
     *
     * @param dirId   目录ID
     * @param userDTO 当前登录用户
     * @return true=删除成功
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = RuntimeException.class)
    public boolean deleteDirectory(Long dirId, UserDTO userDTO) {
        if (dirId == null) {
            throw new IllegalArgumentException("目录ID不能为空");
        }
        if (userDTO == null || userDTO.getId() == null || StringUtils.isBlank(userDTO.getUserName())) {
            throw new IllegalArgumentException("当前用户信息无效");
        }
        if (userDTO.getId() > Integer.MAX_VALUE || userDTO.getId() < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("当前用户ID无效");
        }

        Integer userId = userDTO.getId().intValue();
        String userName = userDTO.getUserName().trim();
        FileDo directory = this.fileRepository.get(dirId);
        if (directory == null
                || !YesOrNoEnum.N.name().equals(directory.getDel())
                || !YesOrNoEnum.N.name().equals(directory.getIsFile())
                || !YesOrNoEnum.Y.name().equals(directory.getIsExist())) {
            throw new IllegalArgumentException("目录不存在或状态无效");
        }
        if (Long.valueOf(-1L).equals(directory.getParentId())) {
            throw new IllegalArgumentException("用户根目录不允许删除");
        }
        rejectChatAttachmentDirectoryMutation(directory);

        List<FileDo> directoryChain = loadAndValidateUploadDirectoryChain(dirId, userId, userName);
        List<FileDo> descendants = collectActiveDescendants(dirId, new HashSet<Long>());
        validateDirectoryDescendants(descendants, userId, userName);

        List<FileDo> validFiles = descendants.stream()
                .filter(item -> YesOrNoEnum.Y.name().equals(item.getIsFile()))
                .filter(item -> YesOrNoEnum.Y.name().equals(item.getIsExist()))
                .collect(Collectors.toList());
        if (!validFiles.isEmpty()) {
            log.warn("目录删除被拒绝: dirId={}, userId={}, validFileCount={}",
                    dirId, userId, validFiles.size());
            throw new DirectoryContainsFileException("目录或子目录中存在有效文件，请先删除文件");
        }

        Path directoryPath = resolveDirectoryPath(directoryChain, userName);
        List<Long> recordIds = descendants.stream()
                .map(FileDo::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        recordIds.add(dirId);

        // 数据库更新先进入事务；文件系统删除失败时抛出异常，由 Spring 回滚目录记录和父节点状态。
        this.fileRepository.batchLogicDelete(recordIds);
        refreshParentHasChild(directory.getParentId());
        deleteDirectoryTree(directoryPath);

        log.info("删除目录成功: dirId={}, userId={}, recordCount={}, path={}",
                dirId, userId, recordIds.size(), directoryPath);
        return true;
    }

    private List<FileDo> collectActiveDescendants(Long parentId, Set<Long> visitedDirectoryIds) {
        if (!visitedDirectoryIds.add(parentId)) {
            throw new IllegalArgumentException("目录层级存在循环引用");
        }

        FileDalQueryParam queryParam = new FileDalQueryParam();
        queryParam.setParentId(parentId);
        queryParam.setDel(YesOrNoEnum.N.name());
        List<FileDo> children = this.fileRepository.getAssignFiles(queryParam);
        if (CollectionUtils.isEmpty(children)) {
            return new ArrayList<>();
        }

        List<FileDo> result = new ArrayList<>(children);
        for (FileDo child : children) {
            if (YesOrNoEnum.N.name().equals(child.getIsFile()) && child.getId() != null) {
                result.addAll(collectActiveDescendants(child.getId(), visitedDirectoryIds));
            }
        }
        return result;
    }

    private void validateDirectoryDescendants(List<FileDo> descendants, Integer userId, String userName) {
        for (FileDo item : descendants) {
            boolean file = YesOrNoEnum.Y.name().equals(item.getIsFile());
            boolean directory = YesOrNoEnum.N.name().equals(item.getIsFile());
            if (!file && !directory) {
                throw new IllegalArgumentException("目录数据异常: 节点类型无效，id=" + item.getId());
            }
            if (!userId.equals(item.getUserId()) || !userName.equals(item.getUserName())) {
                throw new IllegalArgumentException("目录数据异常: 存在非当前用户节点，id=" + item.getId());
            }
            if (directory) {
                validatePathSegment(item.getFileName());
            }
        }
    }

    private Path resolveDirectoryPath(List<FileDo> directoryChain, String userName) {
        Path storageRoot = resolveStorageRoot();
        Path currentPath = resolveSafeChild(storageRoot, userName);
        List<Path> expectedPaths = new ArrayList<>(directoryChain.size());
        expectedPaths.add(currentPath);
        for (int index = 1; index < directoryChain.size(); index++) {
            currentPath = resolveSafeChild(currentPath, directoryChain.get(index).getFileName());
            ensureContained(storageRoot, currentPath);
            expectedPaths.add(currentPath);
        }
        validateExistingDirectoryComponents(expectedPaths);
        return currentPath;
    }

    private void deleteDirectoryTree(Path directoryPath) {
        if (!Files.exists(directoryPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(directoryPath)
                || !Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目录文件系统路径无效");
        }

        try {
            Files.walkFileTree(directoryPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("文件系统目录删除失败: path={}", directoryPath, e);
            throw new RuntimeException("文件系统目录删除失败，请检查目录权限", e);
        }
    }

    private void refreshParentHasChild(Long parentId) {
        if (parentId == null || parentId <= 0) {
            return;
        }
        FileDalQueryParam queryParam = new FileDalQueryParam();
        queryParam.setParentId(parentId);
        queryParam.setDel(YesOrNoEnum.N.name());
        List<FileDo> remainingChildren = this.fileRepository.getAssignFiles(queryParam);

        FileDo parentUpdate = new FileDo();
        parentUpdate.setId(parentId);
        parentUpdate.setHasChild(CollectionUtils.isEmpty(remainingChildren)
                ? YesOrNoEnum.N.name() : YesOrNoEnum.Y.name());
        parentUpdate.setGmtModified(new Date());
        this.fileRepository.updateSelective(parentUpdate);
    }

    @Override
    public FileDto updateDirectory(Long dirId, String newName) {
        // 1. 校验参数
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("新名称不能为空");
        }
        newName = newName.trim();
        if (newName.length() > MAX_DIR_NAME_LENGTH) {
            throw new IllegalArgumentException("目录名称超过" + MAX_DIR_NAME_LENGTH + "个字符");
        }

        // 2. 获取目录信息
        FileDo dirDo = this.fileRepository.get(dirId);
        if (dirDo == null || YesOrNoEnum.Y.name().equals(dirDo.getIsFile())) {
            throw new IllegalArgumentException("目录不存在");
        }
        rejectChatAttachmentDirectoryMutation(dirDo);

        if (dirDo.getParentId() != null && dirDo.getParentId() == -1L) {
            throw new IllegalArgumentException("顶层目录名称不允许修改");
        }

        // 3. 检查同级重名（排除自身）
        if (existsSameName(dirDo.getParentId(), newName, dirId)) {
            throw new IllegalArgumentException("同级目录已存在同名目录");
        }

        // 4. 重命名文件系统目录
        String oldPath = buildDirectoryPath(dirId);
        String parentPath = oldPath.substring(0, oldPath.lastIndexOf(File.separator));
        String newPath = parentPath + File.separator + newName;
        File oldDir = new File(oldPath);
        File newDir = new File(newPath);
        if (oldDir.exists() && oldDir.isDirectory()) {
            if (!oldDir.renameTo(newDir)) {
                throw new RuntimeException("文件系统目录重命名失败");
            }
        }

        // 5. 更新DB记录
        FileDo updateDo = new FileDo();
        updateDo.setId(dirId);
        updateDo.setFileName(newName);
        updateDo.setFilePath(newPath);
        updateDo.setGmtModified(new Date());
        this.fileRepository.updateSelective(updateDo);

        // 6. 异步批量更新所有子节点的 filePath（替换路径前缀，不阻塞接口响应）
        final String oldPathFinal = oldPath;
        final String newPathFinal = newPath;
        CompletableFuture.runAsync(() -> {
            try {
                List<FileDo> descendants = collectAllDescendants(dirId);
                if (!descendants.isEmpty()) {
                    Date now = new Date();
                    List<FileDo> updates = new ArrayList<>(descendants.size());
                    for (FileDo desc : descendants) {
                        if (desc.getFilePath() != null && desc.getFilePath().startsWith(oldPathFinal)) {
                            FileDo up = new FileDo();
                            up.setId(desc.getId());
                            up.setFilePath(newPathFinal + desc.getFilePath().substring(oldPathFinal.length()));
                            up.setGmtModified(now);
                            updates.add(up);
                        }
                    }
                    if (!updates.isEmpty()) {
                        fileRepository.batchUpdateSelective(updates);
                        log.info("异步更新子节点filePath完成: dirId={}, 更新数量={}", dirId, updates.size());
                    }
                }
            } catch (Exception e) {
                log.error("异步更新子节点filePath失败: dirId={}", dirId, e);
            }
        });

        log.info("更新目录成功: id={}, newName={}", dirId, newName);
        return this.doToDto(this.fileRepository.get(dirId));
    }

    @Override
    public FileDto moveDirectory(Long dirId, Long targetParentId) {
        // 1. 校验目标父目录
        if (!isDirectory(targetParentId)) {
            throw new IllegalArgumentException("目标父目录不存在或不是目录类型");
        }

        // 2. 获取目录信息
        FileDo dirDo = this.fileRepository.get(dirId);
        if (dirDo == null || YesOrNoEnum.Y.name().equals(dirDo.getIsFile())) {
            throw new IllegalArgumentException("目录不存在");
        }
        rejectChatAttachmentDirectoryMutation(dirDo);

        // 3. 检查目标目录下是否有同名
        if (existsSameName(targetParentId, dirDo.getFileName(), null)) {
            throw new IllegalArgumentException("目标目录下已存在同名目录");
        }

        // 4. 移动文件系统目录
        String oldPath = buildDirectoryPath(dirId);
        String targetParentPath = buildDirectoryPath(targetParentId);
        String newPath = targetParentPath + File.separator + dirDo.getFileName();
        File oldDir = new File(oldPath);
        File newDir = new File(newPath);
        if (oldDir.exists() && oldDir.isDirectory()) {
            if (!oldDir.renameTo(newDir)) {
                throw new RuntimeException("文件系统目录移动失败");
            }
        }

        // 5. 更新DB记录
        FileDo updateDo = new FileDo();
        updateDo.setId(dirId);
        updateDo.setParentId(targetParentId);
        updateDo.setFilePath(newPath);
        updateDo.setGmtModified(new Date());
        this.fileRepository.updateSelective(updateDo);

        // 6. 更新目标父目录的hasChild状态
        FileDo parentUpdate = new FileDo();
        parentUpdate.setId(targetParentId);
        parentUpdate.setHasChild(YesOrNoEnum.Y.name());
        this.fileRepository.updateSelective(parentUpdate);

        log.info("移动目录成功: id={}, targetParentId={}", dirId, targetParentId);
        return this.doToDto(this.fileRepository.get(dirId));
    }

    /**
     * 递归收集指定目录下的所有子孙节点（目录+文件）
     */
    private List<FileDo> collectAllDescendants(Long parentId) {
        List<FileDo> result = new ArrayList<>();
        FileDalQueryParam queryParam = new FileDalQueryParam();
        queryParam.setParentId(parentId);
        queryParam.setDel(YesOrNoEnum.N.name());
        List<FileDo> children = this.fileRepository.getAssignFiles(queryParam);
        if (!CollectionUtils.isEmpty(children)) {
            result.addAll(children);
            for (FileDo child : children) {
                result.addAll(collectAllDescendants(child.getId()));
            }
        }
        return result;
    }

    @Override
    public boolean isDirectory(Long id) {
        if (id == null) {
            return false;
        }
        FileDo fileDo = this.fileRepository.get(id);
        return fileDo != null && YesOrNoEnum.N.name().equals(fileDo.getIsFile());
    }

    @Override
    public String buildDirectoryPath(Long dirId) {
        if (dirId == null) {
            return "";
        }
        FileDo fileDo = this.fileRepository.get(dirId);
        if (fileDo == null) {
            return "";
        }
        // 如果是顶层目录，直接返回其路径
        if (fileDo.getParentId() == null || fileDo.getParentId() == -1L) {
            return fileDo.getFilePath();
        }
        // 递归构建路径
        String parentPath = buildDirectoryPath(fileDo.getParentId());
        return parentPath + File.separator + fileDo.getFileName();
    }

    @Override
    public boolean existsSameName(Long parentId, String dirName, Long excludeId) {
        FileDalQueryParam queryParam = new FileDalQueryParam();
        queryParam.setParentId(parentId);
        queryParam.setFileName(dirName);
        queryParam.setIsFile(YesOrNoEnum.N.name());
        queryParam.setDel(YesOrNoEnum.N.name());
        List<FileDo> list = this.fileRepository.getAssignFiles(queryParam);
        if (CollectionUtils.isEmpty(list)) {
            return false;
        }
        if (excludeId == null) {
            return true;
        }
        return list.stream().anyMatch(f -> !f.getId().equals(excludeId));
    }

    // ========== 文件操作实现 ==========

    @Override
    public FilePageDto listFiles(FileQueryParam fileQueryParam) {
        if (Objects.isNull(fileQueryParam.getUserId())) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        // 默认分页参数
        if (fileQueryParam.getCurrentPage() < 1) {
            fileQueryParam.setCurrentPage(1);
        }
        if (fileQueryParam.getPageSize() < 1) {
            fileQueryParam.setPageSize(10);
        }
        // 设置查询条件, 执行分页查询
        fileQueryParam.setIsFile(YesOrNoEnum.Y.name());
        fileQueryParam.setIsExist(YesOrNoEnum.Y.name());
        fileQueryParam.setDel(YesOrNoEnum.N.name());
        PageQueryParam.OrderBy orderBy = new PageQueryParam.OrderBy();
        orderBy.setProperty("gmtCreated");
        orderBy.setDirection(PageQueryParam.Direction.DESC);
        fileQueryParam.setOrderBy(Lists.newArrayList(orderBy));
        FileDalQueryParam fileDalQueryParam = this.createDalParam(fileQueryParam);
        log.info("=>【文件分页列表查询】入参={}", JSON.toJSONString(fileDalQueryParam));
        long count = this.fileRepository.count(fileDalQueryParam);
        List<FileDo> fileDoS = new ArrayList<>();
        if (count > 0) {
            fileDoS = this.fileRepository.page(fileDalQueryParam);
        }
        PageResult<FileDo> pageResult = new PageResult<>();
        pageResult.setTotalCount((int) count);
        pageResult.setModelList(fileDoS);
        pageResult.setPageSize(fileDalQueryParam.getPageSize());
        pageResult.setCurrentPage(fileDalQueryParam.getCurrentPage());
        log.info("=>【文件分页列表查询】查询结果={}, 入参={}", JSON.toJSONString(pageResult), JSON.toJSONString(fileDalQueryParam));
        // 转换结果
        List<FileDto> fileDtoList = Collections.emptyList();
        if (pageResult.getModelList() != null) {
            fileDtoList = pageResult.getModelList().stream()
                    .map(this::doToDto)
                    .collect(Collectors.toList());
            applyParentDirectoryNames(fileDtoList, loadParentDirectoryNames(fileDtoList));
        }
        return FilePageDto.of(fileDtoList, pageResult.getTotalCount(),
                pageResult.getCurrentPage(), pageResult.getPageSize());
    }

    private Map<Long, String> loadParentDirectoryNames(List<FileDto> fileDtoList) {
        Set<Long> parentIds = fileDtoList.stream()
                .map(FileDto::getParentId)
                .filter(Objects::nonNull)
                .filter(parentId -> parentId > 0)
                .collect(Collectors.toSet());
        Map<Long, String> parentNames = new HashMap<>();
        for (Long parentId : parentIds) {
            FileDo parentDirectory = this.fileRepository.get(parentId);
            if (parentDirectory != null && YesOrNoEnum.N.name().equals(parentDirectory.getIsFile())) {
                parentNames.put(parentId, parentDirectory.getFileName());
            }
        }
        return parentNames;
    }

    static void applyParentDirectoryNames(List<FileDto> fileDtoList, Map<Long, String> parentNames) {
        if (CollectionUtils.isEmpty(fileDtoList) || CollectionUtils.isEmpty(parentNames)) {
            return;
        }
        for (FileDto fileDto : fileDtoList) {
            fileDto.setParentDirName(parentNames.get(fileDto.getParentId()));
        }
    }

    @Override
    public FileDto getFileDetail(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("文件ID不能为空");
        }
        FileDo fileDo = this.fileRepository.get(fileId);
        if (fileDo == null) {
            throw new IllegalArgumentException("文件不存在");
        }
        FileDto fileDto = this.doToDto(fileDo);
        // 查询所属目录名称
        if (fileDo.getParentId() != null && fileDo.getParentId() > 0) {
            FileDo parentDir = this.fileRepository.get(fileDo.getParentId());
            if (parentDir != null) {
                fileDto.setParentDirName(parentDir.getFileName());
            }
        }
        return fileDto;
    }

    @Override
    public boolean deleteFileWithFs(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("文件ID不能为空");
        }
        FileDo fileDo = this.fileRepository.get(fileId);
        if (fileDo == null) {
            throw new IllegalArgumentException("文件不存在");
        }
        // 只能删除文件，不能删除目录
        if (!YesOrNoEnum.Y.name().equals(fileDo.getIsFile())) {
            throw new IllegalArgumentException("不能使用此方法删除目录");
        }

        // 1. 删除文件系统文件
        String filePath = fileDo.getFilePath();
        if (filePath != null && !filePath.isEmpty()) {
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                boolean deleted = file.delete();
                if (!deleted) {
                    log.warn("文件系统删除失败: {}", filePath);
                }
            }
        }

        // 2. 删除DB记录
        this.fileRepository.logicDelete(fileId);
        log.info("文件删除成功: fileId={}, fileName={}", fileId, fileDo.getFileName());
        return true;
    }

    @Override
    public FileDto renameFile(Long fileId, String newFileName) {
        log.info("重命名文件，入参: fileId={}, newFileName={}", fileId, newFileName);
        if (fileId == null) {
            throw new IllegalArgumentException("文件ID不能为空");
        }
        if (StringUtils.isBlank(newFileName)) {
            throw new IllegalArgumentException("新文件名不能为空");
        }

        try {
            FileDo fileDo = this.fileRepository.get(fileId);
            if (fileDo == null) {
                throw new IllegalArgumentException("文件不存在");
            }
            if (!YesOrNoEnum.Y.name().equals(fileDo.getIsFile())) {
                throw new IllegalArgumentException("只能对文件进行重命名，不支持目录");
            }

            // 同名校验：DB 中同一目录下是否已存在同名文件（排除自身）
            FileDalQueryParam sameNameCheck = new FileDalQueryParam();
            sameNameCheck.setParentId(fileDo.getParentId());
            sameNameCheck.setFileName(newFileName);
            sameNameCheck.setIsFile(YesOrNoEnum.Y.name());
            sameNameCheck.setDel(YesOrNoEnum.N.name());
            List<FileDo> sameNameList = this.fileRepository.getAssignFiles(sameNameCheck);
            boolean dbConflict = !CollectionUtils.isEmpty(sameNameList)
                    && sameNameList.stream().anyMatch(f -> !f.getId().equals(fileId));
            if (dbConflict) {
                throw new IllegalArgumentException("该目录下已存在同名文件: " + newFileName);
            }

            // 只更新 DB 展示名称，filePath 保持不变
            // 物理文件名含唯一 taskId 前缀，不执行文件系统重命名，确保删除操作始终能通过 filePath 定位物理文件
            FileDo updateDo = new FileDo();
            updateDo.setId(fileId);
            updateDo.setFileName(newFileName);
            updateDo.setGmtModified(new Date());
            this.fileRepository.updateSelective(updateDo);

            log.info("重命名文件成功: fileId={}, oldName={}, newName={}", fileId, fileDo.getFileName(), newFileName);
            return getFileDetail(fileId);
        } catch (IllegalArgumentException e) {
            log.warn("重命名文件参数校验失败，fileId={}, newFileName={}, 原因: {}", fileId, newFileName, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("重命名文件失败，fileId={}, newFileName={}, 原因: {}", fileId, newFileName, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public String validateDirectory(Long dirId) {
        if (dirId == null) {
            return null;
        }
        FileDo fileDo = this.fileRepository.get(dirId);
        if (fileDo == null) {
            return null;
        }
        // 必须是目录类型
        if (!YesOrNoEnum.N.name().equals(fileDo.getIsFile())) {
            return null;
        }
        // 构建完整路径
        String path = buildDirectoryPath(dirId);
        // 验证文件系统目录存在
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }
        try {
            // 统一存储文件系统规范路径，避免 macOS 目录大小写兼容但媒体安全校验不一致。
            return dir.getCanonicalPath();
        } catch (java.io.IOException e) {
            log.error("规范化上传目录失败: dirId={}, path={}", dirId, path, e);
            return null;
        }
    }

    @Override
    public String ensureUploadDirectory(Long dirId, Integer userId, String userName) {
        if (dirId == null) {
            throw new IllegalArgumentException("上传目录ID不能为空");
        }
        if (userId == null || StringUtils.isBlank(userName)) {
            throw new IllegalArgumentException("当前用户信息无效");
        }

        UploadDirectoryLock directoryLock = acquireUploadDirectoryLock(dirId);
        directoryLock.lock.lock();
        try {
            return ensureUploadDirectoryLocked(dirId, userId, userName.trim());
        } finally {
            directoryLock.lock.unlock();
            releaseUploadDirectoryLock(dirId, directoryLock);
        }
    }

    @Override
    public FileDto ensureChatAttachmentDirectory(Integer userId, String userName) {
        if (userId == null || StringUtils.isBlank(userName)) {
            throw new IllegalArgumentException("当前用户信息无效");
        }
        String normalizedUserName = userName.trim();
        FileDo rootDirectory = findUserRootDirectory(userId, normalizedUserName);

        UploadDirectoryLock directoryLock = acquireUploadDirectoryLock(rootDirectory.getId());
        directoryLock.lock.lock();
        try {
            FileDo existing = findChatAttachmentDirectory(rootDirectory.getId(), userId);
            if (existing != null) {
                ensureUploadDirectoryLocked(existing.getId(), userId, normalizedUserName);
                return doToDto(fileRepository.get(existing.getId()));
            }

            String rootPath = ensureUploadDirectoryLocked(rootDirectory.getId(), userId, normalizedUserName);
            Path attachmentPath = resolveSafeChild(Paths.get(rootPath), CHAT_ATTACHMENT_DIRECTORY_NAME);
            validateExistingDirectoryComponents(Collections.singletonList(attachmentPath));
            try {
                Files.createDirectories(attachmentPath);
                if (Files.isSymbolicLink(attachmentPath)
                        || !Files.isDirectory(attachmentPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("聊天附件目录路径无效");
                }

                FileCreateParam createParam = new FileCreateParam();
                createParam.setParentId(rootDirectory.getId());
                createParam.setFileName(CHAT_ATTACHMENT_DIRECTORY_NAME);
                createParam.setFilePath(attachmentPath.toRealPath().toString());
                createParam.setFileType("NOT_FILE");
                createParam.setIsFile(YesOrNoEnum.N.name());
                createParam.setIsExist(YesOrNoEnum.Y.name());
                createParam.setHasChild(YesOrNoEnum.N.name());
                createParam.setUserId(userId);
                createParam.setUserName(normalizedUserName);
                FileDo created = createParamToDo(createParam);
                fileRepository.insertSelective(created);

                FileDo rootUpdate = new FileDo();
                rootUpdate.setId(rootDirectory.getId());
                rootUpdate.setHasChild(YesOrNoEnum.Y.name());
                rootUpdate.setGmtModified(new Date());
                fileRepository.updateSelective(rootUpdate);
                log.info("聊天附件目录创建完成: userId={}, dirId={}, path={}",
                        userId, created.getId(), created.getFilePath());
                return doToDto(created);
            } catch (IOException e) {
                throw new IllegalStateException("聊天附件目录创建失败: " + e.getMessage(), e);
            }
        } finally {
            directoryLock.lock.unlock();
            releaseUploadDirectoryLock(rootDirectory.getId(), directoryLock);
        }
    }

    private FileDo findUserRootDirectory(Integer userId, String userName) {
        FileDalQueryParam query = new FileDalQueryParam();
        query.setParentId(-1L);
        query.setUserId(userId);
        query.setIsFile(YesOrNoEnum.N.name());
        query.setIsExist(YesOrNoEnum.Y.name());
        query.setDel(YesOrNoEnum.N.name());
        List<FileDo> roots = fileRepository.getAssignFiles(query);
        if (CollectionUtils.isEmpty(roots)) {
            throw new IllegalArgumentException("用户根目录不存在");
        }
        return roots.stream()
                .filter(root -> userName.equals(root.getFileName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目录根节点与当前用户不匹配"));
    }

    private FileDo findChatAttachmentDirectory(Long rootDirectoryId, Integer userId) {
        FileDalQueryParam query = new FileDalQueryParam();
        query.setParentId(rootDirectoryId);
        query.setFileName(CHAT_ATTACHMENT_DIRECTORY_NAME);
        query.setUserId(userId);
        query.setIsFile(YesOrNoEnum.N.name());
        query.setIsExist(YesOrNoEnum.Y.name());
        query.setDel(YesOrNoEnum.N.name());
        List<FileDo> directories = fileRepository.getAssignFiles(query);
        if (CollectionUtils.isEmpty(directories)) {
            return null;
        }
        if (directories.size() > 1) {
            throw new IllegalStateException("聊天附件目录数据重复");
        }
        return directories.get(0);
    }

    private static boolean isChatAttachmentDirectory(FileDo directory) {
        return directory != null
                && YesOrNoEnum.N.name().equals(directory.getIsFile())
                && CHAT_ATTACHMENT_DIRECTORY_NAME.equals(directory.getFileName());
    }

    private void rejectChatAttachmentDirectoryMutation(FileDo directory) {
        if (isChatAttachmentDirectory(directory)) {
            throw new IllegalArgumentException("聊天附件系统目录不允许修改");
        }
    }

    private String ensureUploadDirectoryLocked(Long dirId, Integer userId, String userName) {
        List<FileDo> directoryChain = loadAndValidateUploadDirectoryChain(dirId, userId, userName);
        Path storageRoot = resolveStorageRoot();
        Path currentPath = resolveSafeChild(storageRoot, userName);
        List<Path> expectedPaths = new ArrayList<>(directoryChain.size());
        expectedPaths.add(currentPath);

        for (int index = 1; index < directoryChain.size(); index++) {
            currentPath = resolveSafeChild(currentPath, directoryChain.get(index).getFileName());
            ensureContained(storageRoot, currentPath);
            expectedPaths.add(currentPath);
        }

        validateExistingDirectoryComponents(expectedPaths);
        Path targetPath = expectedPaths.get(expectedPaths.size() - 1);
        try {
            Files.createDirectories(targetPath);
            validateExistingDirectoryComponents(expectedPaths);

            Path realStorageRoot = storageRoot.toRealPath();
            Path realTargetPath = targetPath.toRealPath();
            if (!realTargetPath.startsWith(realStorageRoot)) {
                throw new IllegalArgumentException("上传目录超出当前存储根目录");
            }

            repairDirectoryMetadata(directoryChain, expectedPaths, userName);
            log.info("上传目录准备完成: dirId={}, userName={}, path={}", dirId, userName, realTargetPath);
            return realTargetPath.toString();
        } catch (IOException e) {
            throw new IllegalStateException("上传目录创建失败: " + e.getMessage(), e);
        }
    }

    private List<FileDo> loadAndValidateUploadDirectoryChain(Long dirId, Integer userId, String userName) {
        List<FileDo> reversedChain = new ArrayList<>();
        Set<Long> visitedIds = new HashSet<>();
        Long currentId = dirId;

        while (currentId != null && currentId != -1L) {
            if (!visitedIds.add(currentId)) {
                throw new IllegalArgumentException("目录层级存在循环引用");
            }

            FileDo directory = fileRepository.get(currentId);
            if (directory == null) {
                throw new IllegalArgumentException("目录层级数据不完整: 缺少目录ID=" + currentId);
            }
            validateUploadDirectoryRecord(directory, userId, userName);
            reversedChain.add(directory);

            Long parentId = directory.getParentId();
            if (parentId == null) {
                throw new IllegalArgumentException("目录层级数据不完整: 父目录ID为空");
            }
            currentId = parentId;
        }

        if (currentId == null || reversedChain.isEmpty()) {
            throw new IllegalArgumentException("目录层级数据不完整: 未找到用户根目录");
        }

        Collections.reverse(reversedChain);
        FileDo rootDirectory = reversedChain.get(0);
        if (!Long.valueOf(-1L).equals(rootDirectory.getParentId())
                || !userName.equals(rootDirectory.getFileName())) {
            throw new IllegalArgumentException("目录根节点与当前用户不匹配");
        }
        return reversedChain;
    }

    private void validateUploadDirectoryRecord(FileDo directory, Integer userId, String userName) {
        if (!YesOrNoEnum.N.name().equals(directory.getIsFile())
                || !YesOrNoEnum.Y.name().equals(directory.getIsExist())
                || !YesOrNoEnum.N.name().equals(directory.getDel())) {
            throw new IllegalArgumentException("目录状态无效: dirId=" + directory.getId());
        }
        boolean legacySystemRoot = Long.valueOf(-1L).equals(directory.getParentId())
                && "system".equals(directory.getUserName())
                && userName.equals(directory.getFileName());
        if (!userId.equals(directory.getUserId())
                || (!userName.equals(directory.getUserName()) && !legacySystemRoot)) {
            throw new IllegalArgumentException("目录不属于当前用户: dirId=" + directory.getId());
        }
        validatePathSegment(directory.getFileName());
    }

    private Path resolveStorageRoot() {
        String configuredRoot = NioServerContext.getValue(
                com.alibaba.server.common.OSinfo.isWindows()
                        ? BasicConstant.NIO_FILE_BASE_PATH_WINDOWS
                        : BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC);
        if (StringUtils.isBlank(configuredRoot)) {
            throw new IllegalStateException("文件存储根目录未配置");
        }

        Path storageRoot = Paths.get(configuredRoot.trim()).toAbsolutePath().normalize();
        try {
            if (Files.exists(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(storageRoot)) {
                    throw new IllegalArgumentException("文件存储根目录不允许是符号链接");
                }
                if (!Files.isDirectory(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("文件存储根路径被文件占用");
                }
            } else {
                Files.createDirectories(storageRoot);
            }
            return storageRoot.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("文件存储根目录不可用: " + e.getMessage(), e);
        }
    }

    private Path resolveSafeChild(Path parent, String childName) {
        validatePathSegment(childName);
        Path child = parent.resolve(childName).normalize();
        ensureContained(parent, child);
        return child;
    }

    private void validatePathSegment(String pathSegment) {
        if (StringUtils.isBlank(pathSegment)
                || ".".equals(pathSegment)
                || "..".equals(pathSegment)
                || pathSegment.indexOf('/') >= 0
                || pathSegment.indexOf('\\') >= 0
                || pathSegment.indexOf('\0') >= 0
                || Paths.get(pathSegment).isAbsolute()
                || Paths.get(pathSegment).getNameCount() != 1) {
            throw new IllegalArgumentException("目录名称不安全: " + pathSegment);
        }
    }

    private void ensureContained(Path parent, Path child) {
        if (!child.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("上传目录超出当前存储根目录");
        }
    }

    private void validateExistingDirectoryComponents(List<Path> expectedPaths) {
        for (Path expectedPath : expectedPaths) {
            if (!Files.exists(expectedPath, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(expectedPath)) {
                throw new IllegalArgumentException("目录路径不允许包含符号链接: " + expectedPath);
            }
            if (!Files.isDirectory(expectedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("目录路径被文件占用: " + expectedPath);
            }
        }
    }

    private void repairDirectoryMetadata(
            List<FileDo> directoryChain,
            List<Path> expectedPaths,
            String userName) throws IOException {
        for (int index = 0; index < directoryChain.size(); index++) {
            FileDo directory = directoryChain.get(index);
            String expectedPath = expectedPaths.get(index).toRealPath().toString();
            boolean repairPath = !expectedPath.equals(directory.getFilePath());
            boolean repairLegacyRootOwner = Long.valueOf(-1L).equals(directory.getParentId())
                    && "system".equals(directory.getUserName());
            if (repairPath || repairLegacyRootOwner) {
                FileDo update = new FileDo();
                update.setId(directory.getId());
                if (repairPath) {
                    update.setFilePath(expectedPath);
                }
                if (repairLegacyRootOwner) {
                    update.setUserName(userName);
                }
                update.setGmtModified(new Date());
                fileRepository.updateSelective(update);
                if (repairPath) {
                    directory.setFilePath(expectedPath);
                }
                if (repairLegacyRootOwner) {
                    directory.setUserName(userName);
                }
            }
        }
    }

    private UploadDirectoryLock acquireUploadDirectoryLock(Long dirId) {
        return uploadDirectoryLocks.compute(dirId, (id, current) -> {
            UploadDirectoryLock result = current == null ? new UploadDirectoryLock() : current;
            result.users.incrementAndGet();
            return result;
        });
    }

    private void releaseUploadDirectoryLock(Long dirId, UploadDirectoryLock directoryLock) {
        uploadDirectoryLocks.compute(dirId, (id, current) -> {
            if (current != directoryLock) {
                return current;
            }
            return directoryLock.users.decrementAndGet() == 0 ? null : directoryLock;
        });
    }

    private static final class UploadDirectoryLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger users = new AtomicInteger();
    }
}
