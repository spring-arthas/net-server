package com.alibaba.server.common;

/**
 *
 * 类ClassLoaderProvider.java的实现描述：用于用户自定义classloader
 * @author liubei
 * @date 2020/08/27
 */
public interface ClassLoaderProvider {

    ClassLoader getClassLoader();
}
