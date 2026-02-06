/*
 * Copyright 2015 Alibaba.com All right reserved. This software is the
 * confidential and proprietary information of Alibaba.com ("Confidential
 * Information"). You shall not disclose such Confidential Information and shall
 * use it only in accordance with the terms of the license agreement you entered
 * into with Alibaba.com.
 */
package com.alibaba.server.nio.core.repository;

import com.alibaba.server.nio.core.annotation.Batch;
import com.alibaba.server.nio.core.annotation.CountQuery;
import com.alibaba.server.nio.core.annotation.MapMethod;
import com.alibaba.server.nio.core.annotation.PageQuery;
import com.alibaba.server.nio.core.dataobject.Base2DO;
import com.alibaba.server.nio.core.dataobject.BaseDO;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import com.alibaba.server.nio.core.result.PageResult;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 基于mybatis的基础dao实现
 * 【可直接使用，无需写xml】默认实现根据驼峰转下划线，也可使用@Table和@Column自定义映射关系
 * 当然也可以写xml，会覆盖默认实现
 *
 * -------------------------------------------------------------------------------
 *
 * 默认提供什么：
 * 提供常用的增删改查默认实现。
 *
 * 默认不提供什么：
 * 不提供复杂查询，复杂查询指or查询、子查询、表关联。
 * 不提供根据条件的更新和删除，只提供根据id的更新和删除，也提供了批量方法。
 * 理由：查询可以是灵活的，但是写入应该是严格的且尽量面向对象的。
 *
 * 默认不提供的方法可以自己用XML实现，和原生mybatis用法一样。
 * 如果用XML实现自定义方法，或者用XML覆盖默认方法，也可以使用框架的特性节省代码，注意不用每个方法都实现。
 * 比如只需实现query一个方法的XML，即可覆盖默认方法query、queryPage、count。

 * 框架特性：
 * 1、默认增删改查方法不再赘述，请看下方方法注释，默认查询是and拼接条件的。
 * 2、{@link CountQuery}：将select语句动态处理为count语句执行。
 * 3、{@link PageQuery}：将select语句动态处理为count+select语句执行，返回PageResult。
 * 4、{@link Batch}：将单条的语句动态处理为批量语句执行，入参为List即可。
 * 5、{@link MapMethod}：将一个方法映射到另外一个方法上，可配合CountQuery、PageQuery、Batch使用。
 *         如将CountQuery映射到query方法上，这样就只需实现query方法，count方法自动生成。
 * 6、{@link ShardingKey}：分表字段，框架会根据分表字段生成id，可实现不用分表字段只用id查询更新数据。
 *         一般为tenantId，或者tenantId和业务字段，修改分表字段请慎重评估影响。详见 {@link IdGeneratorInterceptor}
 *         分表字段不支持更新，框架会在更新时忽略相关字段。
 * 7、{@link Table}：表名。默认为Repository类名去掉Repository后，驼峰转下划线。如果需要自定义请使用这个注解指定。
 * 8、{@link Column}：字段名。默认为DalPageQueryParam、DO属性名，驼峰转下划线。如果需要自定义请使用这个注解指定。
 * 9、{@link AutoFilterLogicDelete}：自动过滤逻辑删除标记，加上后所有查询更新接口将不会操作到逻辑删除数据。
 * 10、{@link Exclude}：排除条件，生成not、!= 运算符。
 * 11、{@link FindInSet}：见MYSQL FIND_IN_SET函数，会导致不走索引，慎用。
 * 12、{@link IgnoreCase}：忽略大小写查询，将字段和查询条件值都套上LOWER()函数，运算符不限，如like、=都支持。
 * 13、{@link IsNullQuery}：查询空和非空的条件，如is not null、is null。
 * 14、{@link QueryOperator}：运算符，如LIKE <= >= <>。
 *
 * @author liubei
 * @date 2020/08/27
 */
public interface Base2MapperRepository<Q extends DalPageQueryParam, T extends Base2DO> extends Base2Repository<Q, T> {

    /**
     * 【有默认实现】
     *
     * @param id
     * @return
     */
    @Override
    @SelectProvider(type = DynamicSQLProvider.class, method = "get")
    T get(Long id);

    /**
     * 【有默认实现】
     *
     * @param queryParam
     * @return
     */
    @Override
    @SelectProvider(type = DynamicSQLProvider.class, method = "query")
    List<T> query(Q queryParam);

    /**
     * 【有默认实现】
     * 分页查询。优先使用id="queryPage"的sql，若不存在则使用id="query"的sql
     *
     * @param queryParam
     * @return
     */
    @Override
    @PageQuery
    @MapMethod(value = "query", selfFirst = true)
    PageResult<T> queryPage(Q queryParam);

    /**
     * 【有默认实现】
     *
     * @param queryParam
     * @return
     */
    @Override
    @CountQuery
    @MapMethod(value = "query", selfFirst = true)
    long count(Q queryParam);

    /**
     * 【有默认实现】
     * 自增id会被回填到DO参数的id字段中
     *
     * @param createDO
     */
    @Override
    @InsertProvider(type = DynamicSQLProvider.class, method = "insert")
    // 	@SelectKey(statement = "SELECT LAST_INSERT_ID() as id", keyProperty = "id", before = false, resultType = Long
    // 	.class)
    void insert(T createDO);

    /**
     * 【有默认实现】
     * 无法返回自增id，如需要请使用单条insert
     * 建议搭配事务使用
     * 传入列表不能为空
     *
     * @param createDOList
     */
    @Override
    @Batch
    @MapMethod("insert")
    void batchInsert(List<T> createDOList);

    /**
     * 【有默认实现】
     * 自增id会被回填到DO参数的id字段中
     *
     * @param createDO
     */
    @Override
    @InsertProvider(type = DynamicSQLProvider.class, method = "insertSelective")
    @SelectKey(statement = "SELECT LAST_INSERT_ID() as id", keyProperty = "id", before = false, resultType = Long.class)
    void insertSelective(T createDO);

    /**
     * 【有默认实现】
     * 无法返回自增id，如需要请使用单条insert
     * 建议搭配事务使用
     * 传入列表不能为空
     *
     * @param createDOList
     */
    @Override
    @Batch
    @MapMethod("insertSelective")
    void batchInsertSelective(List<T> createDOList);

    /**
     * 【有默认实现】
     * 根据id更新，updateDO里为null的字段不更新
     *
     * @param updateDO
     */
    @Override
    @UpdateProvider(type = DynamicSQLProvider.class, method = "update")
    void update(T updateDO);

    /**
     * 【有默认实现】
     * 根据id批量更新，建议搭配事务使用，一是保证一致性，二是提高性能
     * 传入列表不能为空
     *
     * @param updateDOList
     */
    @Override
    @Batch
    @MapMethod("update")
    void batchUpdate(List<T> updateDOList);

    /**
     * 【有默认实现】
     * 根据id更新，updateDO里为null的字段不更新
     *
     * @param updateDO
     */
    @Override
    @UpdateProvider(type = DynamicSQLProvider.class, method = "updateSelective")
    void updateSelective(T updateDO);

    /**
     * 【有默认实现】
     * 根据id批量更新，建议搭配事务使用，一是保证一致性，二是提高性能
     * 传入列表不能为空
     *
     * @param updateDOList
     */
    @Override
    @Batch
    @MapMethod("updateSelective")
    void batchUpdateSelective(List<T> updateDOList);

    /**
     * 【有默认实现】
     * 默认实现为逻辑删除
     *
     * @param id
     */
    @Override
    @UpdateProvider(type = DynamicSQLProvider.class, method = "logicDelete")
    void logicDelete(Long id);


    /**
     * 【有默认实现】
     * 默认实现为逻辑删除
     *
     * @param idList
     */
    @Override
    @UpdateProvider(type = DynamicSQLProvider.class, method = "batchLogicDelete")
    void batchLogicDelete(List<Long> idList);

    /**
     * 【有默认实现】
     * 默认实现为物理删除，软删除请使用update
     *
     * @param id
     */
    @Override
    @DeleteProvider(type = DynamicSQLProvider.class, method = "delete")
    void delete(Long id);

    /**
     * 【有默认实现】
     * 默认实现为物理删除，软删除请使用update
     *
     * @param idList
     */
    @Override
    @DeleteProvider(type = DynamicSQLProvider.class, method = "batchDelete")
    void batchDelete(List<Long> idList);
}
