package com.alibaba.server.common;

import java.io.File;
import java.text.SimpleDateFormat;

/**
 * @Auther: YSFY
 * @Date: 2020/10/3
 * @Pacage_name: com.alibaba.server.common
 * @Project_Name: net-server
 * @Description: 常量类
 */

@SuppressWarnings("all")
public class BasicConstant {
    public static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    public static final String
        SERVER_IP = "SERVER.IP",
        NIO_BIND_IP = "NIO.BIND.IP",
        NETTY_SERVER_PROTOCOL_PORT = "NETTY.SERVER.PROTOCOL.PORT",
        NIO_TEXT_PORT = "NIO.TEXT.PORT",
        NIO_FILE_UPLOAD_PORT = "NIO.FILE.UPLOAD.PORT",
        NIO_FILE_DOWNLOAD_PORT = "NIO.FILE.DOWNLOAD.PORT",
        NIO_FILE_RESUME_UPLOAD_PORT = "NIO.FILE.RESUME.UPLOAD.PORT",
        NIO_FILE_RESUME_DOWNLOAD_PORT = "NIO.FILE.RESUME.DOWNLOAD.PORT",
        NIO_WEBSOCKET_PORT = "NIO.WEBSOCKET.PORT",
        NIO_MEDIA_STREAM_PORT = "NIO.MEDIA.STREAM.PORT",
        MEDIA_STREAM_PUBLIC_HOST = "MEDIA.STREAM.PUBLIC.HOST",
        MEDIA_STREAM_TOKEN_SECRET = "MEDIA.STREAM.TOKEN.SECRET",
        MEDIA_STREAM_TOKEN_EXPIRE_SECONDS = "MEDIA.STREAM.TOKEN.EXPIRE.SECONDS",
        MEDIA_STREAM_BUFFER_SIZE = "MEDIA.STREAM.BUFFER.SIZE",
        MEDIA_STREAM_MAX_THREADS = "MEDIA.STREAM.MAX.THREADS",
        MEDIA_STREAM_MAX_CONNECTIONS_PER_USER = "MEDIA.STREAM.MAX.CONNECTIONS.PER.USER",
        FILE_TRANSFER_TOKEN_SECRET = "FILE.TRANSFER.TOKEN.SECRET",
        FILE_TRANSFER_TOKEN_EXPIRE_SECONDS = "FILE.TRANSFER.TOKEN.EXPIRE.SECONDS",
        SERVER_LOCAL_LOOPBACK = "127.0.0.1",
        CLASS_FILE_END = ".class",

        MESSAGE_SPLIT = "--",
        OK = "[OK]",
        UPLOAD = "UPLOAD",
        DOWNLOAD = "DOWNLOAD",
        SEND_SUCCESS = "SEND.SUCCESS",
        NOT_EXIST = "[NOT-EXIST-FAIL]",
        SEND_FAIL = "SEND.FAIL",
        MAIN_THREAD = "服务端Socket核心线程",
        DISC_FULL_ERROR = "磁盘已满，拒绝上传 [FAIL]",
        READ_TIME_OUT = "READ_TIME_OUT",
        DATA_END = "[DATA_END]",
        DATA_EMPTY = "DATA_EMPTY",
        EXCEPTION = "EXCEPTION",
        NO_EXCEPTION = "NO_EXCEPTION",
        FILE_UPLOAD_TASK = "FILE.UPLOAD.TASK",
        FILE_DOWNLOAD_TASK = "FILE.DOWNLOAD.TASK",
        CHAT_TASK = "CHAT.TASK",
        SOCKET_BUFFER_SIZE = "SOCKET.BUFFER.SIZE",
        FILE_ACCEPTOR_SERVER_HANDLER = "FILE.ACCEPTOR.SERVER.HANDLER",
        FILE_CORE_SERVICE_HANDLER = "FILE.CORE.SERVICE.HANDLER",
        MESSAGE_CHANNEL_SERVER = "MESSAGE.CHANNEL.SERVER";

    public static final String CHAT = "CHAT";
    public static final String WEBSOCKET = "WEBSOCKET";
    public static final String YES = "Y";
    public static final String NO = "N";
    public static final String EMPTY = "";
    public static final String REGISTERED = "Y";
    public static final String LOGIN = "1", LOGOUT = "2", CANCEL = "3", NOT_LOGIN = "-1";

    /**
     * 线程相关
     *
     * */
    public static final String NIO_SERVER_MAIN_CORE_TEXT_SELECTOR = "NIO.SERVER.MAIN.CORE.TEXT.SELECTOR";
    public static final String NIO_SERVER_MAIN_CORE_TEXT_ACCEPTOR = "NIO.SERVER.MAIN.CORE.TEXT.ACCEPTOR";
    public static final String NIO_SERVER_MAIN_CORE_FILE_SELECTOR = "NIO.SERVER.MAIN.CORE.FILE.SELECTOR";
    public static final String NIO_SERVER_MAIN_CORE_FILE_RESUME_SELECTOR = "NIO.SERVER.MAIN.CORE.FILE.RESUME.SELECTOR";
    public static final String NIO_SERVER_MAIN_CORE_FILE_UPLOAD_ACCEPTOR = "NIO.SERVER.MAIN.CORE.FILE.UPLOAD.ACCEPTOR";
    public static final String NIO_SERVER_MAIN_CORE_FILE_RESUME_UPLOAD_ACCEPTOR = "NIO.SERVER.MAIN.CORE.FILE.RESUME.UPLOAD.ACCEPTOR";
    public static final String NIO_SERVER_MAIN_CORE_FILE_DOWNLOAD_ACCEPTOR = "NIO.SERVER.MAIN.CORE.FILE.DOWNLOAD.ACCEPTOR";
    public static final String NIO_SERVER_MAIN_CORE_FILE_RESUME_DOWNLOAD_ACCEPTOR = "NIO.SERVER.MAIN.CORE.FILE.RESUME.DOWNLOAD.ACCEPTOR";
    public static final String NIO_SERVER_MAIN_CORE_WEBSOCKET_SELECTOR = "NIO.SERVER.MAIN.CORE.WEBSOCKET.SELECTOR";
    public static final String NIO_SERVER_MAIN_CORE_WEBSOCKET_ACCEPTOR = "NIO.SERVER.MAIN.CORE.WEBSOCKET.ACCEPTOR";
    public static final String FILE_DOWNLOAD_WAIT_RESULT_THREAD = "FILE.DOWNLOAD.WAIT.RESULT.THREAD";
    public static final String FILE_DOWNLOAD_THREAD = "FILE.DOWNLOAD.THREAD";

    /**
     * socket相关
     *
     * */
    public static final String SOCKET_TIMEOUT = "SOCKET.TIMEOUT";
    public static final String SELECTOR_POLL_TIMEOUT = "SELECTOR.POLL.TIMEOUT";
    public static final String SOCKET_RECEIVE_BUFFER_SIZE = "SOCKET.RECEIVE.BUFFER.SIZE";
    public static final String SOCKET_SEND_BUFFER_SIZE = "SOCKET.SEND.BUFFER.SIZE";
    public static final String SOCKET_RECONNECTED_COUNT = "SOCKET.RECONNECTED.COUNT";

    /**
     * 业务相关
     *
     * */
    public static final String GLOBAL_MAIN_REACTOR = "GLOBAL.MAIN.REACTOR";
    public static final String CHANNEL_PIPE_LINE = "CHANNEL.PIPE.LINE";
    public static final String SEND_MESSAGE_QUEUE = "SEND.MESSAGE.QUEUE";
    public static final String TRANSPORT_PROTOCOL = "TRANSPORT.PROTOCOL";
    public static final String USER = "USER";
    public static final String SOCKET_CHANNEL = "SOCKET_CHANNEL";
    public static final String CHANNEL_PIPELINE = "CHANNEL.PIPELINE";
    public static final String CHAT_MESSAGE_PROTOCOL = "CHAT.MESSAGE.PROTOCOL";
    public static final String FILE_TRANSPORT_PROTOCOL = "FILE.TRANSPORT.PROTOCOL";
    public static final String WEBSOCKET_MESSAGE_PROTOCOL = "WEBSOCKET.MESSAGE.PROTOCOL";
    public static final String NIO_FILE_BASE_PATH_WINDOWS = "NIO.FILE.BASE.PATH.WINDOWS";
    public static final String NIO_FILE_BASE_PATH_LINUX_MAC = "NIO.FILE.BASE.PATH.LINUX.MAC";
    public static final String CLIENT_CLOSE_SOCKET = "CLIENT.CLOSE.SOCKET"; // 关闭socketChannel
    public static final String REGISTER_TYPE_CHAT = "接入客户端聊天服务", REGISTER_TYPE_FILE = "接入客户端文件服务";
    public static final String CHAT_CHANNEL_CONTEXT = "CHAT.CHANNEL.CONTEXT", FILE_CHANNEL_CONTEXT = "FILE.CHANNEL.CONTEXT";
    public static final String CHAT_MESSAGE_FRAME_TYPE = "CHAT.MESSAGE.FRAME.TYPE", FILE_MESSAGE_FRAME_TYPE = "FILE.MESSAGE.FRAME.TYPE", EVENT_TYPE = "EVENT.TYPE";

    /**
     * 文件服务数据返回定义
     * */
    public static final String ONLINE_TRANSPORT_CONFIRM = "ONLINE.TRANSPORT.SUCCESS"; // 文件确认在线传输
    public static final String ONLINE_TRANSPORT_FAIL_NO_TASK = "文件服务器内部处理错误，没有文件发送任务"; // 文件在线传送失败
    public static final String ONLINE_TRANSPORT_SUCCESS = "ONLINE.TRANSPORT.SUCCESS"; // 在线传输成功
    public static final String ONLINE_TRANSPORT_FAIL = "ONLINE.TRANSPORT.FAIL"; // 在线传输失败
    public static final String CONFIRM_ONLINE_TRANSPORT = "CONFIRM.ONLINE.TRANSPORT"; // 同意在线文件传输
    public static final String CANCEL_ONLINE_TRANSPORT = "CANCEL.ONLINE.TRANSPORT"; // 取消在线文件传呼
    public static final String ONLINE_TRANSPORT_NEED_CONFIRM = "ONLINE.TRANSPORT.NEED.CONFIRM"; // 在线传输接收端用户确认
    public static final String UPLOAD_TRANSPORT_CONFIRM = "UPLOAD.TRANSPORT.CONFIRM"; // 上传传输确认
    public static final String UPLOAD_TRANSPORT_CONTINUE = "UPLOAD.TRANSPORT.CONTINUE"; // 上传传输续传
    public static final String UPLOAD_TRANSPORT_CONFIRM_REPEAT = "UPLOAD.TRANSPORT.CONFIRM.REPEAT"; // 重复上传确认帧
    public static final String DOWNLOAD_TRANSPORT_CONFIRM = "DOWNLOAD.TRANSPORT.CONFIRM"; // 下载确认帧
    public static final String DOWNLOAD_TRANSPORT_CONFIRM_NOT_EXIST = "DOWNLOAD.TRANSPORT.CONFIRM.NOT.EXIST"; // 下载不存在确认帧
    public static final String DOWNLOAD_TRANSPORT_STOP = "DOWNLOAD.TRANSPORT.STOP";// 下载终止
    public static final String LAUNCH_USER = "LAUNCH.USER", RECEIVE_USER = "RECEIVE.USER", CLOSE_USER = "CLOSE.USER", CLOSE_FILE_CHANNLE = "CLOSE.FILE.CHANNEL", CLOSE_CHAT_CHANNEL = "CLOSE.CHAT.CHANNEL";
    public static final String ORIGIN_FILE_STREAM_DATA = "ORIGIN.FILE.STREAM.DATA", FILE_TASK = "FILE.TASK", FILE_FRAME = "FILE.FRAME", FILE_STREAM_SEND_END = "FILE.STREAM.SEND.END", FILE_TAG = "tag";
    public static final String FILE_CHANNEL_OPEN_ERROR = "文件通道未打开，上传文件失败", FILE_DOWNLOAD_SUCCESS = "FILE.DOWNLOAD.SUCCESS";
    /**
     * 缓存相关
     *
     * */
    public static final String CACHE = "CACHE";
    public static final String BYTEBUFFER = "BYTE.BUFFER";
    public static final String DOWNLOAD_SEND_BYTE_BUFFER = "DOWNLOAD.SEND.BYTE.BUFFER";
    public static final String SELECTOR_WRITE_EVENT_HANDLER_TIME = "SELECTOR.WRITE.EVENT.HANDLER.TIME";

    public static final String CPU_CORE_COUNT = "CPU.CORE.COUNT";
    public static final String OS_NAME = "os.name";

    public static final String SELECTOR = "SELECTOR";
    public static final String SELECTOR_RUNNABLE = "SELECTOR.RUNNABLE";

    public static final String ACCEPTOR = "ACCEPTOR";
    public static final String ACCEPTOR_RUNNABLE = "ACCEPTOR.RUNNABLE";
    public static final String ACCEPTOR_THREAD = "ACCEPTOR.THREAD";

    public static final String REACTOR = "REACTOR";

    public static final String BYTE_BUFFER = "BYTE.BUFFER";
    public static final String SEND_MESSAGE = "SEND.MESSAGE";
    public static final String WRITE_MAX_WAIT_COUNT = "WRITE.MAX.WAIT.COUNT";
    public static final String THREAD = "THREAD";
    public static final String RUNNABLE = "RUNNABLE";
    public static final String IOC = "IOC";

    public static final String FILE_WINDOWS_SEPARATOR = File.separator;
    public static final String FILE_LINUX_MAC_SEPARATOR = File.separator;


    public static final int OP_ACCEPT = 1 << 4, OP_CONNECT = 1 << 3, OP_WRITE = 1 << 2, OP_READ = 1 << 0;

}
