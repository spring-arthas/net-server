package com.alibaba.server.nio.core.repository;

import com.alibaba.server.nio.core.annotation.CountQuery;
import com.alibaba.server.nio.core.annotation.PageQuery;
import com.alibaba.server.nio.core.page.MybatisPageList;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import com.alibaba.server.util.MybatisUtils;
import com.github.pagehelper.util.MSUtils;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 类PageInterceptor.java的实现描述：mybatis分页拦截器
 *
 * @author liubei
 * @date 2020/08/27
 */
// @Intercepts({ @Signature(type = StatementHandler.class, method = "prepare", args = { Connection.class,Integer.class
// }),})
@Intercepts(@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }))
public class PageInterceptor implements Interceptor {

	@SuppressWarnings({ "rawtypes" })
	public Object intercept(Invocation invocation) throws Throwable {
        // 获取参数
		Object[] args = invocation.getArgs();
		MappedStatement ms = (MappedStatement) args[0];
		Object parameterObject = args[1];
		RowBounds rowBounds = (RowBounds) args[2];
		ResultHandler resultHandler = (ResultHandler) args[3];
		Executor executor = (Executor) invocation.getTarget();
		BoundSql boundSql = ms.getBoundSql(parameterObject);
        // 如果参数不对，或者没有加@PageQuery注解 则放弃拦截
		Annotation anno = getAnnotation(ms);
		if (!(parameterObject instanceof DalPageQueryParam) || anno == null) {
			return invocation.proceed();
		}
        DalPageQueryParam queryParam = (DalPageQueryParam) parameterObject;
		// count(*)
		List countResultList = getQueryCount(ms, queryParam, boundSql, executor, resultHandler);
		if (anno.annotationType() == CountQuery.class) {
			return countResultList;
		}
		Long count = (Long) countResultList.get(0);

        // 原sql后面加上order by ,limit
		MybatisPageList resultList = fillQueryParams(ms, queryParam, boundSql, executor, resultHandler, rowBounds);
		resultList.setOriginalTotalCount(count.intValue());
		resultList.setPageIndex(queryParam.getCurrentPage());
		resultList.setPageSize(queryParam.getPageSize());
		return resultList;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private MybatisPageList fillQueryParams(MappedStatement ms, Object parameterObject, BoundSql boundSql, Executor executor, ResultHandler resultHandler,
									  RowBounds rowBounds) throws SQLException {
        DalPageQueryParam pageQueryDO = (DalPageQueryParam) parameterObject;
		StringBuilder rawSql = new StringBuilder(removeLimitSql(boundSql.getSql()));
        //if (StringUtils.isNotEmpty(pageQueryDO.getGroupBy()) && !containsGroupByStatement(boundSql.getSql())) {
        // // 设置了group by字段，并且原sql不含group by
        //    rawSql.append(" GROUP BY " + pageQueryDO.getGroupBy());
        //}

        if (!containsOrderByStatement(boundSql.getSql())) {
            String orderBySql = MybatisUtils.getOrderBySql(pageQueryDO);
            rawSql.append(orderBySql);
        }

		// append limit
        String limitSql = MybatisUtils.getLimitSql(pageQueryDO);
        rawSql.append(limitSql);

		BoundSql limitBoundSql = new BoundSql(ms.getConfiguration(), rawSql.toString(), boundSql.getParameterMappings(), parameterObject);
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
            String prop = parameterMapping.getProperty();
            if (boundSql.hasAdditionalParameter(prop)) {
                limitBoundSql.setAdditionalParameter(prop, boundSql.getAdditionalParameter(prop));
            }
        }
		CacheKey pageKey = executor.createCacheKey(ms, parameterObject, rowBounds, limitBoundSql);
		List limitResultList = executor.query(ms, parameterObject, RowBounds.DEFAULT, resultHandler, pageKey, limitBoundSql);
		MybatisPageList pageList = new MybatisPageList();

		pageList.setRecords(limitResultList);
		return pageList;
	}

	@SuppressWarnings("rawtypes")
	private List getQueryCount(MappedStatement ms, Object parameterObject, BoundSql boundSql, Executor executor, ResultHandler resultHandler) throws SQLException {
        // 创建 缓存 key 以防错误缓存
		CacheKey countKey = executor.createCacheKey(ms, parameterObject, RowBounds.DEFAULT, boundSql);
		countKey.update("_COUNT");
        // 拼装count sql
		String countSql = getAutoCountSql(boundSql.getSql());
		BoundSql countBoundSql = new BoundSql(ms.getConfiguration(), countSql, boundSql.getParameterMappings(), parameterObject);
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
            String prop = parameterMapping.getProperty();
            if (boundSql.hasAdditionalParameter(prop)) {
                countBoundSql.setAdditionalParameter(prop, boundSql.getAdditionalParameter(prop));
            }
        }
        // 返回类型改变，需要创建新的MappedStatement
		MappedStatement countMs = MSUtils.newCountMappedStatement(ms);
		Object countResultList = executor.query(countMs, parameterObject, RowBounds.DEFAULT, resultHandler, countKey, countBoundSql);
		return (List) countResultList;
	}

    private Annotation getAnnotation(MappedStatement ms) {
        String id = ms.getId();
        if (id.contains(MapConfiguration.MAP_SPLITER)) {
            id = id.substring(id.indexOf(MapConfiguration.MAP_SPLITER) + MapConfiguration.MAP_SPLITER.length());
        }
        String mapperClassName = id.substring(0, id.lastIndexOf('.'));
        String methodName = id.substring(id.lastIndexOf('.') + 1);
        Class<?> mapper = getClass(mapperClassName, ms);
        Method[] methods = mapper.getMethods();
        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                PageQuery anno = method.getAnnotation(PageQuery.class);
                if (anno != null) {
                    return anno;
                }
                CountQuery countAnno = method.getAnnotation(CountQuery.class);
                if (countAnno != null) {
                    return countAnno;
                }
                break;
            }
        }
        return null;
    }
	
    private Class<?> getClass(String className, MappedStatement ms) {
        MapConfiguration config = (MapConfiguration) ms.getConfiguration();
        try {
            return Class.forName(className, true, config.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
	   

	/**
     * 拦截sql语句
	 */
	public Object plugin(Object target) {
		return Plugin.wrap(target, this);
	}

	/**
	 * no use
	 */
	public void setProperties(Properties properties) {
	}

    protected String getAutoCountSql(String sql) {
        sql = removeLimitSql(sql);
        String sqlString = sql.trim().replaceAll("\t", " ").replaceAll("\n", " ");
        // 判断group by或者distinct，否则直接替换count
        if (containsGroupByStatement(sqlString) || containsDistinctStatement(sqlString)) {
            String sqlCount = "select count(*) from (" + sql + ") _auto_count_4_page";
            return sqlCount;
        }
        int index = findFirstFromKeyword(sqlString, " FROM ");
        String sqlCount = "select count(*) " + sqlString.substring(index, sqlString.length());
        return sqlCount;
    }
    
    protected String removeLimitSql(String sql) {
        String sqlString = sql.trim().replaceAll("\t", " ").replaceAll("\n", " ");
        int index = findFirstFromKeyword(sqlString, " LIMIT ");
        if (index <= 0) {
            return sqlString;
        }
        String sqlResult = sqlString.substring(0, index);
        return sqlResult;
    }
	   
	/**
	 * @param sqlString
	 * @return
	 */
	private int findFirstFromKeyword(String sqlString,String keyWord) {
		String sqlUpperCase = sqlString.toUpperCase();
		return sqlUpperCase.indexOf(keyWord.toUpperCase());
	}

    private Pattern orderByPattern  = Pattern.compile("\\s+ORDER\\s+BY\\s+", Pattern.CASE_INSENSITIVE);
    private Pattern groupByPattern  = Pattern.compile("\\s+GROUP\\s+BY\\s+", Pattern.CASE_INSENSITIVE);
    private Pattern distinctPattern = Pattern.compile("\\s+DISTINCT\\s*\\(", Pattern.CASE_INSENSITIVE);

    private boolean containsOrderByStatement(String sqlString) {
        String sqlUpperCase = sqlString.replaceAll("\t", " ").replaceAll("\n", " ");
        Matcher m = orderByPattern.matcher(sqlUpperCase);
        return m.find();
    }

    private boolean containsGroupByStatement(String sqlString) {
        Matcher m = groupByPattern.matcher(sqlString);
        return m.find();
    }

    private boolean containsDistinctStatement(String sqlString) {
        Matcher m = distinctPattern.matcher(sqlString);
        return m.find();
    }
}
