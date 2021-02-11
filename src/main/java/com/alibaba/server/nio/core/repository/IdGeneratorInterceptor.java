package com.alibaba.server.nio.core.repository;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 基于IdGenerator生成主键。
 *
 * @see IdGenerator
 *
 * @author JiaJu Zhuang
 * @date 2020/9/1 7:15 下午
 **/
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
@Slf4j
public class IdGeneratorInterceptor implements Interceptor {
    /**
     * 系统支持的最大分表数量
     */
    private static final int DEFAULT_MODE = 2048;

    /**
     * sequence
     */
    private final IdGenerator idGenerator;

    public IdGeneratorInterceptor(IdGenerator idGenerator) {this.idGenerator = idGenerator;}

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement mappedStatement = (MappedStatement)invocation.getArgs()[0];
        if (SqlCommandType.INSERT != mappedStatement.getSqlCommandType()) {
            return invocation.proceed();
        }

        // 入参
        Object parameter = invocation.getArgs()[1];
        // batch的调用先跳过，batch调用会在BatchSqlSource里转为循环单个调用，单个的会再次执行到这里
        //if (parameter instanceof Map) {
        //    return invocation.proceed();
        //}

        //if (parameter instanceof List) {
        //    List parameterList = (List)parameter;
        //    parameterList.forEach(data -> setId(data, tableName, sequenceDao));
        //    return invocation.proceed();
        //}


        if (parameter instanceof Map) {
            Map map = (Map)parameter;
            List paramList = (List)map.get(BatchSqlSource.LIST);
            for (Object data : paramList) {
                setId(data);
            }
        } else {
            setId(parameter);
        }

        return invocation.proceed();
    }

    private void setId(Object data) {
        if (!(data instanceof BaseDO)) {
            return;
        }
        BaseDO baseDO = (BaseDO)data;
        if (baseDO.getId() != null) {
            return;
        }

        long id = idGenerator.nextId(baseDO);
        baseDO.setId(id);
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }

}
