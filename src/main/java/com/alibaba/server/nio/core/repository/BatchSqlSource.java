package com.alibaba.server.nio.core.repository;

import com.alibaba.server.util.ClassUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * 类BatchSqlSource.java的实现描述：批量sql数据源 会把批量参数用;拼装成多条sql，一起提交
 * 在性能上与循环调用差别不大，因为数据库使用了autocommit；正确的做法应该是在批量操作外面包一个事务
 * @author liubei
 * @date 2020/08/27
 */
public class BatchSqlSource implements SqlSource {

    public static final String LIST = "list";
    // 原sqlSource
    private SqlSource originSqlSource;
    
    private Configuration config;

    public BatchSqlSource(SqlSource originSqlSource, Configuration config){
        this.originSqlSource = originSqlSource;
        this.config=config;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        if ( !(parameterObject instanceof Map)) {
            return originSqlSource.getBoundSql(parameterObject);
        }
        Map map = (Map) parameterObject;
        List paramList = (List) map.get(LIST);
        if (paramList == null || paramList.size()==0) {
            return originSqlSource.getBoundSql(parameterObject);
        }
        
        List<ParameterMapping> newMapping = new ArrayList<ParameterMapping>();
        StringBuilder newSql = new StringBuilder();
        BoundSql sql = null;
        for (int i = 0; i < paramList.size(); i++) {
            sql = originSqlSource.getBoundSql(paramList.get(i));
            List<ParameterMapping> originMapping = sql.getParameterMappings();
            if (originMapping == null || originMapping.isEmpty()) {
                return sql;
            }
            for (ParameterMapping mapping : originMapping) {
                ParameterMapping.Builder builder = new ParameterMapping.Builder(config, LIST + "[" + i + "]."
                                                                                        + mapping.getProperty(),
                                                                                mapping.getTypeHandler());
                newMapping.add(builder.build());
            }
            newSql.append(sql.getSql());
            newSql.append(";");
        }
        String newSqlString = newSql.substring(0, newSql.length() - 1);
        // set attribute field
        ClassUtils.setFieldValue(sql, "parameterMappings", newMapping);
        ClassUtils.setFieldValue(sql, "sql", newSqlString);
        return sql;
    }

}
