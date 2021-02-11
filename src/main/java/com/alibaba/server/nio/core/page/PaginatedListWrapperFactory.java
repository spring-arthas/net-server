package com.alibaba.server.nio.core.page;

import com.alibaba.server.nio.core.result.PageResult;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;

/**
 * 类PaginatedListWrapperFactory.java的实现描述：
 * 用于将返回的分页结果转换为分页器模型
 * 分页器如果实现List不能被正常序列化
 * @author liubei
 * @date 2020/08/27
 */
public class PaginatedListWrapperFactory extends DefaultObjectWrapperFactory{

    @Override
    public boolean hasWrapperFor(Object object) {
        if(object instanceof PageResult){
            return true;
        }
        return super.hasWrapperFor(object);
    }
    
    @SuppressWarnings({ "rawtypes"})
    @Override
    public ObjectWrapper getWrapperFor(MetaObject metaObject, Object object) {
        if(object instanceof PageResult){
            PageResult pageList = (PageResult)object;
            return new PaginatedListWrapper(metaObject, pageList);
        }
        return super.getWrapperFor(metaObject, object);
    }
}
