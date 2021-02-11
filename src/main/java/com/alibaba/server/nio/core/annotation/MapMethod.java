package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;

/**
 *
 * 类MapMethod.java的实现描述：允许mapper的实现映射到同类的其他方法上
 * 【用法】
 * 1.@MapMethod("xxx")加到mapper接口的方法上，xxx代表映射的 方法名 或 xml的id
 * 2.@MapMethod("xxx")会优先映射"xxx"的资源，若不存在则会不走映射，按原方法的id执行sql
 * 3.也可使用 @MapMethod(value="xxx", selfFirst=true)，表示优先映射原方法的id的资源，若不存在则映射"xxx"的资源
 * @author liubei
 * @date 2020/08/27
 */
@Target({ METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface MapMethod {

	String value() default "";

	boolean selfFirst() default false;
}
