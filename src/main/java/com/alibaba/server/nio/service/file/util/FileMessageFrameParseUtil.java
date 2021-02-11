package com.alibaba.server.nio.service.file.util;

import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.util.ByteOrderConvert;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * 文件帧解析工具
 * @author spring
 * */
@Slf4j
public class FileMessageFrameParseUtil {

    /**
     * 解析帧类型、文件操作类型、文件类型
     * @param frameTypeByte  帧类型字节
     * @param fileTypeByte   文件类型字节
     * @param fileOperateByte 文件操作类型字节
     * @param fileMessageFrame 当前文件帧
     */
    public static void executeFileBasicTypeParse(byte frameTypeByte, byte fileTypeByte, byte fileOperateByte, FileMessageFrame fileMessageFrame) {
        StringBuilder stringBuilder = new StringBuilder("");

        // 1、解析帧类型
        stringBuilder.append(Integer.toBinaryString(frameTypeByte & 0xff));
        while (stringBuilder.toString().length() != 8) {
            stringBuilder.insert(0, "0");
        }
        Arrays.asList(FileMessageFrame.FrameType.values())
            .stream()
            .filter(predict -> (StringUtils.equals(predict.getBit(), stringBuilder.toString())))
            .forEach(frameType -> {
                fileMessageFrame.setFrameType(frameType);
            });

        // 2、解析并设置文件类型
        stringBuilder.delete(0, stringBuilder.length());
        stringBuilder.append(Integer.toBinaryString(fileTypeByte & 0xff));
        while (stringBuilder.toString().length() != 8) {
            stringBuilder.insert(0, "0");
        }
        Arrays.asList(FileMessageFrame.FileType.values())
            .stream()
            .filter(predict -> (StringUtils.equals(predict.getBit(), stringBuilder.toString())))
            .forEach(fileType -> {
                fileMessageFrame.setFileType(fileType);
            });

        // 3、解析并设置文件操作类型
        stringBuilder.delete(0, stringBuilder.length());
        stringBuilder.append(Integer.toBinaryString(fileOperateByte & 0xff));
        while (stringBuilder.toString().length() != 8) {
            stringBuilder.insert(0, "0");
        }
        Arrays.asList(FileMessageFrame.FileOperateType.values())
            .stream()
            .filter(predict -> (StringUtils.equals(predict.getBit(), stringBuilder.toString())))
            .forEach(fileOperateType -> {
                fileMessageFrame.setFileOperateType(fileOperateType);
            });
    }

    /**
     * 上传帧解析: 解析模板 帧总字节数：13
     *      帧类型：1B
     *      文件类型: 1B
     *      文件操作类型: 1B
     *      帧总数据长度: 2B
     *      文件名称字节数组长度: 2B
     *      文件大小： 8B
     *      动态数据： ~ 不确定
     *
     * @param i 解析起始索引位置
     * @param sumBytes
     * @param fileMessageFrame
     * @return index 返回文件基本数据解析后的字节索引，即读取真实数据开始的索引位置
     */
    public static int executeUploadFrameParse(Integer i, byte[] sumBytes, FileMessageFrame fileMessageFrame) {
        // 解析帧总长度 2B(第三四字节)
        //getFrameSumLength(sumBytes[i + 3], sumBytes[i + 4], fileMessageFrame);
        // 解析文件名称字节数组长度 2B (第五六字节)
        getFileNameBytesLength(sumBytes[i + 8], sumBytes[i + 9], fileMessageFrame);
        // 解析文件总长度 8B (第六到十三字节)
        byte[] fileLengthBytes = new byte[8];
        int k = 0, current = i + 10;
        while (k < 8) {
            fileLengthBytes[k] = sumBytes[current];
            current = current + 1;
            k++;
        }
        getFileSize(fileLengthBytes, fileMessageFrame);
        return (i + FileMessageFrame.UPLOAD_COMMON);
    }

    /**
     * 下载帧解析
     * @param i  解析起始索引位置
     * @param sumBytes
     * @param fileMessageFrame
     */
    public static int executeDownloadFrameParse(Integer i, byte[] sumBytes, FileMessageFrame fileMessageFrame) {
        getFileNameBytesLength(sumBytes[i + 8], sumBytes[i + 9], fileMessageFrame);
        // 解析文件总长度 8B (第六到十三字节)
        byte[] fileLengthBytes = new byte[8];
        int k = 0, current = i + 10;
        while (k < 8) {
            fileLengthBytes[k] = sumBytes[current];
            current = current + 1;
            k++;
        }
        getFileSize(fileLengthBytes, fileMessageFrame);
        return (i + FileMessageFrame.UPLOAD_COMMON);
    }

    /**
     * 删除帧解析
     * @param sumBytes
     * @param fileMessageFrame
     */
    public static void executeDeleteFrameParse(byte[] sumBytes, FileMessageFrame fileMessageFrame) {
    }

    /**
     * 文件在线传输文件流解析
     * @param i
     * @param sumBytes
     * @param fileMessageFrame
     */
    public static void executeDataTransportFrameParse(Integer i, byte[] sumBytes, FileMessageFrame fileMessageFrame) {
        // 1、包序号
        //fileMessageFrame.setFrameIndex(sumBytes[4]);

        // 2、读取两个字节获取接收端用户名或文件名称长度
        byte[] shortBytes = new byte[2];
        shortBytes[0] = sumBytes[i + 8];
        shortBytes[1] = sumBytes[i + 9];
        Short frameLength = ByteOrderConvert.bytesToShort(shortBytes);
        fileMessageFrame.setFrameSumLength(frameLength);

        int k = i + 10;
        if(frameLength > 0) {
            byte[] receiveBytes = new byte[frameLength];
            for(int j = 0; j < frameLength; j++) {
                receiveBytes[j] = sumBytes[k];
                k++;
            }

            try {
                fileMessageFrame.setData(new String(receiveBytes, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 文件在线传输结束消息解析
     * @param i
     * @param sumBytes
     * @param fileMessageFrame
     */
    public static void executeDataTransportEndFrameParse(Integer i, byte[] sumBytes, FileMessageFrame fileMessageFrame) {

        // 1、读取两个字节表示的数据长度
        byte[] shortBytes = new byte[2];
        shortBytes[0] = sumBytes[i + 8];
        shortBytes[1] = sumBytes[i + 9];
        Short frameLength = ByteOrderConvert.bytesToShort(shortBytes);
        fileMessageFrame.setFrameSumLength(frameLength);

        int k = i + 10;
        if(frameLength > 0) {
            byte[] receiveBytes = new byte[frameLength];
            for(int j = 0; j < frameLength; j++) {
                receiveBytes[j] = sumBytes[k];
                k++;
            }

            try {
                fileMessageFrame.setData(new String(receiveBytes, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 解析帧总长度
     *
     * @param b1
     * @param b2
     * @param fileMessageFrame
     * @return
     * */
    private static void getFrameSumLength(byte b1, byte b2, FileMessageFrame fileMessageFrame) {
        byte[] shortBytes = new byte[2];
        shortBytes[0] = b1;
        shortBytes[1] = b2;
        Short frameLength = ByteOrderConvert.bytesToShort(shortBytes);
        fileMessageFrame.setFrameSumLength(frameLength);
    }

    /**
     * 解析文件名称字节数组长度
     *
     * @param b1
     * @param b2
     * @param fileMessageFrame
     * @return
     * */
    private static void getFileNameBytesLength(byte b1, byte b2, FileMessageFrame fileMessageFrame) {
        byte[] shortBytes = new byte[2];
        shortBytes[0] = b1;
        shortBytes[1] = b2;
        Short frameLength = ByteOrderConvert.bytesToShort(shortBytes);
        fileMessageFrame.setDataLength(frameLength);
    }

    /**
     * 解析文件大小
     * @param fileLengthBytes
     * @param fileMessageFrame
     * @return
     * */
    private static void getFileSize(byte[] fileLengthBytes, FileMessageFrame fileMessageFrame) {
        try {
            fileMessageFrame.setFileLength(ByteOrderConvert.bytesToLong(fileLengthBytes));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
