package com.alibaba.server.nio.repository.file.service;

import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.dto.FilePageDto;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.file.service.param.FileUpdateParam;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 文件服务
 * 
 * @author spring
 */
public interface FileService {

    /**
     * 获取指定文件对象或路径下的文件信息,默认查询子文件夹及文件信息,牵扯递归查询
     * 
     * @param fileQueryParam   文件查询param
     * @param completeFilePath 文件绝对路径
     * @param relativeFilePath 文件相对路径
     * @return fileDto
     */
    FileDto getFolderFileTree(FileQueryParam fileQueryParam, String completeFilePath, String relativeFilePath);

    /**
     * 创建文件夹
     * 
     * @param fileQueryParam
     * @param completeFilePath 文件绝对路径
     * @return fileDto
     */
    FileDto create(FileQueryParam fileQueryParam, String completeFilePath);

    /**
     * 修改文件夹
     * 
     * @param fileUpdateParam
     * @param originFilePath   原始文件夹全路径
     * @param completeFilePath 新文件夹全路径
     * @return fileDto
     */
    FileDto update(FileUpdateParam fileUpdateParam, String originFilePath, String completeFilePath);

    /**
     * 创建文件
     * 
     * @param fileQueryParam
     * @return
     */
    FileDto createFile(FileQueryParam fileQueryParam, UserDTO userDTO);

    /**
     * 更新当前文件为删除状态
     * 
     * @param fileQueryParam 当前文件夹信息
     * @param fileIdList     待删除的文件id List
     * @return
     */
    Boolean deleteFile(FileQueryParam fileQueryParam, List<Long> fileIdList);

    /**
     * 根据文件id查询文件信息
     * 
     * @param fileQueryParam
     * @return
     */
    FileDto getFileById(FileQueryParam fileQueryParam);

    /**
     * 根据 ID 物理删除文件记录（用于断连清理）
     * 
     * @param fileId
     */
    void deleteFileById(Long fileId);

    // ========== 目录操作接口 ==========

    /**
     * 创建目录（DB + 文件系统）
     * 
     * @param parentId 父目录ID
     * @param dirName  目录名称（≤5字符）
     * @return 创建的目录信息
     * @throws IllegalArgumentException 名称过长或参数无效
     */
    FileDto createDirectory(Long parentId, String dirName, UserDTO userDTO);

    /**
     * 删除目录（DB + 文件系统）
     * 
     * @param dirId 目录ID
     * @return true=成功
     * @throws IllegalStateException 目录下有子项
     */
    boolean deleteDirectory(Long dirId);

    /**
     * 更新目录名称（DB + 文件系统）
     * 
     * @param dirId   目录ID
     * @param newName 新名称（≤5字符，同级不重复）
     * @return 更新后的目录信息
     */
    FileDto updateDirectory(Long dirId, String newName);

    /**
     * 移动目录（DB + 文件系统）
     * 
     * @param dirId          目录ID
     * @param targetParentId 目标父目录ID
     * @return 移动后的目录信息
     */
    FileDto moveDirectory(Long dirId, Long targetParentId);

    /**
     * 检查指定ID是否为目录类型
     * 
     * @param id 文件/目录ID
     * @return true=是目录
     */
    boolean isDirectory(Long id);

    /**
     * 构建目录的完整文件系统路径（递归拼接）
     * 
     * @param dirId 目录ID
     * @return 完整路径
     */
    String buildDirectoryPath(Long dirId);

    /**
     * 检查同级目录下是否存在同名目录
     * 
     * @param parentId  父目录ID
     * @param dirName   目录名称
     * @param excludeId 排除的目录ID（用于更新时排除自身）
     * @return true=存在重名
     */
    boolean existsSameName(Long parentId, String dirName, Long excludeId);

    // ========== 文件操作接口 ==========

    /**
     * 分页查询目录下的文件列表
     * 
     * @param fileQueryParam    文件查询对象
     * @return 文件列表
     */
    FilePageDto listFiles(FileQueryParam fileQueryParam);

    /**
     * 获取文件详情（包含所属目录名称）
     * 
     * @param fileId 文件ID
     * @return 文件详情
     */
    FileDto getFileDetail(Long fileId);

    /**
     * 删除文件（DB + 文件系统）
     * 
     * @param fileId 文件ID
     * @return true=成功
     */
    boolean deleteFileWithFs(Long fileId);

    /**
     * 校验目录是否存在且为目录类型
     * 
     * @param dirId 目录ID
     * @return 目录的文件系统路径，若不存在则返回null
     */
    String validateDirectory(Long dirId);

    /**
     * 获取当前用户顶层和第二层目录数据
     * @param userDTO
     * */
    FileDto handleUserTwoLevelDirectory(UserDTO userDTO) throws Exception;
    /**
     * 根据文件传输任务对象创建文件数据
     * */
    FileDo createByTask(FileTaskDto fileTaskDto);

    /**
     * 重命名文件（DB + 文件系统）
     *
     * @param fileId      文件ID
     * @param newFileName 新文件名（含扩展名）
     * @return 更新后的文件信息
     * @throws IllegalArgumentException 参数非法或文件不存在
     * @throws RuntimeException         文件系统重命名失败
     */
    FileDto renameFile(Long fileId, String newFileName);
}
