package com.alibaba.server.nio.core.repository;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;

import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * 类DynamicSqlSource.java的实现描述：用于生成动态SQL
 * 每个方法会对应一个DynamicSqlSource实例
 * @author liubei
 * @date 2020/08/27
 */
public class DynamicSqlSource implements SqlSource {

	private SqlSourceBuilder sqlSourceParser;
	private Class<?>		 providerType;
	private Method			 providerMethod;
	private MappedStatement	 ms;

	public DynamicSqlSource(SqlSourceBuilder sqlSourceParser, Class<?> providerType, Method providerMethod, MappedStatement ms){
		this.sqlSourceParser = sqlSourceParser;
		this.providerType = providerType;
		this.providerMethod = providerMethod;
		this.ms = ms;
	}

	@Override
	public BoundSql getBoundSql(Object parameterObject) {
		try {
			Class<?>[] parameterTypes = providerMethod.getParameterTypes();
			if (parameterTypes.length <= 2 && parameterTypes[parameterTypes.length - 1].equals(MappedStatement.class)) {
				// 要求providerMethod的参数一定要包含MappedStatement
				String sql = (String) providerMethod.invoke(providerType.newInstance(), parameterObject, ms);
				Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
				SqlSource s = sqlSourceParser.parse(sql, parameterType, new HashMap<String, Object>());
				return s.getBoundSql(parameterObject);
			} else {
				throw new BuilderException("Error in DynamicSqlSource (" + providerType.getName() + "." + providerMethod.getName()
										   + ").  Cause: parameter in this method does not conform to my specification");
			}
		} catch (Exception e) {
			throw new BuilderException("Error in DynamicSqlSource (" + providerType.getName() + "." + providerMethod.getName() + ").  Cause: " + e, e);
		}
	}

}
