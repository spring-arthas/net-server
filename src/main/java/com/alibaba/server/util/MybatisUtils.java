package com.alibaba.server.util;

import com.alibaba.server.nio.core.annotation.Table;
import com.alibaba.server.nio.core.dataobject.BaseDO;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import com.alibaba.server.nio.core.repository.MapConfiguration;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.mapping.MappedStatement;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * mybati的工具类
 *
 * @author JiaJu Zhuang
 * @date 2020/9/1 9:10 下午
 **/
public class MybatisUtils {

    private static final Map<MappedStatement, String> TABLE_NAME_CACHE = Maps.newConcurrentMap();
    /**
     * 存储后缀
     */
    private static final String REPOSITORY = "Repository";

    /**
     * 获取表名
     *
     * @param ms
     * @return
     */
    public static String getTableNameByMs(MappedStatement ms) {
        if (TABLE_NAME_CACHE.containsKey(ms)) {
            return TABLE_NAME_CACHE.get(ms);
        }
        String id = ms.getId();
        String className = id.substring(0, id.lastIndexOf("."));
        Class<?> s = getClass(className, ms);
        Table table = s.getAnnotation(Table.class);
        if (table != null && StringUtils.isNotEmpty(table.value())) {
            TABLE_NAME_CACHE.put(ms, table.value());
            return table.value();
        }

        // 截取后两个"."之间的类名
        int lastDotIndex = 0;
        int secondLastDotIndex = 0;

        for (int i = id.length() - 1; i >= 0; i--) {
            if (id.charAt(i) != '.') {
                continue;
            }
            if (lastDotIndex == 0) {
                lastDotIndex = i;
            } else {
                secondLastDotIndex = i;
                break;
            }
        }
        String mapperName = id.substring(secondLastDotIndex + 1, lastDotIndex);
        if (mapperName.endsWith(REPOSITORY)) {
            String tableCamel = mapperName.substring(0, mapperName.length() - REPOSITORY.length());
            String tableName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, tableCamel);
            TABLE_NAME_CACHE.put(ms, tableName);
            return tableName;
        }
        throw new RuntimeException("Mapper class name must end with 'Repository'");
    }

    public static String getTableNameByDO(Class<?> paramClass) {
        if (!BaseDO.class.isAssignableFrom(paramClass)) {
            throw new RuntimeException("DO class name must extends 'BaseDO'");
        }

        Table table = paramClass.getAnnotation(Table.class);
        if (table != null && StringUtils.isNotEmpty(table.value())) {
            return table.value();
        }

        String name = paramClass.getName();
        String tableCamel = name.substring(name.lastIndexOf('.') + 1, name.length() - 2);
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, tableCamel);
    }

    public static Class<?> getClass(String className, MappedStatement ms) {
        MapConfiguration config = (MapConfiguration)ms.getConfiguration();
        try {
            return Class.forName(className, true, config.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getOrderBySql(DalPageQueryParam queryParam) {
        return Optional.ofNullable(queryParam.getOrderBy())
            .filter(list -> !list.isEmpty())
            .map(list -> " ORDER BY " + list.stream()
                .map(orderBy -> {
                    String column = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, orderBy.getProperty());
                    String direction = orderBy.getDirection() == null ? "" : orderBy.getDirection().name();
                    return column + " " + direction;
                }).collect(Collectors.joining(","))
            ).orElse("");
    }

    public static String getLimitSql(DalPageQueryParam queryParam) {
        return " LIMIT " + (queryParam.getCurrentPage() - 1) * queryParam.getPageSize()
            + "," + queryParam.getPageSize();
    }

}
