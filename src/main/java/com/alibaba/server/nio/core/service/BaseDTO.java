package com.alibaba.server.nio.core.service;

import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.facade.CloneableSupport;
import com.alibaba.server.nio.core.facade.Identity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.Date;

/**
 * 数据传输对象基类
 *
 * @author liubei
 * @date 2020/08/26
 */
@Getter
@Setter
@ToString
public class BaseDTO implements Identity, CloneableSupport {

    /**
     * 主键
     */
    private Long id;

    /**
     * 创建时间
     */
    private Date gmtCreated;

    /**
     * 修改时间
     */
    private Date gmtModified;

    /**
     * 删除时间
     * */
    private Date delTime;

    /**
     * 删除标记
     *
     * INSERT：在构造器中设置为了del = N，请按需设置
     * UPDATE：在构造器中设置为了del = N，请按需设置
     * 表结构约定：无需设置默认值
     *
     * @see YesOrNoEnum
     */
    private String del;

    /**
     * 当前用户所占用的聊天SocketChannel
     * */
    private SocketChannel chatSocketChannel;

    /**
     * 当前用户所占用的文件SocketChannel
     * */
    private SocketChannel fileSocketChannel;
}
