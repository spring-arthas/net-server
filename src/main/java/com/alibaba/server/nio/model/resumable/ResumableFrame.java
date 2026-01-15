package com.alibaba.server.nio.model.resumable;

import com.alibaba.server.util.ByteOrderConvert;
import java.nio.charset.StandardCharsets;

/**
 * 断点续传协议帧定义
 */
public class ResumableFrame {

    public static final byte[] MAGIC = new byte[]{(byte) 0xFA, (byte) 0xCE};
    public static final int HEADER_LENGTH = 8;

    // 帧类型定义
    public static final byte TYPE_UPLOAD_CHECK = 0x30;
    public static final byte TYPE_UPLOAD_DATA = 0x31;
    public static final byte TYPE_UPLOAD_ACK = 0x32;
    public static final byte TYPE_UPLOAD_END = 0x33;

    public static final byte TYPE_DOWNLOAD_CHECK = 0x34;
    public static final byte TYPE_DOWNLOAD_DATA = 0x35;
    public static final byte TYPE_DOWNLOAD_ACK = 0x36;
    public static final byte TYPE_DOWNLOAD_END = 0x37;

    private byte type;
    private byte flags;
    private int length;
    private byte[] data;

    public ResumableFrame() {}

    public ResumableFrame(byte type, byte[] data) {
        this.type = type;
        this.data = data;
        this.length = (data == null) ? 0 : data.length;
    }

    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    public byte getFlags() {
        return flags;
    }

    public void setFlags(byte flags) {
        this.flags = flags;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getDataAsString() {
        if (data == null) return null;
        return new String(data, StandardCharsets.UTF_8);
    }
}
