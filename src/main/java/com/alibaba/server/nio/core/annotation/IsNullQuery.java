package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 *
 * 类IsNullQuery.java的实现描述：仅用在QueryParam的属性上，并且要求类型为Boolean
 * 标示本注解的属性，用于拼接is null的sql
 * 如果值是true则拼接is null
 * 如果值是false则拼接is not null
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface IsNullQuery {
}
