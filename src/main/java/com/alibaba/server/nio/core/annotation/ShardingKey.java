package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 * 自定义分表字段
 *
 * @author liubei
 * @date 2020/09/09
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ShardingKey {
}
