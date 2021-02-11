package com.alibaba.server.nio.core.page;

import lombok.ToString;

import java.util.*;

/**
 *
 * 类MybatisPageList.java的实现描述：分页结果，仅用于mybatis
 * @author liubei
 * @date 2020/08/27
 */
@ToString
public class MybatisPageList<T> implements List<T> {

	/** 分页展示条数 , 不能小于1*/
	private int		pageSize  = 1;
	/** 当前页码 , 不能小于1 */
	private int		pageIndex = 1;

	private List<T>	records;
	/** 原始记录总数，即不加入任何查询条件的记录总数 */
	private int		originalTotalCount;

	public MybatisPageList(){
		this.records = new ArrayList();
	}

	public MybatisPageList(List<T> records){
		this.records = records;
	}

	public void setRecords(List<T> records) {
		this.records = records;
	}

	/**
	 * @return
	 */
	public List<T> getRecords() {
		return records;
	}

	/**
	 * 原始记录总数，即不加入任何查询条件的记录总数。
	 * 
	 * @return
	 */
	public int getOriginalTotalCount() {
		return originalTotalCount;
	}

	public void setOriginalTotalCount(int originalTotalCount) {
		this.originalTotalCount = originalTotalCount;
	}

	public Iterator<T> iterator() {
		return records.iterator();
	}

	public int size() {
		return records.size();
	}

	public boolean isEmpty() {
		return records.isEmpty();
	}

	public T get(int i) {
		return records.get(i);
	}

	public boolean contains(Object o) {
		return records.contains(o);
	}

	public Object[] toArray() {
		return records.toArray();
	}

	public <E> E[] toArray(E[] ts) {
		return records.toArray(ts);
	}

	public boolean containsAll(Collection<?> objects) {
		return records.containsAll(objects);
	}

	public int indexOf(Object o) {
		return records.indexOf(o);
	}

	public int lastIndexOf(Object o) {
		return records.lastIndexOf(o);
	}

	public List<T> subList(int begin, int end) {
		return records.subList(begin, end);
	}

	@Override
	public boolean add(T e) {
		return records.add(e);
	}

	@Override
	public boolean remove(Object o) {
		return records.remove(o);
	}

	@Override
	public boolean addAll(Collection<? extends T> c) {
		return records.addAll(c);
	}

	@Override
	public boolean addAll(int index, Collection<? extends T> c) {
		return records.addAll(index, c);
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		return records.removeAll(c);
	}

	@Override
	public void clear() {
		records.clear();
	}

	@Override
	public T set(int index, T element) {
		return records.set(index, element);
	}

	@Override
	public void add(int index, T element) {
		records.add(index, element);
	}

	@Override
	public T remove(int index) {
		return records.remove(index);
	}

	@Override
	public ListIterator<T> listIterator() {
		return records.listIterator();
	}

	@Override
	public ListIterator<T> listIterator(int index) {
		return records.listIterator(index);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		return records.retainAll(c);
	}

	/**
	 * @return the pageSize
	 */
	public int getPageSize() {
		if (pageSize < 1) {
			return 1;
		}
		return pageSize;
	}

	/**
	 * @param pageSize the pageSize to set
	 */
	public void setPageSize(int pageSize) {
		if (pageSize < 1) {
			this.pageSize = 1;
		} else {
			this.pageSize = pageSize;
		}
	}

	/**
	 * @return the pageIndex
	 */
	public int getPageIndex() {
		if (pageIndex < 1) {
			return 1;
		}
		return pageIndex;
	}

	/**
	 * @param pageIndex the pageIndex to set
	 */
	public void setPageIndex(int pageIndex) {
		if (pageIndex < 1) {
			this.pageIndex = 1;
		} else {
			this.pageIndex = pageIndex;
		}
	}

}
