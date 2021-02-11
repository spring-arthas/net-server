package com.alibaba.server.nio.core.facade;

/**
 * 可唯一标识的
 *
 * @author liubei
 * @date 2020/09/28
 */
public interface Identity {

    /**
     * 获取id
     *
     * @return
     */
    Long getId();

    /**
     * 设置id
     *
     * @param id
     */
    void setId(Long id);

}
