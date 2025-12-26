package com.alibaba.server.nio.model;

import com.google.common.collect.Lists;
import lombok.Data;

import java.util.List;

/**
 * @author pangfuzhong
 * @date 2025/12/26
 * @description 传输数据
 */
@Data
public class TransportDataModel {
	/**
	 * 数据类型 @see com.alibaba.server.nio.model.ChannelEventModelEnum
	 * */
	private String dataType;
	/**
	 * 待处理的字节数据(未进行粘包半包处理)
	 * */
	private List<ChannelEventModel.GroupData> waitHandleDataList = Lists.newArrayList();
}
