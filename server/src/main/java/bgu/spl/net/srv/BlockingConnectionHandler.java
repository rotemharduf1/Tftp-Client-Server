package bgu.spl.net.srv;

import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.impl.tftp.TftpEncoderDecoder;
import bgu.spl.net.impl.tftp.TftpProtocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;

public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final BidiMessagingProtocol<T> protocol;
    private final MessageEncoderDecoder<T> encdec;
    private final Socket sock;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile boolean connected = true;
    private volatile boolean isLoggedIn;



    public BlockingConnectionHandler(Socket sock, MessageEncoderDecoder<T> reader, BidiMessagingProtocol<T> protocol) {
        this.sock = sock;
        this.encdec = reader;
        this.protocol = protocol;


        this.isLoggedIn = false;
    }

    @Override
    public void run() {
        ConnectionsImpl instance = ConnectionsImpl.getInstance();
        protocol.start(ConnectionsImpl.counter.get(),instance);
        instance.getClients().putIfAbsent(instance.getCounter().get(),this);
        ConnectionsImpl.counter.set(ConnectionsImpl.counter.get()+1);
        try (Socket sock = this.sock) {
            int read;

            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());

            while (!protocol.shouldTerminate() && connected && (read = in.read()) >= 0) {
                T nextMessage = encdec.decodeNextByte((byte) read);
                if (nextMessage != null) {
                    protocol.process(nextMessage);
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    @Override
    public void close() throws IOException {
        isLoggedIn = false;
        connected = false;
        sock.close();
    }

    @Override
    public synchronized void send(T msg) {
        try {
            out.write(encdec.encode(msg));
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public TftpProtocol getProtocol(){
        return (TftpProtocol)protocol;
    }

    public TftpEncoderDecoder getEncDec(){
        return (TftpEncoderDecoder)encdec;
    }

    public void setLoggedIn(boolean connect){
        isLoggedIn=connect;
    }

    public boolean getIsLoggedIn(){
        return isLoggedIn;
    }
}


