package com.alibaba.server.nio.core.repository;

import com.alibaba.server.common.ClassLoaderProvider;
import com.alibaba.server.nio.core.annotation.Batch;
import com.alibaba.server.nio.core.annotation.Column;
import com.alibaba.server.nio.core.annotation.MapMethod;
import com.alibaba.server.util.ClassUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.builder.annotation.ProviderSqlSource;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.MappedStatement.Builder;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 类MyConfiguration.java的实现描述： 自定义Mybatis的配置类，实现以下功能
 * 1. 实现mapper的方法映射，对应@MapMethod注解
 * 2. 向DynamicSQLProvider注入额外属性
 * 3. 返回结果通过@Column完成字段映射
 *
 * @author liubei
 * @date 2020/08/27
 */
public class MapConfiguration extends Configuration {

	/**
	 * 缓存了所有@MapMethod表示的映射关系
	 */
	protected Map<String, AnnotationResult>													userDefinedMapping = new ConcurrentHashMap<String, AnnotationResult>();
	/**
	 * 缓存了所有拥有@MapMethod注解的MappedStatement
	 */
	protected Map<String, MappedStatement>													 bufferedMs		 = new ConcurrentHashMap<String, MappedStatement>();
	/**
	 * 使用@Column注释过的DO字段映射关系
	 */
	private Map<String/*class name*/, Map<String/* db column */, String/* DO attribute */>> columnMap		  = new ConcurrentHashMap<String, Map<String, String>>();
	
	private ClassLoaderProvider classLoaderProvider;

	public static final String																 MAP_SPLITER		= "_MAP_";

	@Override
	public MappedStatement getMappedStatement(String id, boolean validateIncompleteStatements) {
		// 不影响原有逻辑
		if (validateIncompleteStatements) {
			buildAllStatements();
		}
		if (bufferedMs.containsKey(id)) {
			return bufferedMs.get(id);
		}
		AnnotationResult newId = getAnnotations(id);
		MappedStatement resultStatement;
		if (StringUtils.isEmpty(newId.mappedId)) {
			resultStatement = mappedStatements.get(id);
		} else {
			// 第一次调用sql时会组建一个新的MappedStatement
			if (newId.selfFirst) {
				try {
					resultStatement = getOldIdStatement(id);
				} catch (IllegalArgumentException e) {
					resultStatement = getNewIdStatement(newId.mappedId, id);
				}
			} else {
				try {
					resultStatement = getNewIdStatement(newId.mappedId, id);
				} catch (IllegalArgumentException e) {
					resultStatement = getOldIdStatement(id);
				}
			}
		}
		if (newId.isBatch) {
			BatchSqlSource batchSqlSource = new BatchSqlSource(resultStatement.getSqlSource(), this);
			ClassUtils.setFieldValue(resultStatement, "sqlSource", batchSqlSource);
		}
		return resultStatement;
	}

	private MappedStatement getOldIdStatement(String id) {
		MappedStatement ms = mappedStatements.get(id);
		bufferedMs.put(id, ms);
		return ms;
	}

	private MappedStatement getNewIdStatement(String newId, String oldId) {
		MappedStatement ms = mappedStatements.get(newId);
		String combineId = newId + MAP_SPLITER + oldId;
		MappedStatement newMs = buildNewMs(ms, combineId);
		bufferedMs.put(combineId, newMs);
		return newMs;
	}

	protected MappedStatement buildNewMs(MappedStatement ms, String combineId) {
		Builder builder = new Builder(ms.getConfiguration(), combineId, ms.getSqlSource(), ms.getSqlCommandType());
		builder.resource(ms.getResource());
		builder.fetchSize(ms.getFetchSize());
		builder.statementType(ms.getStatementType());
		builder.keyGenerator(ms.getKeyGenerator());
		if (ms.getKeyProperties() != null && ms.getKeyProperties().length != 0) {
			StringBuilder keyProperties = new StringBuilder();
			for (String keyProperty : ms.getKeyProperties()) {
				keyProperties.append(keyProperty).append(",");
			}
			keyProperties.delete(keyProperties.length() - 1, keyProperties.length());
			builder.keyProperty(keyProperties.toString());
		}
		builder.timeout(ms.getTimeout());
		builder.parameterMap(ms.getParameterMap());
		builder.resultMaps(ms.getResultMaps());
		builder.resultSetType(ms.getResultSetType());
		builder.cache(ms.getCache());
		builder.flushCacheRequired(ms.isFlushCacheRequired());
		builder.useCache(ms.isUseCache());
		return builder.build();
	}

	@Override
	public boolean hasStatement(String statementName, boolean validateIncompleteStatements) {
		if (validateIncompleteStatements) {
			buildAllStatements();
		}
		AnnotationResult mappedStat = getAnnotations(statementName);
		if (StringUtils.isEmpty(mappedStat.mappedId)) {
			return mappedStatements.containsKey(statementName);
		}
		return mappedStatements.containsKey(mappedStat.mappedId) || mappedStatements.containsKey(statementName);
	}

	/**
	 * 根据id取到对应方法上的注解
	 * 有缓存
	 * @param id
	 * @return
	 */
	protected AnnotationResult getAnnotations(String id) {
		AnnotationResult cachedId = userDefinedMapping.get(id);
		if (cachedId != null) {
			return cachedId;
		}
		try {
			String mapperClassName = id.substring(0, id.lastIndexOf('.'));
			String methodName = id.substring(id.lastIndexOf('.') + 1);
			Class<?> mapper = Class.forName(mapperClassName, true, this.getClassLoader());
			Method[] methods = mapper.getMethods();
			for (Method method : methods) {
				if (method.getName().equals(methodName)) {
					MapMethod anno = method.getAnnotation(MapMethod.class);
					Batch batch = method.getAnnotation(Batch.class);
					AnnotationResult result = new AnnotationResult();
					if (anno != null) {
						if (StringUtils.isNotBlank(anno.value())) {
							String newId = mapperClassName + "." + anno.value();
							result.mappedId = newId;
							result.selfFirst = anno.selfFirst();
						}
					}
					if (batch != null) {
						result.isBatch = true;
					}
					userDefinedMapping.put(id, result);
					return result;
				}
			}
		} catch (Exception e) {
			// ignore
			throw new RuntimeException(e);
		}
		// 代表一个空结果
		AnnotationResult empty = new AnnotationResult();
		userDefinedMapping.put(id, empty);
		return empty;
	}

	private class AnnotationResult {

		private String  mappedId;
		private boolean selfFirst;
		private boolean isBatch;
	}

	@Override
	public void addMappedStatement(MappedStatement ms) {
		if (mappedStatements.containsKey(ms.getId())) {
			//说明xml对应的资源已经存在，忽略
			return;
		}
		// DynamicSQLProvider 特殊处理
		SqlSource sqlSource = ms.getSqlSource();
		if (sqlSource instanceof ProviderSqlSource) {
			try {
				Field typeField = ProviderSqlSource.class.getDeclaredField("providerType");
				typeField.setAccessible(true);
				Class<?> providerClass = (Class<?>) typeField.get(sqlSource);
				if (providerClass.equals(DynamicSQLProvider.class)) {
					// 获取原ProviderSqlSource的属性
					Field sqlSourceParserField = ProviderSqlSource.class.getDeclaredField("sqlSourceParser");
					Field providerMethodField = ProviderSqlSource.class.getDeclaredField("providerMethod");
					sqlSourceParserField.setAccessible(true);
					providerMethodField.setAccessible(true);
					SqlSourceBuilder sqlSourceParser = (SqlSourceBuilder) sqlSourceParserField.get(sqlSource);
					Method providerMethod = (Method) providerMethodField.get(sqlSource);
					// 使用新的DynamicSqlSource
					sqlSource = new DynamicSqlSource(sqlSourceParser, providerClass, providerMethod, ms);
					// 注入回ms中
					ClassUtils.setFieldValue(ms, "sqlSource", sqlSource);
				}
			} catch (Exception e) {
				// ignore?
				throw new RuntimeException(e);
			}
		}
		super.addMappedStatement(ms);
	}

	@Override
	public void addKeyGenerator(String id, KeyGenerator keyGenerator) {
		if (keyGenerators.containsKey(id)) {
			//说明xml对应的资源已经存在，忽略
			return;
		}
		keyGenerators.put(id, keyGenerator);
	}

	@Override
	public MetaObject newMetaObject(Object object) {
		MetaObject metaObj = super.newMetaObject(object);
		if (object == null) {
			return metaObj;
		}
		// 替换metaObject的ObjectWrapper为自定义的wrapper
		Class<?> metaClass = object.getClass();
		Map<String, String> thisColumnMap = columnMap.get(metaClass.getName());
		if (thisColumnMap == null) {
			// 初始化
			thisColumnMap = new HashMap<String, String>();
			List<Field> fields = ClassUtils.getFieldsByAnnotation(metaClass, Column.class);
			for (Field field : fields) {
				Column columnAnno = field.getAnnotation(Column.class);
				if (columnAnno != null && StringUtils.isNotEmpty(columnAnno.value()) && !columnAnno.ignore()) {
					thisColumnMap.put(columnAnno.value(), field.getName());
				}
			}
			columnMap.put(metaClass.getName(), thisColumnMap);
		}
		ResultMappingObjWrapper myWrapper = new ResultMappingObjWrapper(thisColumnMap, metaObj.getObjectWrapper());
		ClassUtils.setFieldValue(metaObj, "objectWrapper", myWrapper);
		return metaObj;
	}

    public ClassLoader getClassLoader() {
        if (classLoaderProvider == null) {
            return this.getClass().getClassLoader();
        }
        return classLoaderProvider.getClassLoader();
    }

    public void setClassLoaderProvider(ClassLoaderProvider classLoaderProvider) {
        this.classLoaderProvider = classLoaderProvider;
    }
	
}
