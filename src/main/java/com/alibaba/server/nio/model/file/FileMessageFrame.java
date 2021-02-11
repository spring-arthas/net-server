package com.alibaba.server.nio.model.file;

import com.alibaba.server.nio.service.file.model.FileTransport;
import lombok.Data;
import java.nio.channels.FileChannel;

/**
 * @author: YSFY
 * @Date: 2020-11-21 21:09
 * @Pacage_name: com.alibaba.server.nio.model.chat
 * @Project_Name: net-server
 * @Description: 文件帧
 *
 *   上传帧
 *      帧类型：1B --> 2bit --> 上传 下载 在线传输
 *      文件类型: 1B --> 6bit--> .txt | .xlsx | .mp4 | .word ......
 *      文件操作类型: 1B --> 1b
 *      帧总数据长度: 2B
 *      文件名称字节数组长度: 2B
 *      文件大小： 8B
 *      动态数据： ~ 不确定
 *
 *    在线传输帧
 *      帧类型：1B --> 2bit --> 上传 下载 在线传输
 *      文件类型: 1B --> 6bit--> .txt | .xlsx | .mp4 | .word ......
 *      文件操作类型: 1B --> 1b
 *      帧总数据长度: 2B
 *      动态数据： ~ 不确定
 *
 *   下载帧
 */

@Data
public class FileMessageFrame {
    /**
     * 是否结束帧 + 序号 + 帧类型 + 文件类型 + 文件操作类型 + 文件名称长度 + 文件大小 = 2 + 1 + 4 + 1 + 1 + 1 + 2 + 8= 18
     * */
    public static final Byte UPLOAD_COMMON = 18;

    public static final Byte ONLINE_TRANSPORT_COMMON = 3;

    /**
     * 帧类型 --> 1B
     */
    public enum FrameType {
        UPLOAD("UPLOAD", "上传帧", "00000000"),
        DOWNLOAD("DOWNLOAD", "下载帧", "00000001"),
        ONLINE_TRANSPORT("ONLINE_TRANSPORT", "在线传输", "00000010"),
        DELETE("DELETE", "删除帧", "00000011"),
        // 文件流数据传输帧
        DATA_TRANSPORT("DATA_TRANSPORT", "实时文件流数据帧", "00000100"),
        // 文件流数据结束帧
        DATA_TRANSPORT_END("DATA_TRANSPORT_END", "文件流结束帧", "00000101"),
        // 上传确认帧
        UPLOAD_TRANSPORT_CONFIRM("UPLOAD_TRANSPORT_CONFIRM", "上传确认帧", "00000110");

        private String frameType;

        private String description;

        private String bit;

        public String getFrameType() {
            return frameType;
        }

        public String getDescription() {
            return description;
        }

        public String getBit() {
            return bit;
        }

        FrameType(String frameType, String description, String bit) {

            this.frameType = frameType;
            this.description = description;
            this.bit = bit;
        }
    }

    /*
    * 文件类型 1B
    * */
    public enum FileType {
        MP4("mp4", "上传帧", "00000000"),
        AVI("avi", "下载帧", "00000001"),
        RMVB("rmvb", "下载帧", "00000010"),

        TXT("txt", "下载帧", "00000011"),
        XLSX("xlsx", "下载帧", "00000100"),
        XLS("xls", "下载帧", "00000101"),
        WORD("word", "下载帧", "00000110"),
        TAR("tar", "下载帧", "00000111"),
        ZIP("zip", "下载帧", "00001000"),
        EXE("exe", "下载帧", "00001001"),
        XLN("xln", "下载帧", "00001010"),
        JAVA("java", "下载帧", "00001011"),

        ALL("ALL", "共有帧", "11111111");

        private String fileType;

        private String description;

        private String bit;

        public String getFileType() {
            return fileType;
        }

        public String getDescription() {
            return description;
        }

        public String getBit() {
            return bit;
        }

        FileType(String fileType, String description, String bit) {

            this.fileType = fileType;
            this.description = description;
            this.bit = bit;
        }
    }

    /**
     * 文件操作类型
     * */
    public enum FileOperateType {
        TRANSPORT("TRANSPORT", "文件传送", "00000000"),
        STORE("STORE", "文件存储", "00000001");

        private String frameType;

        private String description;

        private String bit;

        public String getFrameType() {
            return frameType;
        }

        public String getDescription() {
            return description;
        }

        public String getBit() {
            return bit;
        }

        FileOperateType(String frameType, String description, String bit) {
            this.frameType = frameType;
            this.description = description;
            this.bit = bit;
        }
    }

    /**
     * 帧类型
     * */
    private FileMessageFrame.FrameType frameType;

    /**
     * 文件类型
     * */
    private FileMessageFrame.FileType fileType;

    /**
     * 文件操作类型
     * */
    private FileMessageFrame.FileOperateType fileOperateType;

    /**
     * 帧序号
     * */
    private int frameIndex;

    /**
     * 帧数据总长度
     * */
    private Short frameSumLength;

    /**
     * 文件名称长度
     * */
    private Short fileNameLength;

    /**
     * 是否为结束帧
     * */
    private byte endFrame;

    /**
     * 文件实际大小
     * */
    private Long fileLength;

    /**
     * 文件帧持有的文件传输对象
     * */
    private FileTransport fileTransport;

    /**
     * 本地文件通道
     * */
    private FileChannel fileChannel;

    /**
     * 数据长度
     * */
    private Short dataLength;

    /**
     * 数据 --> json string
     *   currentUserName : 指定谁发送
     *   remoteUserName: 发送给谁
     *   group: 分组信息，基于文件存储根路径按照group规定的方式进行文件路径拼接
     *   fileName : 文件名称
     * */
    private String data;
}
