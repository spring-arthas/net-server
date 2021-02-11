package com.alibaba.server.nio.core.page;

import com.alibaba.server.nio.core.result.PageResult;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.wrapper.CollectionWrapper;
import java.util.List;


/**
 * 类PaginatedListWrapper.java的实现描述：
 *
 * @author liubei
 * @date 2020/08/27
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class PaginatedListWrapper extends CollectionWrapper {

	private PageResult pageList;

	public PaginatedListWrapper(MetaObject metaObject, PageResult pageList){
		super(metaObject, pageList.getModelList());
		this.pageList = pageList;
	}

	@Override
	public <E> void addAll(List<E> element) {
		// 传入的element一定是MybatisPageList
		// 如果此行报错说明没加 @PageQuery注解，或者方法参数不是QueryParam的子类
		MybatisPageList mybatisPageList = (MybatisPageList<E>) element;
		pageList.setTotalCount(mybatisPageList.getOriginalTotalCount());
		pageList.setCurrentPage(mybatisPageList.getPageIndex());
		pageList.setPageSize(mybatisPageList.getPageSize());
		super.addAll(element);
	}
}
