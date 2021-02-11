package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 * 忽略大小写，使用在QueryParam上
 * 注意：mysql默认就是忽略大小写的，只有在字段上设置大小写敏感后才需要此功能。
 *
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface IgnoreCase {
}
