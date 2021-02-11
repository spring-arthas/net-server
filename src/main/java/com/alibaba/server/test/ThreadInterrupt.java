package com.alibaba.server.test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.concurrent.TimeUnit;

import lombok.SneakyThrows;

/**
 * 线程中断演示
 * @author spring
 */
public class ThreadInterrupt {

    public static void main(String[] args) throws InterruptedException, IOException {
        //testThreadInterrupt();

        testServerSocketChannel();
    }

    private static void testServerSocketChannel() throws IOException, InterruptedException {
        ServerServiceChannel.serverServiceChannel();

        System.out.println("主线程 [ " + Thread.currentThread().getName() + " ] 启动, 主线程将在5s后执行中断......");

        Thread thread = new Thread(new SocketChannelBlockMethodRunnable());
        thread.start();

        TimeUnit.SECONDS.sleep(5);
        // 执行的是thread线程的中断
        thread.interrupt();

        // 执行的是主线程的中断
        //Thread.interrupted();

        thread.join();
    }

    private static class SocketChannelBlockMethodRunnable implements Runnable {

        @SneakyThrows
        @Override
        public void run() {
            // 当前SocketChannel执行读取并发送
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.connect(new InetSocketAddress(InetAddress.getByName("30.7.80.26"), 10086));
            while (!socketChannel.finishConnect()) {
                if(socketChannel.isConnected()) {
                    break;
                }
            }

            int index = 0;
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            while (!Thread.currentThread().isInterrupted()) {
                String message = "这是发向服务端的第 [ " + index + " ] 次数据";
                byteBuffer.put(message.getBytes(Charset.forName("utf-8")));
                byteBuffer.flip();
                socketChannel.write(byteBuffer);
                byteBuffer.clear();
                //TimeUnit.SECONDS.sleep(1);
            }
        }
    }

    private static void testThreadInterrupt() throws InterruptedException {
        System.out.println("主线程 [ " + Thread.currentThread().getName() + " ] 启动, 主线程将在5s后执行中断......");

        Thread thread = new Thread(new CanInterruptMethodRunnable());
        thread.start();

        TimeUnit.SECONDS.sleep(5);
        // 执行的是thread线程的中断
        thread.interrupt();

        // 执行的是主线程的中断
        //Thread.interrupted();

        Thread.currentThread().join();
    }

    private static class FileChannelBlockMethodRunnable implements Runnable {

        @SneakyThrows
        @Override
        public void run() {
            // 读取通道
            File readFile = new File("D:\\nio\\file\\\\spring\\我的资源\\测试文件夹\\归档max.zip");
            FileChannel readFileChannel = FileChannel.open(readFile.toPath(), StandardOpenOption.READ);

            // 写入通道
            File writeFile = new File("D:\\nio\\file\\\\spring\\我的资源\\归档max.zip");
            writeFile.createNewFile();
            FileChannel writeFileChannel = FileChannel.open(writeFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            // 循环中含有IO操作时的阻塞
            int index = 0, readBytes = 0;
            long sumBytes = 0L;
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            while (!Thread.currentThread().isInterrupted()) {
                sumBytes += readBytes = readFileChannel.read(byteBuffer);
                if(readBytes == -1) {
                    return;
                }

                byteBuffer.flip();
                if(byteBuffer.hasRemaining()) {
                    writeFileChannel.write(byteBuffer);
                    byteBuffer.clear();

                    System.out.println(
                        "子线程 [ " + Thread.currentThread().getName() + "] 完成第 [" + (++index) + " ] 次任务写入执行, 写入总字节数 [" + sumBytes + "], 中断状态: status = [ "
                            + Thread.currentThread().isInterrupted() + " ]");
                }
             }

            System.out.println(
                "子线程 [ " + Thread.currentThread().getName() + "] 在第 [" + (++index) + " ] 次任务写入执行过程发生中断, 将跳出循环, 中断状态: status = [ "
                    + Thread.currentThread().isInterrupted() + " ]");
        }
    }

    private static class CanInterruptMethodRunnable implements Runnable {
        @SneakyThrows
        @Override
        public void run() {
            int index = 0;
            while (!Thread.interrupted()) { //Thread.currentThread().isInterrupted()
                try {
                    // 子线程中含有可被中断的方法
                    TimeUnit.SECONDS.sleep(1);
                    System.out.println(
                        "子线程 [ " + Thread.currentThread().getName() + "] 完成第 [" + (++index) + " ] 次任务执行, 中断状态: status = [ "
                            + Thread.currentThread().isInterrupted() + " ]");
                } catch (InterruptedException e) {
                    System.out.println(
                        "子线程 [ " + Thread.currentThread().getName() + "] 在第 [" + (++index) + " ] 次任务执行过程发生中断, 将跳出循环, 中断状态: status = [ "
                            + Thread.currentThread().isInterrupted() + " ]");
                    break;
                }
            }
        }
    }
}
