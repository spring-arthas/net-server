package com.alibaba.server.nio.core.repository;

import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.annotation.*;
import com.alibaba.server.nio.core.dataobject.BaseDO;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import com.alibaba.server.util.ClassUtils;
import com.alibaba.server.util.MybatisUtils;
import com.google.common.base.CaseFormat;
import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.mapping.MappedStatement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类DynamicSQLProvider.java的实现描述：简单sql自动生成，若xml里配置了同名sql，则会优先使用xml
 * 需要满足以下约定：
 * 1. DO类名与数据库表名对应 （或使用@Table注解加在Mapper接口上）
 * 2. DO的属性与数据库字段对应 （或使用@Column）
 * 3. DO需要继承BaseDO
 *
 * @author liubei
 * @date 2020/08/27
 */
public class DynamicSQLProvider {

    // 并未在所有地方加缓存，但是性能影响可以忽略不计
    private Map<MappedStatement, List<String>> columnNameCache = new ConcurrentHashMap<MappedStatement, List<String>>();
    private Map<MappedStatement, String> deleteSqlCache = new ConcurrentHashMap<MappedStatement, String>();

    public String insert(BaseDO baseDO, MappedStatement ms) {
        return insert(baseDO, ms, false);
    }

    public String insertSelective(BaseDO baseDO, MappedStatement ms) {
        return insert(baseDO, ms, true);
    }

    private String insert(BaseDO baseDO, MappedStatement ms, boolean selective) {
        String tableName = MybatisUtils.getTableNameByMs(ms);
        List<String> columns = getInsertColumns(baseDO, baseDO.getClass(), selective);

        List<String> insertParams = getInsertParams(columns);
        List<String> dbColumns = getMappedColumnName(baseDO.getClass(), columns);
        StringBuilder sql = new StringBuilder();
        sql.append("insert into ");
        sql.append(tableName);
        sql.append(" (");
        if (baseDO.getId() != null && baseDO.getId() > 0) {
            sql.append("id,");
        }
        sql.append("gmt_created,gmt_modified,del_time,");
        sql.append(StringUtils.join(dbColumns, ","));
        sql.append(") values(");
        if (baseDO.getId() != null && baseDO.getId() > 0) {
            sql.append("#{id},");
        }
        sql.append("now(), now(), now(),");
        sql.append(StringUtils.join(insertParams, ","));
        sql.append(")");
        return sql.toString();
    }

    public String get(Long id, MappedStatement ms) {
        String tableName = MybatisUtils.getTableNameByMs(ms);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(StringUtils.join(getColumnsByMs(ms), ","));
        sql.append(" FROM ");
        sql.append(tableName);
        sql.append(" WHERE id=");
        sql.append(String.valueOf(id));
        sql.append(getDeleteFilterSql(ms));
        return sql.toString();
    }

    private String getDeleteFilterSql(MappedStatement ms) {
        if (deleteSqlCache.containsKey(ms)) {
            return deleteSqlCache.get(ms);
        }
        String id = ms.getId();
        String className = id.substring(0, id.lastIndexOf("."));
        Class<?> s = MybatisUtils.getClass(className, ms);
        AutoFilterLogicDelete deleteColumn = s.getAnnotation(AutoFilterLogicDelete.class);
        if (deleteColumn != null) {
            // 构造过滤删除数据的sql
            String deleteFilterSql = " AND del='" + YesOrNoEnum.N.name() + "' ";
            deleteSqlCache.put(ms, deleteFilterSql);
            return deleteFilterSql;
        }
        deleteSqlCache.put(ms, "");
        return "";
    }

    public String logicDelete(Long id, MappedStatement ms) {
        String tableName = MybatisUtils.getTableNameByMs(ms);
        return "UPDATE "
                + tableName
                + " SET gmt_modified = now(), del = '" + YesOrNoEnum.Y.name() + "', del_time = now() "
                + " WHERE id = "
                + id + getDeleteFilterSql(ms);
    }

    public String batchLogicDelete(Map map, MappedStatement ms) {
        // mybatis 会默认转换成map，其默认key值为list
        List<Long> ids = (List<Long>) map.get(BatchSqlSource.LIST);
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        String tableName = MybatisUtils.getTableNameByMs(ms);
        return "UPDATE "
                + tableName
                + " SET gmt_modified = now(), del = '" + YesOrNoEnum.Y.name() + "', del_time = now() "
                + " WHERE id IN('"
                + StringUtils.join(ids, "','")
                + "')" + getDeleteFilterSql(ms);
    }

    public String delete(Long id, MappedStatement ms) {
        String tableName = MybatisUtils.getTableNameByMs(ms);
        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ");
        sql.append(tableName);
        sql.append(" WHERE id=");
        sql.append(String.valueOf(id));
        return sql.toString();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public String batchDelete(Map map, MappedStatement ms) {
        // mybatis 会默认转换成map，其默认key值为list
        List<Long> ids = (List<Long>) map.get(BatchSqlSource.LIST);
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        String tableName = MybatisUtils.getTableNameByMs(ms);
        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ");
        sql.append(tableName);
        sql.append(" WHERE id IN(");
        sql.append(StringUtils.join(ids, ","));
        sql.append(")");
        return sql.toString();
    }

    public String update(BaseDO baseDO, MappedStatement ms) {
        return update(baseDO, ms, false);
    }

    public String updateSelective(BaseDO baseDO, MappedStatement ms) {
        return update(baseDO, ms, true);
    }

    private String update(BaseDO baseDO, MappedStatement ms, boolean selective) {
        // String tableName = getTableNameByBaseDO(baseDO.getClass());
        String tableName = MybatisUtils.getTableNameByMs(ms);
        List<String> columns = getUpdateColumns(baseDO, baseDO.getClass(), selective);
        List<String> dbColumns = getMappedColumnName(baseDO.getClass(), columns);
        StringBuilder sql = new StringBuilder();
        sql.append("update ");
        sql.append(tableName);
        sql.append(" SET gmt_modified = now()");
        for (int i = 0; i < columns.size(); i++) {
            String camel = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, columns.get(i));
            sql.append("," + dbColumns.get(i) + "=#{" + camel + "}");
        }
        sql.append(" where id=#{id}");
        sql.append(getDeleteFilterSql(ms));
        return sql.toString();
    }

    public String query(DalPageQueryParam queryParam, MappedStatement ms) {
        String tableName = MybatisUtils.getTableNameByMs(ms);

        String deleteFilterSql = getDeleteFilterSql(ms);
        boolean autoFilterLogicDelete = StringUtils.isNotBlank(deleteFilterSql);

        String querySql = getQueryColumns(queryParam, queryParam.getClass(), autoFilterLogicDelete);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(StringUtils.join(getColumnsByMs(ms), ","));
        sql.append(" FROM ");
        sql.append(tableName);
        sql.append(" WHERE 1=1");
        sql.append(querySql);

        if (autoFilterLogicDelete) {
            sql.append(deleteFilterSql);
        }

        // if (StringUtils.isNotEmpty(queryParam.getGroupBy())) {
        // // 设置了group by字段
        // sql.append(" GROUP BY " + queryParam.getGroupBy());
        // }
        String orderBySql = MybatisUtils.getOrderBySql(queryParam);
        sql.append(orderBySql);

        String limitSql = MybatisUtils.getLimitSql(queryParam);
        sql.append(limitSql);

        // 对于普通query，自动加上 LIMIT 10000 以防OOM
        // sql.append(" LIMIT 10000");
        return sql.toString();
    }

    // TODO 使用@Column(ignore = true) 替代
    private static final List<Method> EXCLUDE_QUERY_METHOD;
    private static final Method DEL_QUERY_METHOD;
    static {
        try {
            EXCLUDE_QUERY_METHOD = Arrays.asList(
                    DalPageQueryParam.class.getMethod("getCurrentPage"),
                    DalPageQueryParam.class.getMethod("getPageSize"),
                    DalPageQueryParam.class.getMethod("getOrderBy"),
                    DalPageQueryParam.class.getMethod("getOffset"));
            DEL_QUERY_METHOD = DalPageQueryParam.class.getMethod("getDel");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    private String getQueryColumns(DalPageQueryParam queryParam, Class<? extends DalPageQueryParam> paramClass,
            boolean autoFilterLogicDelete) {
        StringBuilder result = new StringBuilder();
        try {
            List<Method> getters = ClassUtils.getGetters(paramClass);
            // 固定拼接字段，不需要再拼接
            getters.removeAll(EXCLUDE_QUERY_METHOD);

            if (autoFilterLogicDelete) {
                getters.remove(DEL_QUERY_METHOD);
            }

            for (Method method : getters) {
                Object value = method.invoke(queryParam);
                if (value == null) {
                    continue;
                }
                if (value instanceof String && StringUtils.isEmpty((String) value)) {
                    continue;
                }
                String columnUpperCamel = method.getName().substring(3);
                String columnLowerCamel = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, columnUpperCamel);
                String columnUnderScore;

                // 判断是否加了注解@Column
                Field field = ClassUtils.getField(paramClass, columnLowerCamel);
                Column column = field.getAnnotation(Column.class);
                Exclude exclude = field.getAnnotation(Exclude.class);
                QueryOperator operator = field.getAnnotation(QueryOperator.class);
                FindInSet findInSet = field.getAnnotation(FindInSet.class);
                IsNullQuery isNullQuery = field.getAnnotation(IsNullQuery.class);
                IgnoreCase ignoreCase = field.getAnnotation(IgnoreCase.class);
                if (column != null && StringUtils.isNotBlank(column.value())) {
                    if (column.ignore()) {
                        continue;
                    }
                    columnUnderScore = column.value();
                } else {
                    columnUnderScore = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, columnUpperCamel);
                }

                if (value instanceof Collection) {
                    Collection valueList = (Collection) value;
                    if (valueList.size() > 0) {
                        result.append(" AND ");
                        if (findInSet != null) {
                            result.append("(");
                            result.append(
                                    "find_in_set(#{" + columnLowerCamel + "[0]}," + columnUnderScore + ")");
                            for (int i = 1; i < valueList.size(); i++) {
                                result.append(" or ");
                                result.append(
                                        "find_in_set(#{" + columnLowerCamel + "[" + i + "]}," + columnUnderScore
                                                + ")");
                            }
                            result.append(")");
                        } else {
                            result.append(columnUnderScore);
                            if (exclude != null) {
                                result.append(" NOT");
                            }
                            result.append(" IN (");
                            result.append("#{" + columnLowerCamel + "[0]}");
                            for (int i = 1; i < valueList.size(); i++) {
                                result.append(",#{");
                                result.append(columnLowerCamel + "[" + i + "]}");
                            }
                            result.append(")");
                        }
                    }
                } else if (isNullQuery != null && value instanceof Boolean) {
                    Boolean isNull = (Boolean) value;
                    result.append(" AND ");
                    result.append(columnUnderScore);
                    result.append(" is");
                    if (!isNull) {
                        result.append(" not");
                    }
                    result.append(" null");
                } else {
                    // 是否忽略大小写
                    boolean isIgnore = ignoreCase != null;

                    result.append(" AND ");
                    if (isIgnore) {
                        result.append("LOWER(");
                    }
                    result.append(columnUnderScore);
                    if (isIgnore) {
                        result.append(")");
                    }
                    result.append(" ");
                    if (exclude != null) {
                        result.append("<>");
                    } else if (operator != null && StringUtils.isNotBlank(operator.value())) {
                        result.append(operator.value());
                    } else {
                        result.append("=");
                    }
                    if (operator != null && operator.value() != null && StringUtils.equalsIgnoreCase(
                            operator.value().trim(), "like")) {
                        result.append(" CONCAT('%',");
                        if (isIgnore) {
                            result.append("LOWER(");
                        }
                        result.append("#{");
                        result.append(columnLowerCamel);
                        result.append("}");
                        if (isIgnore) {
                            result.append(")");
                        }
                        result.append(",'%')");
                    } else {
                        result.append(" ");
                        if (isIgnore) {
                            result.append("LOWER(");
                        }
                        result.append("#{");
                        result.append(columnLowerCamel);
                        result.append("}");
                        if (isIgnore) {
                            result.append(")");
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result.toString();
    }

    private static final List<Method> EXCLUDE_UPDATE_METHOD;
    static {
        try {
            EXCLUDE_UPDATE_METHOD = Arrays.asList(
                    BaseDO.class.getMethod("getId"),
                    BaseDO.class.getMethod("getGmtCreated"),
                    BaseDO.class.getMethod("getGmtModified")
            // TODO @ShardingKey
            // TenantBaseDO.class.getMethod("getTenantId")
            );
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    private List<String> getUpdateColumns(BaseDO baseDO, Class<? extends BaseDO> paramClass, boolean selective) {
        List<Method> getters = ClassUtils.getGetters(paramClass);
        // 不支持更新的字段
        getters.removeAll(EXCLUDE_UPDATE_METHOD);

        List<String> result = new ArrayList<String>();
        try {
            for (Method method : getters) {
                Object getResult = method.invoke(baseDO);
                if (!selective || getResult != null) {
                    String column = method.getName().substring(3);
                    result.add(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, column));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private static final List<Method> EXCLUDE_INSERT_METHOD;
    static {
        try {
            EXCLUDE_INSERT_METHOD = Arrays.asList(
                    BaseDO.class.getMethod("getId"),
                    BaseDO.class.getMethod("getGmtCreated"),
                    BaseDO.class.getMethod("getGmtModified"),
                    BaseDO.class.getMethod("getDelTime"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    private List<String> getInsertColumns(BaseDO baseDO, Class<? extends BaseDO> clazz, boolean selective) {
        List<String> result = new ArrayList<>();
        try {
            List<Method> getters = ClassUtils.getGetters(clazz);
            // 固定拼接字段，不需要再拼接
            getters.removeAll(EXCLUDE_INSERT_METHOD);

            for (Method method : getters) {
                Object getResult = method.invoke(baseDO);
                if (!selective || getResult != null) {
                    String column = method.getName().substring(3);
                    result.add(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, column));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private List<String> getInsertParams(List<String> columns) {
        List<String> result = new ArrayList<String>();
        for (String string : columns) {
            String s = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, string);
            result.add("#{" + s + "}");
        }
        return result;
    }

    private List<String> getBatchParams(List<String> columns, int index) {
        List<String> result = new ArrayList<String>();
        for (String string : columns) {
            String s = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, string);
            result.add("#{list[" + index + "]." + s + "}");
        }
        return result;
    }

    private List<String> getColumns(Class<?> paramClass) {
        List<Method> methods = ClassUtils.getGetters(paramClass);
        List<String> result = new ArrayList<>();
        for (Method method : methods) {
            String column = method.getName().substring(3);
            result.add(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, column));
        }
        return result;
    }

    private List<String> getColumnsByMs(MappedStatement ms) {
        if (columnNameCache.containsKey(ms)) {
            return columnNameCache.get(ms);
        }
        String id = ms.getId();
        String className = id.substring(0, id.lastIndexOf("."));
        Class<?> s = MybatisUtils.getClass(className, ms);
        Type[] genType = s.getGenericInterfaces();
        Type[] params = ((ParameterizedType) genType[0]).getActualTypeArguments();
        Class<?> dataObjectClass = (Class<?>) params[1];
        List<String> rawColumns = getColumns(dataObjectClass);
        List<String> resultColumns = getMappedColumnName(dataObjectClass, rawColumns);
        columnNameCache.put(ms, resultColumns);
        return resultColumns;
    }

    private List<String> getMappedColumnName(Class<?> clazz, List<String> oldUnderScoreName) {
        Map<String/* attr name */, String/* db column */> map = new HashMap<String, String>();
        List<Field> fields = ClassUtils.getFieldsByAnnotation(clazz, Column.class);
        for (Field field : fields) {
            Column c = field.getAnnotation(Column.class);
            if (c != null && StringUtils.isNotEmpty(c.value()) && !c.ignore()) {
                map.put(field.getName(), c.value());
            }
        }
        List<String> resultList = new ArrayList<String>();
        for (String string : oldUnderScoreName) {
            String fieldName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, string);
            String columnFromAnnotation = map.get(fieldName);
            if (columnFromAnnotation == null) {
                resultList.add(string);
            } else {
                resultList.add(columnFromAnnotation);
            }
        }
        return resultList;
    }

}
