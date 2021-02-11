package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 * 类Table.java的实现描述：映射的数据库表名，不填默认驼峰转下划线
 * 【请标注在Mapper上】
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Table {
	String value() default "";
}
