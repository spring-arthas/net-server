package com.alibaba.server.nio.core.repository;

import com.alibaba.server.nio.core.dataobject.BaseDO;

/**
 * id生成器
 *
 * @see com.alibaba.hrdirect.repository.support.tddl.IdGeneratorTddlImpl
 *
 * @author liubei
 * @date 2020/10/22
 **/
public interface IdGenerator {

    /**
     * 返回某个表的下个id
     *
     * @param tableName
     * @return
     */
    long nextId(String tableName);

    /**
     * 返回某个表的下个id
     *
     * @param clazz
     * @return
     */
    <T extends BaseDO> long nextId(Class<T> clazz);

    /**
     * 返回某个表的下个id，用于id生成依赖表其它字段的场景，比如id生成依赖分表字段的值
     *
     * @param baseDO
     * @return
     */
    long nextId(BaseDO baseDO);

}
