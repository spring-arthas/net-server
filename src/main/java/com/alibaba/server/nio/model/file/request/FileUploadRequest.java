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
}
