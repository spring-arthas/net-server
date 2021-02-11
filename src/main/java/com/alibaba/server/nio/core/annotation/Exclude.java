package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 *
 * 类Exclude.java的实现描述：排除条件，会在生成的sql上加!=或not，使用在QueryParam上
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Exclude {
}
