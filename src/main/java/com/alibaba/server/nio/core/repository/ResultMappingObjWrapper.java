package com.alibaba.server.nio.core.repository;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;

import java.util.List;
import java.util.Map;

/**
 *
 * 类ResultMappingObjWrapper.java的实现描述：
 * @author liubei
 * @date 2020/08/27
 */
public class ResultMappingObjWrapper implements ObjectWrapper{

    /**
     * 使用@Column注释过的DO字段映射关系
     */
    private Map<String/*db column*/,String/*DO attribute*/> columnMap;
    
    private ObjectWrapper objectWrapper;
    
    public ResultMappingObjWrapper(Map<String, String> columnMap, ObjectWrapper objectWrapper){
        super();
        this.columnMap = columnMap;
        this.objectWrapper = objectWrapper;
    }

    @Override
    public Object get(PropertyTokenizer prop) {
        return objectWrapper.get(prop);
    }

    @Override
    public void set(PropertyTokenizer prop, Object value) {
        objectWrapper.set(prop, value);
    }

    @Override
    public String findProperty(String name, boolean useCamelCaseMapping) {
        if(columnMap.containsKey(name)){
            return columnMap.get(name);
        }
        return objectWrapper.findProperty(name, useCamelCaseMapping);
    }

    @Override
    public String[] getGetterNames() {
        return objectWrapper.getGetterNames();
    }

    @Override
    public String[] getSetterNames() {
        return objectWrapper.getSetterNames();
    }

    @Override
    public Class<?> getSetterType(String name) {
        return objectWrapper.getSetterType(name);
    }

    @Override
    public Class<?> getGetterType(String name) {
        return objectWrapper.getGetterType(name);
    }

    @Override
    public boolean hasSetter(String name) {
        return objectWrapper.hasSetter(name);
    }

    @Override
    public boolean hasGetter(String name) {
        return objectWrapper.hasGetter(name);
    }

    @Override
    public MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory) {
        return objectWrapper.instantiatePropertyValue(name, prop, objectFactory);
    }

    @Override
    public boolean isCollection() {
        return objectWrapper.isCollection();
    }

    @Override
    public void add(Object element) {
        objectWrapper.add(element);
    }

    @Override
    public <E> void addAll(List<E> element) {
        objectWrapper.addAll(element);
    }

}
