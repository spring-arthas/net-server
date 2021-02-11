package com.alibaba.server.nio.core.result;

import com.alibaba.server.nio.core.param.PageQueryParam;
import lombok.*;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 分页返回结果模型
 *
 * @author liubei
 * @date 2020/08/01
 */
@Data
public final class PageResult<T> extends Result {

    /**
     * 页码
     */
    private int currentPage;

    /**
     * 页大小
     */
    private int pageSize;

    /**
     * 总数
     */
    private long totalCount;

    /**
     * 返回结果
     */
    private List<T> modelList;

    public PageResult() {

    }

    public PageResult(List<T> modelList, long totalCount, int currentPage, int pageSize) {
        this.modelList = modelList;
        this.totalCount = totalCount;
        this.currentPage = currentPage;
        this.pageSize = pageSize;
    }

    /**
     * 获取总页数
     */
    public long getTotalPages() {
        return this.totalCount % this.pageSize == 0 ? this.totalCount
            / this.pageSize : (this.totalCount / this.pageSize) + 1;
    }

    /**
     * 判断是否还有下一页
     *
     * @return
     */
    public boolean hasNextPage() {
        return currentPage < getTotalPages();
    }

    public static <T> PageResult<T> general(List<T> modelList, long totalCount, int currentPage, int pageSize) {
        return new PageResult<>(modelList, totalCount, currentPage, pageSize);
    }

    public static <T> PageResult<T> general(List<T> modelList, long totalCount, PageQueryParam param) {
        return general(modelList, totalCount, param.getCurrentPage(), param.getPageSize());
    }

    public static <T> PageResult<T> general(List<T> modelList, PageResult pageResult) {
        return general(modelList, pageResult.getTotalCount(), pageResult.getCurrentPage(), pageResult.getPageSize());
    }

    public static <T> PageResult<T> empty(int currentPage, int pageSize) {
        return general(Collections.emptyList(), 0, currentPage, pageSize);
    }

    public static <T> PageResult<T> empty(PageQueryParam param) {
        return general(Collections.emptyList(), 0, param.getCurrentPage(), param.getPageSize());
    }

    public static <I, T> PageResult<T> of(PageResult<I> pageResult, Function<List<I>, List<T>> converter) {
        return general(converter.apply(pageResult.getModelList()),
            pageResult.getTotalCount(),
            pageResult.getCurrentPage(),
            pageResult.getPageSize());
    }

}
