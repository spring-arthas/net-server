package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 *
 * 类Batch.java的实现描述：批量插入/更新，方法的参数需要是一个List
 * 对应的sql，只需按单条写即可
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Batch {
}
