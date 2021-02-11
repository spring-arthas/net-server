package com.alibaba.server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;

/**
 * @Auther: YSFY
 * @Date: 2019/11/30 19:28
 * @Pacage_name: com.dh.spring.common.net.bio.server
 * @Project_Name: spring-boot-project
 * @Description: 公共方法
 */
public class BasicUtil {
    private static final Logger logger = LoggerFactory.getLogger(BasicUtil.class);
    private static ByteBuffer buffer = ByteBuffer.allocate(8);

    //TODO 提供基于两个long型的数据计算百分比
    public static String getPercent(long l1, long l2) {
        DecimalFormat df = new DecimalFormat("0.0000%");//0%、0.000%
        return df.format(((Number) l1).intValue() / ((Number) l2).doubleValue());
    }

    //TODO long 转 byte[]
    public static byte[] longToBytes(long x) {
        buffer.putLong(0, x);
        return buffer.array();
    }

    //TODO 1、byte[] 转 long
    public static long bytesToLong(byte[] bytes) {
        buffer.put(bytes, 0, bytes.length);
        buffer.flip();//need flip
        return buffer.getLong();
    }

    /**
     * byte[] 转 long
     * @param bs
     * @return
     * @throws Exception
     */
    public static long bytes2long(byte[] bs)  throws Exception {
        int bytes = bs.length;
        if(bytes > 1) {
            if((bytes % 2) != 0 || bytes > 8) {
                throw new Exception("not support");
            }
        }
        switch(bytes) {
            case 0:
                return 0;
            case 1:
                return (long)((bs[0] & 0xff));
            case 2:
                return (long)((bs[0] & 0xff) <<8 | (bs[1] & 0xff));
            case 4:
                return (long)((bs[0] & 0xffL) <<24 | (bs[1] & 0xffL) << 16 | (bs[2] & 0xffL) <<8 | (bs[3] & 0xffL));
            case 8:
                return (long)((bs[0] & 0xffL) <<56 | (bs[1] & 0xffL) << 48 | (bs[2] & 0xffL) <<40 | (bs[3] & 0xffL)<<32 |
                        (bs[4] & 0xffL) <<24 | (bs[5] & 0xffL) << 16 | (bs[6] & 0xffL) <<8 | (bs[7] & 0xffL));
            default:
                throw new Exception("not support");
        }
        //return 0;
    }

    public static void main(String[] args) {
        File file = new File("D:\\arthas\\server\\download\\1.rar");
        byte[] bytes = longToBytes(file.length());
        System.out.println(longToBytes(file.length()));
    }

    //TODO int 转 byte[]
    public static byte[] intToBytes(int value) {
        byte[] result = new byte[4];
        // 由高位到低位
        result[0] = (byte) ((value >> 24) & 0xFF);
        result[1] = (byte) ((value >> 16) & 0xFF);
        result[2] = (byte) ((value >> 8) & 0xFF);
        result[3] = (byte) (value & 0xFF);
        return result;
    }

    //TODO byte[] 转 int
    public static int byteArrayToInt(byte[] bytes) {
        int value = 0;
        // 由高位到低位
        for (int i = 0; i < 4; i++) {
            int shift = (4 - 1 - i) * 8;
            value += (bytes[i] & 0x000000FF) << shift;// 往高位游
        }
        return value;
    }


    /**
     * byte[]转short
     * @param b
     * @return
     */
    public static short byte2short(byte[] b){
       short l = 0;
        for (int i = 0; i < 2; i++) {
            l<<=8;
            l |= (b[i] & 0xff);
        }
        return l;
    }

    /**
     * short转byte[]
     * @param s
     * @return
     */
    public static byte[] unsignedShortToByte2(int s) {
        byte[] targets = new byte[2];
        targets[0] = (byte) (s >> 8 & 0xFF);
        targets[1] = (byte) (s & 0xFF);
        return targets;
    }
}
