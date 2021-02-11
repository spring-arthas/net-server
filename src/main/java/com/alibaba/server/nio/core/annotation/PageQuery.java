package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;

/**
 *
 * 类PageQuery.java的实现描述：该注解代表此方法会执行分页查询，
 * 要求查询参数必须继承com.alibaba.hrdirect.repository.param.DalPageQueryParam
 * 返回结果为com.alibaba.hrdirect.api.result.PageResult
 * 加入该注解的sql会自动加入order by和limit
 * @author liubei
 * @date 2020/08/27
 */
@Target({ METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface PageQuery {
}
