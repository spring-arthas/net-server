package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.result.PageResult;
import com.alibaba.server.nio.repository.file.mapper.FileRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.repository.param.FileDalQueryParam;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileCreateParam;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.file.service.param.FileUpdateParam;
import com.alibaba.server.util.LocalTime;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文件服务
 * @author spring
 * */

@Slf4j
@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private FileRepository fileRepository;

    /**
     * @param fileQueryParam   文件查询param 
     * @param completeFilePath 文件绝对路径
     * @param relativeFilePath 文件相对路径
     * @return
     */
    @Override
    public FileDto getFolderFileTree(FileQueryParam fileQueryParam, String completeFilePath, String relativeFilePath) {
        List<FileDo> fileDos = this.getAssignFiles(fileQueryParam);
        if(!CollectionUtils.isEmpty(fileDos)) {
            // 当前文件夹不为空，此时只查询子文件夹，不查询文件
            FileDto rootFileDto = this.doToDto(fileDos.get(0));

            // 当前文件夹节点是否含有子节点，不含有子文件夹，则查询是否含有文件信息
            if(!rootFileDto.getHasChild().equals(YesOrNoEnum.Y.name())) {
                PageResult<FileDo> fileDoPageResult = this.queryFilesBelongCurrentFolder(rootFileDto.getId(), fileQueryParam.getCurrentPage(), fileQueryParam.getPageSize());
                if(!CollectionUtils.isEmpty(fileDoPageResult.getModelList())) {
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
            childFileQueryParam.setPId(rootFileDto.getId());
            List<FileDo> childFileDos = this.getAssignFiles(childFileQueryParam);
            if(!CollectionUtils.isEmpty(childFileDos)) {
                List<FileDto> childFileList = Lists.newArrayList();

                for(FileDo childFileDo : childFileDos) {
                    childFileList.add(this.doToDto(childFileDo));
                }

                rootFileDto.setChildFileList(childFileList);
            }

            return rootFileDto;
        } else {
            // 为空，文件系统创建当前文件夹
            File file = new File(completeFilePath);
            if(!file.exists() && !file.isDirectory()) {
                // 创建多级文件夹
                file.mkdirs();
            }

            FileCreateParam fileCreateParam = new FileCreateParam();
            fileCreateParam.setPId(-1L);
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
     * @param id  当前文件夹Id
     * @param currentPage 当前页
     * @param pageSize 数量
     * @return
     */
    private PageResult<FileDo> queryFilesBelongCurrentFolder(Long id, Integer currentPage, Integer pageSize) {
        FileDalQueryParam fileDalQueryParam = new FileDalQueryParam();
        fileDalQueryParam.setPId(id);
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
        if(!CollectionUtils.isEmpty(fileDos)) {
            FileDto fileDto = this.doToDto(fileDos.get(0));
            fileDto.setRepeatCreate(YesOrNoEnum.Y.name());
            return fileDto;
        }

        // 2、判断当前文件夹是否含有文件，含有则不能在当前文件夹路径下创建子文件夹
        FileQueryParam newFileQueryParam = new FileQueryParam();
        newFileQueryParam.setPId(fileQueryParam.getPId());
        newFileQueryParam.setFileType("FILE");
        newFileQueryParam.setIsFile(YesOrNoEnum.Y.name());
        newFileQueryParam.setIsExist(YesOrNoEnum.Y.name());
        newFileQueryParam.setCurrentPage(1);
        newFileQueryParam.setPageSize(2);
        PageResult<FileDo> pageResult = this.fileRepository.queryPage(this.createDalParam(newFileQueryParam));
        if(null != pageResult && pageResult.getTotalCount() > 0) {
            FileDto fileDto = new FileDto();
            fileDto.setId(fileQueryParam.getPId());
            fileDto.setFileName(fileQueryParam.getFilePath());
            fileDto.setFileCount(pageResult.getTotalCount());
            return fileDto;
        }

        // 3、为空，文件系统创建当前文件夹
        File file = new File(completeFilePath);
        if(!file.exists() && !file.isDirectory()) {
            // 创建多级文件夹
            file.mkdirs();
        }

        // 创建新的文件夹节点
        FileCreateParam fileCreateParam = new FileCreateParam();
        fileCreateParam.setPId(fileQueryParam.getPId());
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
        fileDo.setId(fileQueryParam.getPId());
        fileDo.setHasChild(YesOrNoEnum.Y.name());
        this.fileRepository.updateSelective(fileDo);
        FileDto fileDto = this.doToDto(fileDo);
        fileDto.setRepeatCreate(YesOrNoEnum.N.name());
        return fileDto;
    }

    @Override
    public FileDto update(FileUpdateParam fileUpdateParam, String originFilePath, String completeFilePath) {
        File file = new File(originFilePath);
        if(file.exists() && file.isDirectory()) {
            // 创建多级文件夹
            file.renameTo(new File(completeFilePath));
        }

        FileDo fileDo = this.updateParamToDo(fileUpdateParam);
        this.fileRepository.updateSelective(fileDo);
        return this.doToDto(fileDo);
    }

    @Override
    public FileDto createFile(FileQueryParam fileQueryParam) {
        List<FileDo> fileDos = this.getAssignFiles(fileQueryParam);
        if(CollectionUtils.isEmpty(fileDos)) {
            FileCreateParam fileCreateParam = new FileCreateParam();
            fileCreateParam.setPId(fileQueryParam.getPId());
            fileCreateParam.setUserName(fileQueryParam.getUserName());
            fileCreateParam.setFileName(fileQueryParam.getFileName());
            fileCreateParam.setFileSize(fileQueryParam.getFileSize());
            fileCreateParam.setFilePath(fileQueryParam.getFilePath());
            fileCreateParam.setFileType(fileQueryParam.getFileType());
            fileCreateParam.setIsFile(YesOrNoEnum.Y.name());
            fileCreateParam.setIsExist(YesOrNoEnum.Y.name());
            fileCreateParam.setHasChild(YesOrNoEnum.N.name());
            FileDo fileDo = this.createParamToDo(fileCreateParam);
            this.fileRepository.insertSelective(fileDo);
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileServiceImpl | --> 当前用户 [{}] 成功持久化上传文件 [{}], 文件存储路径 [{}], thread = {}",
                fileDo.getUserName(), fileDo.getFileName(), fileDo.getFilePath(), Thread.currentThread().getName());
            return this.doToDto(fileDo);
        }

        return new FileDto();
    }

    @Override
    public Boolean deleteFile(FileQueryParam fileQueryParam, List<Long> fileIdList) {
        if(CollectionUtils.isEmpty(fileIdList)) {
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

            //fileDoList.add(this.updateParamToDo(fileUpdateParam));
            this.fileRepository.updateSelective(this.updateParamToDo(fileUpdateParam));
        });

        //this.fileRepository.batchUpdateSelective(fileDoList);
        return Boolean.TRUE;
    }

    @Override
    public FileDto getFileById(FileQueryParam fileQueryParam) {
        return this.doToDto(this.fileRepository.get(fileQueryParam.getId()));
    }

    /**
     * 根据fileQueryParam指定的查询条件查询文件信息
     * @param fileQueryParam
     * @return
     */
    private List<FileDo> getAssignFiles(FileQueryParam fileQueryParam) {
        FileDalQueryParam fileDalQueryParam = this.createDalParam(fileQueryParam);
        return this.fileRepository.getAssignFiles(fileDalQueryParam);
    }

    private FileDo createParamToDo(FileCreateParam param) {
        FileDo fileDo = new FileDo();
        fileDo.setPId(param.getPId());
        fileDo.setUserName(param.getUserName());
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
        fileDo.setPId(param.getPId());
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
        fileDalQueryParam.setPId(fileQueryParam.getPId());
        fileDalQueryParam.setUserName(fileQueryParam.getUserName());
        fileDalQueryParam.setFileName(fileQueryParam.getFileName());
        fileDalQueryParam.setFilePath(fileQueryParam.getFilePath());
        fileDalQueryParam.setFileSize(fileQueryParam.getFileSize());
        fileDalQueryParam.setFileType(fileQueryParam.getFileType());
        fileDalQueryParam.setIsFile(fileQueryParam.getIsFile());
        fileDalQueryParam.setIsExist(fileQueryParam.getIsExist());
        fileDalQueryParam.setHasChild(fileQueryParam.getHasChild());
        fileDalQueryParam.setCurrentPage(fileQueryParam.getCurrentPage());
        fileDalQueryParam.setPageSize(fileQueryParam.getPageSize());
        fileDalQueryParam.setDel(YesOrNoEnum.N.name());
        fileDalQueryParam.setOrderBy(fileQueryParam.getOrderBy());
        return fileDalQueryParam;
    }

    private FileDto doToDto(FileDo fileDo) {
        FileDto fileDto = new FileDto();
        fileDto.setId(fileDo.getId());
        fileDto.setPId(fileDo.getPId());
        fileDto.setUserName(fileDo.getUserName());
        fileDto.setFileName(fileDo.getFileName());
        fileDto.setFilePath(fileDo.getFilePath());
        fileDto.setFileSize(fileDo.getFileSize());
        fileDto.setFileType(fileDo.getFileType());
        fileDto.setIsFile(fileDo.getIsFile());
        fileDto.setIsExist(fileDo.getIsExist());
        fileDto.setHasChild(fileDo.getHasChild());
        fileDto.setDel(fileDo.getDel());
        fileDto.setDelTime(fileDo.getDelTime());
        fileDto.setGmtCreated(fileDo.getGmtCreated());
        fileDto.setGmtModified(fileDo.getGmtModified());
        return fileDto;
    }
}
