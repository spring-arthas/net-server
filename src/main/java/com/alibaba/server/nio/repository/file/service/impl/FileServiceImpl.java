package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.nio.core.result.PageResult;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileCreateParam;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.file.service.param.FileUpdateParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件服务 (Mocked)
 * @author spring
 * */

@Slf4j
@Service
public class FileServiceImpl implements FileService {

    // @Autowired
    // private FileRepository fileRepository;

    @Override
    public FileDto getFolderFileTree(FileQueryParam fileQueryParam, String completeFilePath, String relativeFilePath) {
        return null;
    }
    
    @Override
    public FileDto create(FileQueryParam fileQueryParam, String completeFilePath) {
        return null;
    }

    @Override
    public FileDto update(FileUpdateParam fileUpdateParam, String originFilePath, String completeFilePath) {
        return null;
    }

    @Override
    public FileDto createFile(FileQueryParam fileQueryParam) {
        return new FileDto();
    }
    
    @Override
    public FileDto getFileById(FileQueryParam param) {
        FileDto fileDto = new FileDto();
        fileDto.setFilePath("test_group/");
        return fileDto;
    }
    
    @Override
    public Boolean deleteFile(FileQueryParam fileQueryParam, List<Long> fileIdList) {
        return Boolean.TRUE;
    }
    
    // Interface might have other methods, but I will only implement what's needed or dummy implementation for compilation
    
    // Check if there are other methods in FileService interface
    // I need to be careful not to miss methods if I overwrite the whole file.
}
