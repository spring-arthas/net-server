package com.alibaba.server.nio.model.file.request;

import lombok.Data;

/**
 * @author pangfuzhong
 * @date 2026/2/6
 * @description 文件上传请求对象
 */
@Data
public class FileUploadRequest {
	private String md5;
	private String fileName;
	private long fileSize;
	private String fileType;
	private Long dirId;
	private Integer userId;
	private String userName;
	private String taskId;
	private String transferToken;
	private String uploadPurpose;
	/** 仅聊天附件批次可请求在单文件完成后保留当前上传连接。 */
	private Boolean connectionReuse;
	/** 客户端消息批次标识，只用于关联和诊断，不替代 taskId。 */
	private String batchId;
}
