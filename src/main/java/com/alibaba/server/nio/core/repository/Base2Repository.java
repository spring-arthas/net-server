/*
 * Copyright 2015 Alibaba.com All right reserved. This software is the
 * confidential and proprietary information of Alibaba.com ("Confidential
 * Information"). You shall not disclose such Confidential Information and shall
 * use it only in accordance with the terms of the license agreement you entered
 * into with Alibaba.com.
 */
package com.alibaba.server.nio.core.repository;

import com.alibaba.server.nio.core.dataobject.Base2DO;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import com.alibaba.server.nio.core.result.PageResult;

import java.util.List;

/**
 * 基础dao实现
 *
 * @author liubei
 * @date 2020/08/27
 */
public interface Base2Repository<Q extends DalPageQueryParam, T extends Base2DO> {

    /**
     * 根据id查询
     *
     * @param id
     * @return
     */
    T get(Long id);

    /**
     * 根据条件查询
     *
     * @param queryParam
     * @return
     */
    List<T> query(Q queryParam);

    /**
     * 根据条件分页查询
     *
     * @param queryParam
     * @return
     */
    PageResult<T> queryPage(Q queryParam);

    /**
     * 根据条件count
     *
     * @param queryParam
     * @return
     */
    long count(Q queryParam);

    /**
     * 插入
     * @param createDO
     */
    void insert(T createDO);

    /**
     * 批量插入
     * @param createDOList
     */
    void batchInsert(List<T> createDOList);

    /**
     * 插入
     * @param createDO
     */
    void insertSelective(T createDO);

    /**
     * 批量插入
     * @param createDOList
     */
    void batchInsertSelective(List<T> createDOList);

    /**
     * 更新
     * @param updateDO
     */
    void updateSelective(T updateDO);

    /**
     * 批量更新
     * @param updateDOList
     */
    void batchUpdateSelective(List<T> updateDOList);

    /**
     * 更新
     * @param updateDO
     */
    void update(T updateDO);

    /**
     * 批量更新
     * @param updateDOList
     */
    void batchUpdate(List<T> updateDOList);

    /**
     * 逻辑删除
     * @param id
     */
    void logicDelete(Long id);

    /**
     * 批量逻辑删除
     * @param idList
     */
    void batchLogicDelete(List<Long> idList);

    /**
     * 删除，默认实现为物理删除
     * @param id
     */
    void delete(Long id);

    /**
     * 删除，默认实现为物理删除
     * @param idList
     */
    void batchDelete(List<Long> idList);
}
