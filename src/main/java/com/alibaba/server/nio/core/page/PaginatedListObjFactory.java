package com.alibaba.server.nio.core.page;

import com.alibaba.server.nio.core.result.PageResult;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * 类PaginatedListObjFactory.java的实现描述：
 * 用于将返回的分页结果转换为分页器模型
 * 分页器如果实现List不能被正常序列化
 * @author liubei
 * @date 2020/08/27
 */
public class PaginatedListObjFactory extends DefaultObjectFactory {

    private static final long serialVersionUID = -2652610036827162801L;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        // 特殊逻辑
      if(PageResult.class.equals(type)){
          PageResult list = new PageResult();
          list.setModelList(new ArrayList());
          return (T) list;
      }
      return super.create(type, constructorArgTypes, constructorArgs);
    }
    
    
    @Override
    public <T> boolean isCollection(Class<T> type) {
        // PageResult 需要被识别为collection，才会执行selectList
        if(PageResult.class.equals(type)){
            return true;
        }
        return super.isCollection(type);
    }
}
