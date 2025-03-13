package bgu.spl.net.srv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionsImpl<T> implements Connections<T>{

    protected ConcurrentHashMap<Integer,ConnectionHandler<T>> clients;

    protected static AtomicInteger counter;
    private static volatile ConnectionsImpl instance;
    private ConnectionsImpl() {

        clients= new ConcurrentHashMap<>();
        this.counter = new AtomicInteger(0);
    }
    public static synchronized ConnectionsImpl getInstance()
    {
        if (instance == null)
            instance = new ConnectionsImpl();
        return instance;
    }

    @Override
    public void connect(int connectionId, ConnectionHandler<T> handler) {
        //userNamesClients.putIfAbsent(((BlockingConnectionHandler)handler).getProtocol().getUserName(),connectionId);
        ((BlockingConnectionHandler)handler).setLoggedIn(true);
    }

    @Override
    public boolean send(int connectionId, T msg) {
        try{
            clients.get((Integer)connectionId).send(msg);
        }catch (Exception ignored){
            return false;
        }
        return true;
    }

    @Override
    public void disconnect(int connectionId) {
        BlockingConnectionHandler<T> handler = (BlockingConnectionHandler<T>)(clients.get((Integer)connectionId));
        clients.remove((Integer)connectionId);
        try{
            handler.close();
        }catch (IOException e){
        }
    }

    public ConcurrentHashMap<Integer,ConnectionHandler<T>> getClients(){
        return clients;
    }


    public AtomicInteger getCounter(){
        return counter;
    }
}
