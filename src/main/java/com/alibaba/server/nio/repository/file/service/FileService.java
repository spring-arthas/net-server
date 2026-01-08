package com.alibaba.server.nio.repository.file.service;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.file.service.param.FileUpdateParam;

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
    FileDto createFile(FileQueryParam fileQueryParam);

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
}
