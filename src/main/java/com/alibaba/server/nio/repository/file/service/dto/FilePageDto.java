package com.alibaba.server.nio.repository.file.service.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 文件分页查询结果
 */
@Data
public class FilePageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页码
     */
    private Integer currentPage;

    /**
     * 每页数量
     */
    private Integer pageSize;

    /**
     * 总记录数
     */
    private Long totalCount;

    /**
     * 总页数
     */
    private Long totalPage;

    /**
     * 文件列表
     */
    private List<FileDto> recordList;

    public static FilePageDto of(List<FileDto> recordList, long totalCount, int currentPage, int pageSize) {
        FilePageDto dto = new FilePageDto();
        dto.setRecordList(recordList);
        dto.setTotalCount(totalCount);
        dto.setCurrentPage(currentPage);
        dto.setPageSize(pageSize);
        dto.setTotalPage((totalCount + pageSize - 1) / pageSize);
        return dto;
    }
}
