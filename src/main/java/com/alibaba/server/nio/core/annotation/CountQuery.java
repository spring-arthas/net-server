package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;

/**
 *
 * 类CountQuery.java的实现描述：该注解代表此方法会执行count查询，
 * 要求查询参数必须继承com.alibaba.hrdirect.repository.param.DalPageQueryParam
 * 返回结果为Long
 * @author liubei
 * @date 2020/08/27
 */
@Target({ METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface CountQuery {

}
