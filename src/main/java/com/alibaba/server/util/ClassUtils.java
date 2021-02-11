package com.alibaba.server.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author liubei
 * @date 2020/09/16
 */
public class ClassUtils {

    /**
     * 获取所有getter方法，仅包括public修饰符
     *
     * @param clazz
     * @return
     */
    public static List<Method> getGetters(Class<?> clazz) {
        return Arrays.stream(clazz.getMethods())
            .filter(m -> !m.getDeclaringClass().equals(Object.class))
            .filter(m -> m.getName().startsWith("get"))
            .collect(Collectors.toList());
    }

    /**
     * 返回所有该类和他的父类的属性，包括所有修饰符
     *
     * @param clazz
     * @return
     */
    public static List<Field> getFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (; clazz != Object.class; clazz = clazz.getSuperclass()) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
        }
        return fields;
    }

    /**
     * 从所有该类和他的父类中查找属性，包括所有修饰符
     *
     * @param clazz
     * @param fieldName
     * @return
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        for (; clazz != Object.class; clazz = clazz.getSuperclass()) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // ignore
            }
        }
        throw new RuntimeException(fieldName);
    }

    /**
     * 返回所有该类和他的父类的属性，包括所有修饰符
     *
     * @param clazz
     * @return
     */
    public static <T extends Annotation> List<Field> getFieldsByAnnotation(Class<?> clazz, Class<T> annotationClass) {
        List<Field> fields = new ArrayList<>();
        for (; clazz != Object.class; clazz = clazz.getSuperclass()) {
            List<Field> fieldList = Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.getAnnotation(annotationClass) != null)
                .collect(Collectors.toList());
            fields.addAll(fieldList);
        }
        return fields;
    }

    public static Object getFieldValue(Object obj, Field field) {
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }
        try {
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }


    public static Object getFieldValue(Object obj, String fieldName) {
        Field field = getField(obj.getClass(), fieldName);
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }
        try {
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setFieldValue(Object obj, Field field, Object value) {
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setFieldValue(Object obj, String fieldName, Object value) {
        Field field = getField(obj.getClass(), fieldName);
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 得到方法返回值指定下标的泛型类型
     * @param method
     * @param index
     * @return
     */
    public static <T> Class<T> getGenericReturnType(Method method, int index) {
        Type type = method.getGenericReturnType();
        return getGenericTypeByIndex(type, index);
    }

    /**
     * 得到方法指定下标参数的指定下标的泛型类型
     * @param method
     * @param parameterIndex
     * @param index
     * @return
     */
    public static <T> Class<T> getGenericParameterType(Method method, int parameterIndex, int index) {
        Type[] parameterTypes = method.getGenericParameterTypes();
        Type type = parameterTypes[parameterIndex];
        return getGenericTypeByIndex(type, index);
    }

    /**
     * 得到属性指定下标的泛型类型
     * @param field
     * @param index
     * @return
     */
    public static <T> Class<T> getGenericType(Field field, int index) {
        Type type = field.getGenericType();
        return getGenericTypeByIndex(type, index);
    }

    /**
     * 得到父类型指定下标的泛型类型
     * @param clazz
     * @param index
     * @return
     */
    public static <T> Class<T> getSuperClassGenericType(Class<?> clazz, int index) {
        Type type = clazz.getGenericSuperclass();
        return getGenericTypeByIndex(type, index);
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> getGenericTypeByIndex(Type type, int index) {
        if (!(type instanceof ParameterizedType)) {
            throw new IllegalArgumentException(type + "没有使用泛型");
        }
        Type[] params = ((ParameterizedType) type).getActualTypeArguments();

        if (!(params[index] instanceof Class)) {
            throw new IllegalArgumentException(params[index] + "不是Class");
        }
        return (Class<T>) params[index];
    }


}
