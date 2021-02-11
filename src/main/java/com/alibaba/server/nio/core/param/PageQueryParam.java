package com.alibaba.server.nio.core.param;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询基础类
 *
 * @author liubei
 * @date 2020/08/04
 */
@Data
public class PageQueryParam extends BaseParam {
    /**
     * 页码
     */
    private int currentPage;
    /**
     * 页大小
     */
    private int pageSize;
    /**
     * 排序
     */
    private List<OrderBy> orderBy;


    public PageQueryParam() {
        this.currentPage = 0;
        this.pageSize = 1000;
    }

    public PageQueryParam orderBy(String property, Direction direction) {
        this.orderBy = new ArrayList<>();
        this.orderBy.add(new OrderBy(property, direction));
        return this;
    }

    public PageQueryParam andOrderBy(String property, Direction direction) {
        this.orderBy.add(new OrderBy(property, direction));
        return this;
    }

    /**
     * 翻页
     */
    public void nextPage() {
        currentPage++;
    }


    @Getter
    @Setter
    @ToString
    public static class OrderBy {
        /**
         * 排序字段，驼峰格式，内部会转下划线
         */
        private String property;
        /**
         * 排序方向
         */
        private Direction direction;

        public OrderBy() {

        }

        public OrderBy(String property, Direction direction) {
            this.property = property;
            this.direction = direction;
        }

        public static OrderBy of(String property, Direction direction) {
            return new OrderBy(property, direction);
        }

        public static OrderBy asc(String property) {
            return new OrderBy(property, Direction.ASC);
        }

        public static OrderBy desc(String property) {
            return new OrderBy(property, Direction.DESC);
        }
    }

    public enum Direction {
        /**
         * 升序
         */
        ASC,
        /**
         * 降序
         */
        DESC;
    }

}
