package com.alibaba.server.nio.core.param;

import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.annotation.Column;
import com.alibaba.server.nio.core.facade.CloneableSupport;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询基础类
 *
 * @author liubei
 * @date 2020/08/04
 */
@Getter
@Setter
@ToString
public class DalPageQueryParam implements CloneableSupport {
    /**
     * 删除标志
     */
    private String del;
    /**
     * 页码
     */
    @Column(ignore = true)
    private int currentPage;
    /**
     * 页大小
     */
    @Column(ignore = true)
    private int pageSize;
    /**
     * 排序
     */
    @Column(ignore = true)
    private List<PageQueryParam.OrderBy> orderBy;

    public DalPageQueryParam() {
        this.currentPage = 1;
        this.pageSize = 1000;
        this.del = YesOrNoEnum.N.name();
    }

    public DalPageQueryParam orderBy(String property, PageQueryParam.Direction direction) {
        this.orderBy = new ArrayList<>();
        this.orderBy.add(new PageQueryParam.OrderBy(property, direction));
        return this;
    }

    public DalPageQueryParam andOrderBy(String property, PageQueryParam.Direction direction) {
        this.orderBy.add(new PageQueryParam.OrderBy(property, direction));
        return this;
    }

    public int getOffset() {
        return (currentPage - 1) * pageSize;
    }

    /**
     * 翻页
     */
    public void nextPage() {
        currentPage++;
    }

}
