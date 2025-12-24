package com.alibaba.server.nio.service.file.model;

import com.alibaba.server.common.BasicConstant;

/**
 * 文件操作响应消息枚举类
 *
 * @author spring
 * */
public enum FileOperateEnum {

    ONLINE_TRANSPORT_CONFIRM("ONLINE_TRANSPORT_SUCCESS", "用户同意在线传输", "1", BasicConstant.CONFIRM_ONLINE_TRANSPORT),
    ONLINE_TRANSPORT_CANCEL("ONLINE_TRANSPORT_CANCEL", "用户取消在线传输", "2", BasicConstant.CANCEL_ONLINE_TRANSPORT),
    ONLINE_TRANSPORT_NO_FILE_TASK("ONLINE_TRANSPORT_NO_FILE_TASK", "文件服务器错误，没有待执行的在线文件传输任务", "3", BasicConstant.CLIENT_CLOSE_SOCKET),
    ONLINE_TRANSPORT_NO_LAUNCH_USER("ONLINE_TRANSPORT_NO_LAUNCH_USER", "对方已下线,传送失败", "4", BasicConstant.CLIENT_CLOSE_SOCKET),
    ONLINE_TRANSPORT_SOCKET_NOT_CONNECTED("ONLINE_TRANSPORT_SOCKET_NOT_CONNECTED", "远程连接已断开，传输失败", "5", BasicConstant.CLIENT_CLOSE_SOCKET),
    ONLINE_TRANSPORT_SUCCESS("ONLINE_TRANSPORT_SUCCESS", "在线传输成功", "6", BasicConstant.ONLINE_TRANSPORT_SUCCESS),
    ONLINE_TRANSPORT_FAIL("ONLINE_TRANSPORT_FAIL", "在线传输失败", "7", BasicConstant.ONLINE_TRANSPORT_FAIL),
    ONLINE_TRANSPORT_NEED_CONFIRM("ONLINE_TRANSPORT_NEED_CONFIRM", "在线传输确认帧", "8", BasicConstant.ONLINE_TRANSPORT_NEED_CONFIRM),
    UPLOAD_TRANSPORT_CONFIRM("UPLOAD_TRANSPORT_CONFIRM", "上传确认帧", "9", BasicConstant.UPLOAD_TRANSPORT_CONFIRM),
    UPLOAD_TRANSPORT_CONTINUE("UPLOAD_TRANSPORT_CONTINUE", "上传续传确认帧", "14", BasicConstant.UPLOAD_TRANSPORT_CONTINUE),
    UPLOAD_TRANSPORT_CONFIRM_REPEAT("UPLOAD_TRANSPORT_CONFIRM_REPEAT", "重复上传确认帧", "10", BasicConstant.UPLOAD_TRANSPORT_CONFIRM_REPEAT),
    DOWNLOAD_TRANSPORT_CONFIRM("DOWNLOAD_TRANSPORT_CONFIRM", "下载确认帧", "11", BasicConstant.DOWNLOAD_TRANSPORT_CONFIRM),
    DOWNLOAD_TRANSPORT_CONFIRM_NOT_EXIST("DOWNLOAD_TRANSPORT_CONFIRM_NOT_EXIST", "下载不存在确认帧", "12", BasicConstant.DOWNLOAD_TRANSPORT_CONFIRM_NOT_EXIST),
    DOWNLOAD_TRANSPORT_STOP("DOWNLOAD_TRANSPORT_STOP", "下载终止", "13", BasicConstant.DOWNLOAD_TRANSPORT_STOP);
    private String description;

    private String message;

    private String type;

    private String operate;

    FileOperateEnum(String description, String message, String type, String operate) {
        this.description = description;
        this.message = message;
        this.type = type;
        this.operate = operate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOperate() {
        return operate;
    }

    public void setOperate(String operate) {
        this.operate = operate;
    }
}
