package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 *
 * 类ColumnName.java的实现描述：映射的数据库字段名，不填默认驼峰转下划线
 * 可用在QueryParam上，也可用在DO模型上
 * 最终会反馈到自动拼装的增删改查sql上，以及查询结果映射上
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Column {
	String value() default "";

	/**
	 * 如果value值等于IGNORE的值，则忽略这个field，不做任何sql拼装
	 */
	boolean ignore() default false;

}
