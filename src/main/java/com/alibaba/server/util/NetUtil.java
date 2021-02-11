package com.alibaba.server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class NetUtil {

    private static Logger logger = LoggerFactory.getLogger(NetUtil.class);

    private static List<String> ipList = new ArrayList<>();
    private static String REGEX = "^(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|[1-9])\\." +
        "(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|\\d)\\." +
        "(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|\\d)\\." +
        "(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|\\d)$";

    public static HostnameVerifier hv = new HostnameVerifier() {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    };

    /**
     * 连接远端地址
     * */
    private static String connectionAddress(String remoteAddr, int port) {
        boolean flag = false;

        /*取出地址前五位*/
        String tempUrl = remoteAddr.substring(0, 5);
        if(tempUrl.contains("http")) {
            if(tempUrl.contains("https")) {

                try {
                    trustAllHttpsCertificates();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                HttpsURLConnection.setDefaultHostnameVerifier(hv);
            }

            flag = isConnServerByHttp(remoteAddr);
        } else {
            //flag = isReachable(remoteAddr);
            String localIp = encapsulateIp(remoteAddr, port);
            logger.debug(!"".equals(localIp)?("===========> [success] use " + localIp + " connect to " + remoteAddr + " success, local ip is " + localIp):
                ("===========> [failure] no any local ip can not connect " + remoteAddr + ":" + String.valueOf(port)));
            return !"".equals(localIp)?"success":"false";
        }

        if(flag) {
            return "success";
        }

        return "false";
    }

    /**
     * 判断ip是否能连接成功,通过远端机器的ip构造InetAddress对象
     * 通过isReachable方法测试调用机器和远端主机的连通性
     *
     * 注意事项：此方法可能会在本地和远端网络ip可以ping通的情况下返回false
     *  解决方案：启动程序的用户必须为root用户而非普通用户，而isReachable
     *      的实现采取ICMP实现，必须提供root权限
     * */
    private static boolean isReachable(String remoteAddr) {
        boolean reachAble = true;

        try {
            InetAddress address = InetAddress.getByName(remoteAddr);

            /*判断远端机器的ip版本协议*/
            if(address instanceof Inet4Address) {
                logger.debug("===========> remote ip version is IPV4");
            }

            if(address instanceof Inet6Address) {
                logger.debug("===========> remote ip version is IPV4");
            }

            reachAble = address.isReachable(2000);
            logger.debug(reachAble == true?("===========> success - ping " + remoteAddr + " with no interface specific "):
                ("===========> failure - ping " + remoteAddr + " is unrecognized"));

            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while(networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                logger.debug("===========> checking interface, displayName:" + networkInterface.getDisplayName() + ", name: " +
                    networkInterface.getName());

                boolean result = address.isReachable(networkInterface, 0, 3000);
                logger.debug(result == true?("===========> success - ping " + remoteAddr):("===========> failure - ping " + remoteAddr));
            }

        } catch (UnknownHostException e) {
            logger.debug("===========> [UnknownHost exception]: " + e.getMessage());
        } catch (IOException e) {
            logger.debug("===========> [IO exception]: " + e.getMessage());
        }

        return reachAble;
    }

    /**
     * 判断服务器是否开启,获取服务器的响应
     * */
    private static boolean isConnServerByHttp(String remoteAddr) {
        boolean connlng = false;

        URL url = null;
        HttpURLConnection httpURLConnection = null;

        try {
            /*封装网络连接地址URL*/
            url = new URL(remoteAddr);

            /*根据此地址连接字符串封装对象尝试与服务器进行连接*/
            httpURLConnection = (HttpURLConnection) url.openConnection();
            httpURLConnection.setConnectTimeout(3000);

            /*得到连接服务器服务器返回的状态码*/
            if(httpURLConnection.getResponseCode() == 200) {
                connlng = true;
                logger.debug("===========> [connect result message]: " + httpURLConnection.getResponseMessage());
            } else {
                logger.debug("===========> [connect result message]: " + httpURLConnection.getResponseMessage());
            }
        } catch (MalformedURLException e) {
            logger.debug("===========> [IP URL result exception]: " + e.getMessage());
        } catch (IOException e) {
            logger.debug("===========> [connect result message]: " + e.getMessage());
        } finally {
            httpURLConnection.disconnect();
            logger.debug("===========> close connection");
        }

        return connlng;
    }

    /**
     * https适用
     * */
    private static void trustAllHttpsCertificates() throws Exception {
        TrustManager[] trustManagers = new TrustManager[1];
        TrustManager trustManager = new Tm();
        trustManagers[0] = trustManager;

        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustManagers, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
    }

    static class Tm implements TrustManager, X509TrustManager {

        public boolean isClientTrusted(X509Certificate certificate) {
            return true;
        }

        public boolean isServerTrusted(X509Certificate[] certificates) {
            return true;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            return;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            return;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            //return new X509Certificate[0];
            return null;
        }
    }

    /**
     * 获取本地可用的网络ip，此ip是能够连接远端网络ip的ip
     *
     * @Param remoteIp 远程主机ip
     *
     * @Param port
     *
     * @Return ip 返回本地已经完成与远端ip连接的本地可用ip
     */
    private static String encapsulateIp(String remoteIp, int port) {
        String localIp = "";
        if(checkIpIllegal(remoteIp)) {
            logger.debug("===========> [success] finish ip verification of legitimacy");

            try {
                InetAddress remoteInetAddress = InetAddress.getByName(remoteIp);

                Enumeration<NetworkInterface> netWorkInterfaces = NetworkInterface.getNetworkInterfaces();
                while (netWorkInterfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = netWorkInterfaces.nextElement();

                    Enumeration<InetAddress> iNetAddress = networkInterface.getInetAddresses();
                    while (iNetAddress.hasMoreElements()) {
                        InetAddress localInetAddress = iNetAddress.nextElement();
                        if(isReachable(localInetAddress, remoteInetAddress, port, 3000, networkInterface, localInetAddress)) {
                            localIp = localInetAddress.getHostAddress();
                            break;
                        }
                    }

                    if(!"".equals(localIp)) {
                        break;
                    }
                }

            } catch (UnknownHostException e) {
                logger.debug("===========> [failure] unKnowHost exception: " + e.getMessage());
            } catch (SocketException e) {
                logger.debug("===========> [failure] listing all the local network address occurred bio exception: " + e.getMessage());
            }
        } else {
            logger.debug("===========> [failure] illegal ip address");
        }

        return localIp;
    }

    /**
     * Socket可用于实现本地可用的ip地址与远端地址进行连接成功与否判断
     * */
    private static boolean isReachable(InetAddress localInetAddress, InetAddress remoteInetAddress, int port, int timeOut,
       NetworkInterface networkInterface, InetAddress tempAddress) {
        boolean isReachable = false;
        Socket socket = new Socket();

        /*端口默认为0，表示本地可用的ip地址，此地址配上一个随机可用的端口号用于与远端服务进行连接判断*/
        SocketAddress localSocketAddress = new InetSocketAddress(localInetAddress, 0);
        try {
            /*绑定本地可用地址InetSocketAddress对象*/
            socket.bind(localSocketAddress);

            /*配置远端地址InetSocketAddress对象*/
            InetSocketAddress remoteSocketAddress = new InetSocketAddress(remoteInetAddress, port);

            socket.connect(remoteSocketAddress);

            /**
             *  尝试发送心跳包用于socket连接状态判断
             *      如果在remoteInetSocketAddress表示的远端服务地址对象出现了socket关闭，此时客户端触发sendUrgentData
             *      方法模拟发送心跳包，如果抛出Connection reset by peer则表示与服务端的连接已经断开
             *  */
            socket.sendUrgentData(1000);

            logger.debug("===========> [checking] checking interface, [displayName]:" + networkInterface.getDisplayName() + ", [name]: " +
                networkInterface.getName() + ", [connection_ip]: " + tempAddress.getHostAddress());

            isReachable = true;
        } catch (IOException e) {
            /*if(e.getMessage().contains("unreachable")) {
                logger.debug("===========> [failure] connection refused exception");
            }

            if(e.getMessage().contains("Connection refused")) {
                logger.debug("===========> [failure] connection refused exception");
            }*/

            if(e.getMessage().contains("Connection reset by peer")) {
                logger.debug("===========> [connection refused] bio has interrupted with server " + remoteInetAddress.getHostAddress());
            }

        }

        return isReachable;
    }

    /**
     * 判断ip的合法性
     * */
    private static boolean checkIpIllegal(String ip) {
        if(null != ip && !"".equals(ip)) {
            return ip.matches(REGEX);
        }

        return false;
    }

    private static void workInterface() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            int index = 0;
            while(networkInterfaces.hasMoreElements()) {
                index++;

                System.out.println("**********************************************************************************");
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                System.out.println("网络接口信息为:");

                /*获取此网络接口的显示名称*/
                System.out.println("displayName: " + networkInterface.getDisplayName());

                /*获取此网络接口的名称    */
                System.out.println("name: " + networkInterface.getName());

                /**/
                System.out.println("index: " + networkInterface.getIndex());

                /*获取此网络接口的全部或是部分InterfaceAddress所组成的列表*/
                System.out.println("interfaceAddress: " + networkInterface.getInterfaceAddresses().toString());

                /*如果此接口是子接口，则返回它的父NetWorkInterface,如果它是物理接口则返回null*/
                System.out.println("parent: " + networkInterface.getParent());

                /*获取具有连接到此网络接口下的子接口*/
                System.out.println("subInterface: " + networkInterface.getSubInterfaces().toString());

                /*获取mac地址*/
                System.out.println("mac address: " + networkInterface.getHardwareAddress());

                Enumeration<InetAddress> iNetAddress = networkInterface.getInetAddresses();
                System.out.println();
                System.out.println("当前网络接口的地址信息为：");
                while(iNetAddress.hasMoreElements()) {
                    InetAddress inetAddress = iNetAddress.nextElement();
                    System.out.println("    主机地址为：" + inetAddress.getHostAddress());
                    System.out.println("    主机名称为：" + inetAddress.getHostName());
                    System.out.println("    canonicalHostName: " + inetAddress.getCanonicalHostName());
                }

                //break;
            }

            System.out.println();
            System.out.println("当前本机的网络接口总数量为：" + index);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public static List<String> getIpList() throws SocketException {
        //String privateNetIp = null;// 本地IP，如果没有配置外网IP则返回它
        //String publicNetIp = null;// 外网IP
        Enumeration<NetworkInterface> netInterfaces = NetworkInterface.getNetworkInterfaces();
        InetAddress inetAddress = null;
        boolean isExistPublicNet = false;// 是否找到外网IP
        while (netInterfaces.hasMoreElements() && !isExistPublicNet) {
            Enumeration<InetAddress> inetAddressEnumeration = netInterfaces.nextElement().getInetAddresses();
            while (inetAddressEnumeration.hasMoreElements()) {
                inetAddress = inetAddressEnumeration.nextElement();

                //TODO 过滤掉IPv6地址
                if(inetAddress.getAddress().length == 4) {
                    ipList.add(inetAddress.getHostAddress());
                }

                //TODO 如果外网地址存在只获取外网地址
                /*if (!inetAddress.isSiteLocalAddress() && !inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().indexOf(":") == -1) {// 外网IP
                    publicNetIp = inetAddress.getHostAddress();
                    isExistPublicNet = true;
                    ipList.add(publicNetIp);
                    //break;
                } else if (inetAddress.isSiteLocalAddress() && !inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().indexOf(":") == -1) {// 内网IP
                    privateNetIp = inetAddress.getHostAddress();
                    ipList.add(privateNetIp);
                }*/
            }
        }
        return ipList;
        /*if (publicNetIp != null && !"".equals(publicNetIp)) {
            return publicNetIp;
        } else {
            return privateNetIp;
        }*/
    }


    public static void main(String[] args) {
        //System.out.println(NetUtil.connectionAddress("http://localhost:8080/IFCS"));
        //System.out.println(NetUtil.connectionAddress("10.35.76.48"));
        //NetUtil.connectionAddress("10.35.76.8", 9000);
        //workInterface();
        /*try {
            NetUtil.getIp();
        } catch (SocketException e) {
            e.printStackTrace();
        }*/

        /*try {
            try {
                NetUtil.getIp();
            } catch (SocketException e) {
                e.printStackTrace();
            }
            System.out.println(InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }*/
    }
}
