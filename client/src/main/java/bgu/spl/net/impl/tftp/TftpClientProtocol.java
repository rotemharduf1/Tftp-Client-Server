package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.MessagingProtocol;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class TftpClientProtocol implements MessagingProtocol<byte[]> {

    private boolean shouldTerminate;
    private final String directory = System.getProperty("user.dir") + "/client";

    private byte[] userName;

    private byte[] msgToServer;
    private byte[] msgData;
    private String createdFileName;

    private int SelfBlockNumber;

    private int dataSize;

    protected TftpClientEncoderDecoder.Opcode lastOpcode;

    public enum ERROR {
        NOT_DEFINED, FILE_NOT_FOUND, ACCESS_VIOLATION, DISC_FULL, UNKNOWN_OPCODE, FILE_EXISTS, NOT_CONNECTED, ALREADY_CONNECTED;
    }

    public void start() {
        // TODO implement this
        this.shouldTerminate = false;
        this.userName = new byte[0];
        this.msgToServer = new byte[0];
        this.createdFileName = null;
        this.lastOpcode = TftpClientEncoderDecoder.Opcode.None;
        this.SelfBlockNumber = 0;
        this.dataSize = 0;
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    //we need to check how to synchronize the files - when one read other can write etc.
    @Override
    public byte[] process(byte[] message) {
        // TODO implement this
        TftpClientEncoderDecoder.Opcode opcode = TftpClientEncoderDecoder.peekOpcode(message);
        byte[] msgOut = null;
        switch (opcode) {
            case DATA:
                Handling_Data(message);
                return null;
            case ERROR:
                PRINT_ERROR(message);
                lastOpcode = TftpClientEncoderDecoder.Opcode.None;
                return null;
            case ACK:
                switch (lastOpcode) {
                    case LOGRQ:
                    case DELRQ:
                        PRINT_ACK(message);
                        lastOpcode = TftpClientEncoderDecoder.Opcode.None;
                        return null;
                    case DISC:
                        PRINT_ACK(message);
                        lastOpcode = TftpClientEncoderDecoder.Opcode.None;
                        shouldTerminate = true;
                        return null;
                    case WRQ:
                        if (SelfBlockNumber == 0) {
                            SEND_DATA(createdFileName);
                        } else {
                            createDataPacket(msgData);
                        }
                        return null;
                }
            case BCAST:
                PRINT_BCAST(message);
                lastOpcode = TftpClientEncoderDecoder.Opcode.None;
                return null;
            default:
                System.out.println("invalid opcode");
                lastOpcode = TftpClientEncoderDecoder.Opcode.None;
                return null;
        }
    }

    private void SEND_DATA(String createdFileName) {
        File file = new File(directory, createdFileName);
        if (file.exists() && file.isFile()) {
            byte[] data = new byte[(int) file.length()];
            FileInputStream in;
            try {
                in = new FileInputStream(file);
                in.read(data);
                in.close();
                msgData = data;
                createDataPacket(msgData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void createDataPacket(byte[] ansMessage) {
        int opcode = TftpClientEncoderDecoder.Opcode.DATA.ordinal();
        double leftToSend = (double) (ansMessage.length - 512 * (SelfBlockNumber)) / 512;
        int leftToSent = (int) Math.ceil(leftToSend);
        if (ansMessage.length == 0) {
            dataSize = 0;
            byte[] ans = new byte[6];
            byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
            ans[0] = opcodeByte[0];
            ans[1] = opcodeByte[1];
            int zero = (byte) 0;
            byte[] sizeMsg = new byte[]{(byte) ((zero >> 8) & 0xFF), (byte) (zero & 0xFF)};
            ans[2] = sizeMsg[0];
            ans[3] = sizeMsg[1];
            ans[4] = sizeMsg[0];
            ans[5] = sizeMsg[1];
            msgData = new byte[0];
            msgToServer = ans;
        } else if (leftToSent == 0 && dataSize == 512) {
            dataSize = 0;
            SelfBlockNumber++;
            byte[] ans = new byte[6];
            byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
            ans[0] = opcodeByte[0];
            ans[1] = opcodeByte[1];
            int zero = (byte) 0;
            byte[] sizeMsg = new byte[]{(byte) ((zero >> 8) & 0xFF), (byte) (zero & 0xFF)};
            ans[2] = sizeMsg[0];
            ans[3] = sizeMsg[1];
            byte[] numPacketByte = new byte[]{(byte) ((SelfBlockNumber >> 8) & 0xFF), (byte) (SelfBlockNumber & 0xFF)};
            ans[4] = numPacketByte[0];
            ans[5] = numPacketByte[1];
            SelfBlockNumber = 0;
            msgData = new byte[0];
            msgToServer = ans;
        } else if (leftToSent > 0) {
            int size = Math.min(512, (ansMessage.length) - 512 * (SelfBlockNumber));
            int startingPoint = 512 * (SelfBlockNumber);
            SelfBlockNumber++;
            byte[] packetData = new byte[size];
            System.arraycopy(ansMessage, startingPoint, packetData, 0, size);
            dataSize = packetData.length;
            byte[] ans = new byte[size + 6];
            byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
            ans[0] = opcodeByte[0];
            ans[1] = opcodeByte[1];
            ans[2] = (byte) ((size >> 8) & 0xFF);
            ans[3] = (byte) (size & 0xFF);
            byte[] numPacketByte = new byte[]{(byte) ((SelfBlockNumber >> 8) & 0xFF), (byte) (SelfBlockNumber & 0xFF)};
            ans[4] = numPacketByte[0];
            ans[5] = numPacketByte[1];
            System.arraycopy(packetData, 0, ans, 6, packetData.length);
            msgToServer = ans;
        } else {
            SelfBlockNumber = 0;
            msgData = new byte[0];
        }
    }

    private void PRINT_ERROR(byte[] message) {
        int ErrorCode = ((message[2] & 0xFF) << 8) | (message[3] & 0xFF);
        String errMsg = "Error " + ErrorCode + " ";
        ERROR[] errorArray = ERROR.values();
        ERROR error = errorArray[ErrorCode];
        switch (error) {
            case NOT_DEFINED:
                errMsg = errMsg + "Not defined, see error message (if any).";
                break;
            case FILE_NOT_FOUND:
                errMsg = errMsg + "File not found - RRQ DELRQ of non-existing file.";
                break;
            case ACCESS_VIOLATION:
                errMsg = errMsg + "Access violation - File cannot be written, read or deleted.";
                break;
            case DISC_FULL:
                errMsg = errMsg + "Disk full or allocation exceeded - No room in disk.";
                break;
            case UNKNOWN_OPCODE:
                errMsg = errMsg + "Illegal TFTP operation - Unknown Opcode.";
                break;
            case FILE_EXISTS:
                errMsg = errMsg + "File already exists - File name exists on WRQ.";
                break;
            case NOT_CONNECTED:
                errMsg = errMsg + "User not logged in - Any opcode received before Login completes.";
                break;
            case ALREADY_CONNECTED:
                errMsg = errMsg + "User already logged in - Login username already connected.";
                break;
            default:
                errMsg = errMsg + "Not defined, see error message (if any).";
                break;
        }
        System.out.println(errMsg);
    }

    private void PRINT_ACK(byte[] message) {
        int BlockNum = ((message[2] & 0xFF) << 8) | (message[3] & 0xFF);
        System.out.println("ACK " + BlockNum);
        if (lastOpcode == TftpClientEncoderDecoder.Opcode.DISC) {
            shouldTerminate = true;
        }
    }

    private void PRINT_BCAST(byte[] message) {
        int action = (int) message[2];
        String FileName = new String(message, 3, message.length - 3, StandardCharsets.UTF_8);
        if (action == 0) {
            System.out.println("BCAST del " + FileName);
        } else {
            System.out.println("BCAST add " + FileName);
        }
        msgData = new byte[0];
    }

    private void Handling_Data(byte[] message) {
        int packetSize = ((message[2] & 0xFF) << 8) | (message[3] & 0xFF);
        int blockNum = ((message[4] & 0xFF) << 8) | (message[5] & 0xFF);
        byte[] Data = new byte[packetSize];
        System.arraycopy(message, 6, Data, 0, packetSize);
        if (lastOpcode == TftpClientEncoderDecoder.Opcode.RRQ) {
            try (OutputStream outStream = Files.newOutputStream((Paths.get(directory + "/" + createdFileName)), StandardOpenOption.APPEND)) {
                outStream.write(Data);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (packetSize < 512) {
                lastOpcode = TftpClientEncoderDecoder.Opcode.None;
            }
            msgToServer = Get_ACK(blockNum);
        }
        if (lastOpcode == TftpClientEncoderDecoder.Opcode.DIRQ) {
            ByteBuffer buff = ByteBuffer.allocate(512 * 100000);
            if (!buff.hasRemaining()) {
                int newCapacity = buff.capacity() * 2;
                ByteBuffer newBuff = ByteBuffer.allocate(newCapacity);
                buff.flip();
                newBuff.put(buff);
                buff = newBuff;
            }
            buff.put(Data);
            buff.flip();
            if (packetSize < 512) {
                String names = new String(Data, StandardCharsets.UTF_8);
                names = names.replace((char) 0, '\n');
                System.out.println(names);
                lastOpcode = TftpClientEncoderDecoder.Opcode.None;
            }
            msgToServer = Get_ACK(blockNum);
        }
    }

    private byte[] Get_ACK(int blockNum) {
        byte[] byteAns = new byte[4];
        int opcode = TftpClientEncoderDecoder.Opcode.ACK.ordinal();
        byte[] opcodeByte = new byte[]{(byte) ((opcode >> 8) & 0xFF), (byte) (opcode & 0xFF)};
        byteAns[0] = opcodeByte[0];
        byteAns[1] = opcodeByte[1];
        byte[] blockNumByte = new byte[]{(byte) ((blockNum >> 8) & 0xFF), (byte) (blockNum & 0xFF)};
        byteAns[2] = blockNumByte[0];
        byteAns[3] = blockNumByte[1];
        return byteAns;
    }

    public byte[] getUserName() {
        return userName;
    }

    public String getDirectory() {
        return directory;
    }

    public byte[] getMsgToServer() {
        return msgToServer;
    }

    public void setMsgToServerToEmpty() {
        msgToServer = new byte[0];
    }

    public void setLastOpcode(TftpClientEncoderDecoder.Opcode opcode) {
        lastOpcode = opcode;
    }


    public void setCreatedFileName(String name) {
        createdFileName = name;
    }
}
