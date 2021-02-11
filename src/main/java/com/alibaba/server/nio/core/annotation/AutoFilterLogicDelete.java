package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 *
 * 类AutoFilterLogicDelete.java的实现描述：自动过滤数据库的删除标记字段，字段类型是String，其中Y表示被删除
 * 加上注解后，默认的查询、更新都会过滤掉删除的数据
 * 【请标注在Mapper上】
 *
 * @see com.alibaba.hrdirect.repository.constant.YesOrNoEnum
 *
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface AutoFilterLogicDelete {


}
