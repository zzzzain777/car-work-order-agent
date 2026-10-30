package com.zz.agent.policy;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

/** 仅供本机开发数据库使用，避免系统级 SOCKS 代理截走回环地址连接。 */
public final class LocalPostgresSocketFactory extends SocketFactory {

    @Override
    public Socket createSocket() {
        return new LoopbackSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localHost, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localHost, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    private static final class LoopbackSocket extends Socket {
        private LoopbackSocket() {
            super(Proxy.NO_PROXY);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress address)) {
                throw new IOException("本地数据库只接受 TCP 地址");
            }
            String host = address.getHostString();
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
                throw new IOException("本地数据库只允许连接回环地址");
            }
            super.connect(new InetSocketAddress("127.0.0.1", address.getPort()), timeout);
        }
    }
}
