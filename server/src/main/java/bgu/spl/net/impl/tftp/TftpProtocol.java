package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.srv.BlockingConnectionHandler;
import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.srv.ConnectionsImpl;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.SQLOutput;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Dictionary;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.logging.Handler;

public class TftpProtocol implements BidiMessagingProtocol<byte[]> {

    private boolean shouldTerminate;
    private int connectionId = -1;
    private Connections<byte[]> connections;

    private final String directory = System.getProperty("user.dir")+"/Flies";

    private byte[] userName;

    private int BlockNumber;

    private byte[] DataMsg;

    private String createdFileName;

    private int dataSize;

    public enum ERROR {
        NOT_DEFINED, FILE_NOT_FOUND, ACCESS_VIOLATION, DISC_FULL, UNKNOWN_OPCODE, FILE_EXISTS, NOT_CONNECTED, ALREADY_CONNECTED;
    }

    @Override
    public void start(int connectionId, Connections<byte[]> connections) {
        // TODO implement this
        this.shouldTerminate = false;
        this.connectionId = connectionId;
        this.connections = (ConnectionsImpl<byte[]>) connections;
        this.userName = new byte[0];
        this.BlockNumber = 0;
        this.DataMsg = null;
        this.createdFileName = null;
        this.dataSize=0;

    }

    //we need to check how to synchronize the files - when one read other can write etc.
    @Override
    public void process(byte[] message) {
        // TODO implement this
        TftpEncoderDecoder.Opcode opcode = TftpEncoderDecoder.peekOpcode(message);
        ConnectionHandler<byte[]> handler = (ConnectionHandler<byte[]>) (((ConnectionsImpl) connections).getClients().get((Integer) connectionId));
        byte[] msgOut = null;
        if (opcode == TftpEncoderDecoder.Opcode.None) {
            msgOut = Get_ERROR(ERROR.UNKNOWN_OPCODE);
            handler.send(msgOut);
        } else {
            switch (opcode) {
                case LOGRQ:
                    msgOut = LOGRQ(handler, message);
                    handler.send(msgOut);
                    break;
                case DISC:
                    if (!(((BlockingConnectionHandler<byte[]>) handler).getIsLoggedIn())) {
                        DISC();
                        break;
                    }
                    else{
                        msgOut = Get_ACK(BlockNumber);
                        handler.send(msgOut);
                        DISC();
                        break;
                    }
                case RRQ:
                    msgOut = RRQ(message,handler);
                    handler.send(msgOut);
                    break;
                case WRQ:
                    msgOut = WRQ(message,handler);
                    handler.send(msgOut);
                    break;
                case DATA:
                    uploadData(message,handler);
                    break;
                case DELRQ:
                    msgOut = DELRQ(message,handler);
                    if(msgOut!=null){
                        handler.send(msgOut);
                    }
                    break;
                case DIRQ:
                    msgOut = DIRQ(handler);
                    handler.send(msgOut);
                    break;
                case ACK:
                    msgOut = createDataPacket(DataMsg);
                    if (msgOut != null) {
                        handler.send(msgOut);
                    }
                    break;
                default:
                    msgOut = Get_ERROR(ERROR.UNKNOWN_OPCODE);
                    handler.send(msgOut);
                    break;
            }
        }
    }

    private byte[] LOGRQ(ConnectionHandler<byte[]> handler, byte[] message) {
        if (((BlockingConnectionHandler<byte[]>) handler).getIsLoggedIn()) {
            return Get_ERROR(ERROR.ALREADY_CONNECTED);
        } else {
            byte[] name = new byte[message.length - 2];
            for (int i = 0; i < name.length; i++) {
                name[i] = message[i+2];
            }
            for(ConnectionHandler<byte[]> Handler: ((ConnectionsImpl<byte[]>) connections).getClients().values()){
                if(((BlockingConnectionHandler<byte[]>)Handler).getProtocol().userName==name){
                    return Get_ERROR(ERROR.ALREADY_CONNECTED);
                }
            }
            connections.connect(connectionId, handler);
            return Get_ACK(BlockNumber);

        }
    }

    private byte[] Get_ERROR(ERROR error) {
        String errMsg = "";
        int ErrorCode = 0;
        switch (error) {
            case NOT_DEFINED:
                errMsg = "Not defined, see error message (if any).";
                ErrorCode = ERROR.NOT_DEFINED.ordinal();
                break;
            case FILE_NOT_FOUND:
                errMsg = "File not found - RRQ DELRQ of non-existing file.";
                ErrorCode = ERROR.FILE_NOT_FOUND.ordinal();
                break;
            case ACCESS_VIOLATION:
                errMsg = "Access violation - File cannot be written, read or deleted.";
                ErrorCode = ERROR.ACCESS_VIOLATION.ordinal();
                break;
            case DISC_FULL:
                errMsg = "Disk full or allocation exceeded - No room in disk.";
                ErrorCode = ERROR.DISC_FULL.ordinal();
                break;
            case UNKNOWN_OPCODE:
                errMsg = "Illegal TFTP operation - Unknown Opcode.";
                ErrorCode = ERROR.UNKNOWN_OPCODE.ordinal();
                break;
            case FILE_EXISTS:
                errMsg = "File already exists - File name exists on WRQ.";
                ErrorCode = ERROR.FILE_EXISTS.ordinal();
                break;
            case NOT_CONNECTED:
                errMsg = "User not logged in - Any opcode received before Login completes.";
                ErrorCode = (ERROR.NOT_CONNECTED).ordinal();
                break;
            case ALREADY_CONNECTED:
                errMsg = "User already logged in - Login username already connected.";
                ErrorCode = ERROR.ALREADY_CONNECTED.ordinal();
                break;
        }
        byte[] byteMsg = errMsg.getBytes(StandardCharsets.UTF_8);
        byte[] byteAns = new byte[4 + byteMsg.length];
        int opcode = TftpEncoderDecoder.Opcode.ERROR.ordinal();
        byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
        byteAns[0] = opcodeByte[0];
        byteAns[1] = opcodeByte[1];
        byte[] ErrorCodeByte = new byte[]{(byte) ((ErrorCode >> 8) & 0xFF), (byte) (ErrorCode & 0xFF)};
        byteAns[2] = ErrorCodeByte[0];
        byteAns[3] = ErrorCodeByte[1];
        System.arraycopy(byteMsg,0,byteAns,4,byteMsg.length);
        return byteAns;
    }

    private byte[] Get_ACK(int blockNumber) {
        byte[] byteAns = new byte[4];
        int opcode = TftpEncoderDecoder.Opcode.ACK.ordinal();
        byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
        byteAns[0] = opcodeByte[0];
        byteAns[1] = opcodeByte[1];
        byte[] blockNumByte = new byte[]{(byte) ((blockNumber >> 8) & 0xFF), (byte) (blockNumber & 0xFF)};
        byteAns[2] = blockNumByte[0];
        byteAns[3] = blockNumByte[1];
        return byteAns;
    }

    private void BCAST(Boolean isDeleted, byte[] message) {
        byte[] sendMsg = new byte[3 + message.length];
        int opcode = TftpEncoderDecoder.Opcode.BCAST.ordinal();
        byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
        sendMsg[0] = opcodeByte[0];
        sendMsg[1] = opcodeByte[1];
        if (isDeleted) {
            sendMsg[2] = (byte) 0;
        } else {
            sendMsg[2] = (byte) (1 & 0xff);
        }
        System.arraycopy(message,0,sendMsg,3,message.length);
        ConcurrentHashMap<Integer, ConnectionHandler<byte[]>> clients = ((ConnectionsImpl<byte[]>) connections).getClients();
        for (ConnectionHandler<byte[]> handler : clients.values()) {
            if (((BlockingConnectionHandler<byte[]>) handler).getIsLoggedIn()) {
                handler.send(sendMsg);
            }
        }
    }

    private byte[] DELRQ(byte[] message,ConnectionHandler<byte[]> handler) {
        byte[] FileNameByte = new byte[message.length - 2];
        for (int i = 0; i < FileNameByte.length; i++) {
            FileNameByte[i] = message[i+2];
        }
        String FileName = new String(message, 2, message.length - 2, StandardCharsets.UTF_8);
        String path = directory+"/"+FileName;
        File file = new File(path);
        if (file.exists() && file.isFile()) {
            if(ContainsZero(FileNameByte)){
                return Get_ERROR(ERROR.ACCESS_VIOLATION);
            }
            if(!((BlockingConnectionHandler<byte[]>) handler).getIsLoggedIn()){
                return Get_ERROR(ERROR.NOT_CONNECTED);
            }
            else{
                boolean deleted = file.delete();
                if (deleted) {
                    handler.send(Get_ACK(BlockNumber));
                    BCAST(true, FileNameByte);
                    return null;
                } else {
                    return Get_ERROR(ERROR.ACCESS_VIOLATION);
                }
            }
        } else {
            return Get_ERROR(ERROR.FILE_NOT_FOUND);
        }
    }

    private void DISC() {
        connections.disconnect(connectionId);
    }

    private byte[] createDataPacket(byte[] ansMessage) {
        int opcode = TftpEncoderDecoder.Opcode.DATA.ordinal();
        double leftToSend = (double) (ansMessage.length - 512 * (BlockNumber)) / 512;
        int leftToSent = (int)Math.ceil(leftToSend);
        if (ansMessage.length == 0) {
            dataSize=0;
            byte[] ans = new byte[6];
            byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
            ans[0] = opcodeByte[0];
            ans[1] = opcodeByte[1];
            int zero = (byte) 0;
            byte[] sizeMsg = new byte[]{(byte) ((zero >> 8) & 0xFF), (byte) (zero & 0xFF)};
            ans[2] = sizeMsg[0];
            ans[3] = sizeMsg[1];
            BlockNumber = 0;
            byte[] numPacketByte = new byte[]{(byte) ((BlockNumber >> 8) & 0xFF), (byte) (BlockNumber & 0xFF)};
            ans[4] = numPacketByte[0];
            ans[5] = numPacketByte[1];
            DataMsg = null;
            return ans;
        } else if (leftToSent == 0 && dataSize==512) {
            dataSize=0;
            BlockNumber++;
            byte[] ans = new byte[6];
            byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
            ans[0] = opcodeByte[0];
            ans[1] = opcodeByte[1];
            int zero = (byte) 0;
            byte[] sizeMsg = new byte[]{(byte) ((zero >> 8) & 0xFF), (byte) (zero & 0xFF)};
            ans[2] = sizeMsg[0];
            ans[3] = sizeMsg[1];
            byte[] numPacketByte = new byte[]{(byte) ((BlockNumber >> 8) & 0xFF), (byte) (BlockNumber & 0xFF)};
            ans[4] = numPacketByte[0];
            ans[5] = numPacketByte[1];
            BlockNumber = 0;
            DataMsg = null;
            return ans;
        } else if(leftToSent>0){
            int size = Math.min(512, (ansMessage.length) - 512 * (BlockNumber));
            int startingPoint = 512 * (BlockNumber);
            BlockNumber++;
            byte[] packetData = new byte[size];
            System.arraycopy(ansMessage, startingPoint, packetData, 0, size);
            dataSize = packetData.length;
            byte[] ans = new byte[size + 6];
            byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
            ans[0] = opcodeByte[0];
            ans[1] = opcodeByte[1];
            ans[2] = (byte) ((size >> 8) & 0xFF);
            ans[3] = (byte) (size & 0xFF);
            byte[] numPacketByte = new byte[]{(byte) ((BlockNumber >> 8) & 0xFF), (byte) (BlockNumber & 0xFF)};
            ans[4] = numPacketByte[0];
            ans[5] = numPacketByte[1];
            System.arraycopy(packetData, 0, ans, 6, packetData.length);
            return ans;
        }
        else{
            BlockNumber = 0;
            DataMsg = null;
            return null;
        }
    }

    private byte[] RRQ(byte[] message,ConnectionHandler<byte[]> handler) {
        byte[] FileNameByte = new byte[message.length - 2];
        for (int i = 0; i < FileNameByte.length; i++) {
            FileNameByte[i] = message[i+2];
        }
        String fileName = new String(FileNameByte, StandardCharsets.UTF_8);
        File file = new File(directory, fileName);
        if (file.exists() && file.isFile() && file.canRead()) {
            if(ContainsZero(FileNameByte)){
                return Get_ERROR(ERROR.ACCESS_VIOLATION);
            }
            if(!((BlockingConnectionHandler<byte[]>) handler).getIsLoggedIn()){
                return Get_ERROR(ERROR.NOT_CONNECTED);
            }
            byte[] Data = new byte[(int) file.length()];
            FileInputStream in;
            try {
                in = new FileInputStream(file);
                in.read(Data);
                in.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            DataMsg = Data;
            return createDataPacket(DataMsg);
        } else {
            return Get_ERROR(ERROR.FILE_NOT_FOUND);
        }

    }

    private byte[] WRQ(byte[] message,ConnectionHandler<byte[]> handler) {
        byte[] FileNameByte = new byte[message.length - 2];
        for (int i = 0; i < FileNameByte.length; i++) {
            FileNameByte[i] = message[i+2];
        }
        if (ContainsZero(FileNameByte)) {
            return Get_ERROR(ERROR.ACCESS_VIOLATION);
        }
        String fileName = new String(FileNameByte, StandardCharsets.UTF_8);
        File file = new File(directory, fileName);
        if (!file.exists()) {
            if(!((BlockingConnectionHandler<byte[]>) handler).getIsLoggedIn()){
                return Get_ERROR(ERROR.NOT_CONNECTED);
            }
            try {
                if (file.createNewFile()) {
                    createdFileName = fileName;
                    return Get_ACK(BlockNumber);
                } else {
                    return Get_ERROR(ERROR.DISC_FULL);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            return Get_ERROR(ERROR.FILE_EXISTS);
        }
    }

    private void uploadData(byte[] message,ConnectionHandler<byte[]> handler){
        byte[] Data = new byte[message.length - 6];
        for (int i = 6; i < message.length; i++) {
            Data[i - 6] = message[i];
        }
        int BlocknumberRecieved = ((message[4] & 0xFF) << 8) | (message[5] & 0xFF);
        File file = new File(directory, createdFileName);
        if (file.exists() && file.isFile()) {
            byte[] fileNameByte = createdFileName.getBytes(StandardCharsets.UTF_8);
            try(OutputStream out = Files.newOutputStream(Paths.get(directory+"/"+createdFileName),StandardOpenOption.APPEND)) {
                out.write(Data);
                handler.send(Get_ACK(BlocknumberRecieved));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if(Data.length<512){
                BCAST(false, fileNameByte);
            }
        }
        else {
            handler.send(Get_ERROR(ERROR.ACCESS_VIOLATION));
        }
    }

    private byte[] DIRQ (ConnectionHandler<byte[]> handler){
        if(!((BlockingConnectionHandler<byte[]>) handler).getIsLoggedIn()){
            return Get_ERROR(ERROR.NOT_CONNECTED);
        }
        File file = new File(directory);
        byte[] ansMsg = new byte[0];
        System.out.println(directory);
        if (file.exists() && file.isDirectory()) {
            File[] filesNames = file.listFiles();
            if (filesNames != null) {
                for (File name : filesNames) {
                    System.out.println(name.getName());
                    byte[] nameByte = name.getName().getBytes(StandardCharsets.UTF_8);
                    for (byte b : nameByte) {
                        byte[] newAnsMsg = new byte[ansMsg.length+1];
                        for(int i=0;i<ansMsg.length;i++){
                            newAnsMsg[i] = ansMsg[i];
                        }
                        newAnsMsg[newAnsMsg.length-1]=b;
                        ansMsg = newAnsMsg;
                    }
                    byte[] newAnsMsg = new byte[ansMsg.length+1];
                    for(int i=0;i<ansMsg.length;i++){
                        newAnsMsg[i] = ansMsg[i];
                    }
                    newAnsMsg[newAnsMsg.length-1]=(byte)0;
                    ansMsg = newAnsMsg;
                }
                byte[] newAnsMsg = new byte[ansMsg.length-1];
                for(int i=0;i<ansMsg.length-1;i++){
                    newAnsMsg[i] = ansMsg[i];
                }
                ansMsg = newAnsMsg;
            }
        }
        DataMsg = ansMsg;
        return createDataPacket(DataMsg);
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    public Connections<byte[]> getConnections(){
        return connections;
    }

    public byte[] getUserName(){
        return userName;
    }

    public Boolean ContainsZero(byte[] message){
        for (byte b: message){
            if(b==(byte)0){
                return true;
            }
        }
        return false;
    }
}
