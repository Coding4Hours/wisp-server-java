import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/wisp")
public class wispcrap {
    
    private static final Map<Integer, Stream> streams = new HashMap<>();
    
    private static final Map<String, Integer> PACKET_TYPES = Map.of(
        "CONNECT", 0x01,
        "DATA", 0x02,
        "CONTINUE", 0x03,
        "CLOSE", 0x04
    );

    private static final Map<String, Integer> CLOSE_REASONS = Map.of(
        "NORMAL", 0x02,
        "NETWORK_ERROR", 0x03,
        "INVALID", 0x41,
        "UNREACHABLE", 0x42,
        "TIMEOUT", 0x43,
        "REFUSED", 0x44
    );

    @OnMessage
    public void onMessage(Session session, ByteBuffer message) {
        byte[] data = message.array();
        if (data.length < 5) return;

        int ptype = data[0];
        int streamId = ByteBuffer.wrap(Arrays.copyOfRange(data, 1, 5)).getInt();
        byte[] payload = Arrays.copyOfRange(data, 5, data.length);

        switch (ptype) {
            case 0x01 -> handleConnect(streamId, payload, session);
            case 0x02 -> handleData(streamId, payload);
            case 0x04 -> handleClose(streamId);
        }
    }

    private void handleConnect(int streamId, byte[] payload, Session session) {
        boolean isUdp = payload[0] == 0x02;
        int port = ByteBuffer.wrap(Arrays.copyOfRange(payload, 1, 3)).getShort();
        String host = new String(Arrays.copyOfRange(payload, 3, payload.length));

        try {
            Socket socket = isUdp ? null : new Socket(host, port);
            DatagramSocket udpSocket = isUdp ? new DatagramSocket() : null;
            InetAddress targetAddr = isUdp ? InetAddress.getByName(host) : null;

            Stream stream = new Stream(streamId, isUdp ? "UDP" : "TCP", socket, udpSocket, targetAddr, port, session);
            streams.put(streamId, stream);

            new Thread(() -> {
                try {
                    if (!isUdp) {
                        InputStream in = socket.getInputStream();
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            sendPacket(PACKET_TYPES.get("DATA"), streamId, Arrays.copyOf(buffer, len), session);
                        }
                    }
                } catch (IOException e) {
                    sendPacket(PACKET_TYPES.get("CLOSE"), streamId, new byte[]{CLOSE_REASONS.get("NETWORK_ERROR")}, session);
                    streams.remove(streamId);
                }
            }).start();
        } catch (IOException e) {
            sendPacket(PACKET_TYPES.get("CLOSE"), streamId, new byte[]{CLOSE_REASONS.get("UNREACHABLE")}, session);
        }
    }

    private void handleData(int streamId, byte[] payload) {
        Stream stream = streams.get(streamId);
        if (stream == null) return;

        try {
            if (stream.type.equals("TCP")) {
                stream.socket.getOutputStream().write(payload);
            } else {
                DatagramPacket packet = new DatagramPacket(payload, payload.length, stream.targetAddr, stream.targetPort);
                stream.udpSocket.send(packet);
            }
        } catch (IOException ignored) {}
    }

    private void handleClose(int streamId) {
        Stream stream = streams.remove(streamId);
        if (stream == null) return;

        try {
            if (stream.socket != null) stream.socket.close();
            if (stream.udpSocket != null) stream.udpSocket.close();
        } catch (IOException ignored) {}
    }

    private void sendPacket(int type, int streamId, byte[] payload, Session session) {
        ByteBuffer buffer = ByteBuffer.allocate(5 + payload.length);
        buffer.put((byte) type);
        buffer.putInt(streamId);
        buffer.put(payload);

        try {
            session.getBasicRemote().sendBinary(buffer);
        } catch (IOException ignored) {}
    }
}

class Stream {
    int id;
    String type;
    Socket socket;
    DatagramSocket udpSocket;
    InetAddress targetAddr;
    int targetPort;
    Session session;

    public Stream(int id, String type, Socket socket, DatagramSocket udpSocket, InetAddress targetAddr, int targetPort, Session session) {
        this.id = id;
        this.type = type;
        this.socket = socket;
        this.udpSocket = udpSocket;
        this.targetAddr = targetAddr;
        this.targetPort = targetPort;
        this.session = session;
    }
}
