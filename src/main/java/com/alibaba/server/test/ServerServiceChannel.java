package com.alibaba.server.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * @author spring
 */
public class ServerServiceChannel {
    private static ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
    public static void main(String[] args) throws IOException, InterruptedException {
        ServerServiceChannel.serverServiceChannel();
    }

    public static void serverServiceChannel() throws IOException, InterruptedException {
        Selector selector = Selector.open();
        if(!selector.isOpen()) {
            System.out.println("selector is not open");
        }

        new Thread(new SelectorRunnable(selector)).start();

        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(Boolean.FALSE);
        serverSocketChannel.bind(new InetSocketAddress(InetAddress.getByName("30.7.80.26"),10086));
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        //Thread.currentThread().join();
    }

    private static class SelectorRunnable implements Runnable {
        private Selector selector;

        public SelectorRunnable(Selector selector) {
            this.selector = selector;
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int keysCount = selector.select(2000);
                    if(keysCount == 0) {
                        TimeUnit.SECONDS.sleep(1);
                        continue;
                    }

                    Iterator<SelectionKey> iterators = selector.selectedKeys().iterator();
                    while (iterators.hasNext()) {
                        SelectionKey selectionKey = iterators.next();

                        if(selectionKey.isValid() && selectionKey.isAcceptable()) {
                            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();
                            SocketChannel socketChannel = serverSocketChannel.accept();
                            if(null != socketChannel) {
                                socketChannel.configureBlocking(Boolean.FALSE);
                                socketChannel.register(this.selector, SelectionKey.OP_READ);
                            }
                        }

                        if(selectionKey.isValid() && selectionKey.isReadable()) {
                            this.readEventHandler(selectionKey);
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        private void readEventHandler(SelectionKey selectionKey) throws IOException {

            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();

            int readBytesCount = 0;
            while ((readBytesCount = socketChannel.read(byteBuffer)) > 0) {
                byteBuffer.flip();
                if(byteBuffer.hasRemaining()) {
                    byte[] readBytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(readBytes);
                    System.out.println("接收到数据: " + new String(readBytes, Charset.forName("utf-8")));
                }
                byteBuffer.clear();
            }

            if(readBytesCount == -1) {
                System.out.println("客户端关闭了socket连接, 执行了socket.close()");
                return;
            }
        }

    }
}
