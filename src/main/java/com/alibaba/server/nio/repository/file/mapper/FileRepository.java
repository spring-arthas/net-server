package com.alibaba.server.nio.repository.file.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.repository.param.FileDalQueryParam;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.apache.ibatis.annotations.Mapper;

import com.alibaba.server.nio.core.annotation.MapMethod;
import com.alibaba.server.nio.core.annotation.PageQuery;
import com.alibaba.server.nio.core.result.PageResult;
import java.util.List;

/**
 * 文件dao
 * 
 * @author spring
 */
@Mapper
public interface FileRepository extends BaseMapperRepository<FileDalQueryParam, FileDo> {

    /**
     * 查询指定文件信息
     * 
     * @param fileDalQueryParam
     * @return
     */
    List<FileDo> getAssignFiles(FileDalQueryParam fileDalQueryParam);

    /**
     * 查询当前文件夹下的文件
     * 
     * @param fileDalQueryParam
     * @return
     */
    List<FileDo> queryFilesBelongCurrentFolder(FileDalQueryParam fileDalQueryParam);

    /**
     * 手动分页查询
     * 
     * @param fileDalQueryParam
     * @return
     */
    @PageQuery
    PageResult<FileDo> page(FileDalQueryParam fileDalQueryParam);
}
