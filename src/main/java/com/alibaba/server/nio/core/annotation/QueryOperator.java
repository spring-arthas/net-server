package com.alibaba.server.nio.core.annotation;

import java.lang.annotation.*;

/**
 *
 * 类QueryOperator.java的实现描述：自定义查询操作符，例如 LIKE <= >= <>
 * 如果传入的是LIKE 则会在参数前后自动加上%
 * 不能用在List上
 * @author liubei
 * @date 2020/08/27
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface QueryOperator {
    String value() default "=";

    // TODO 考虑铺开？StartWithLike EQ GTE
    //public enum LikeType {
    //    /**
    //     * 以给定值开头，拼接后的SQL "value%"
    //     */
    //    StartWith,
    //    /**
    //     * 以给定值开头，拼接后的SQL "%value"
    //     */
    //    EndWith,
    //    /**
    //     * 包含给定值，拼接后的SQL "%value%"
    //     */
    //    Contains
    //}

}

