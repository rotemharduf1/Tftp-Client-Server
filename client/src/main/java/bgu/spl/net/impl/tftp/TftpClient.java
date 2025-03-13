package bgu.spl.net.impl.tftp;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

public class TftpClient {
    //TODO: implement the main logic of the client, when using a thread per client the main logic goes here
    static Boolean inputFlag = true;

    static Boolean stopKeyBoard =false;
    static Object inputLock = new Object();
    public static void main(String[] args) throws IOException {
        Thread keyboardThread;
        Thread listeningThread;
        AtomicReference<String> input = new AtomicReference<>("");
        if (args.length == 0) {
            args = new String[]{"localhost", "7777"};
        }

        if (args.length < 2) {
            System.out.println("you must supply two arguments: host, port");
            System.exit(1);
        }
        try (Socket sock = new Socket(args[0], Integer.parseInt(args[1]));
            BufferedInputStream in = new BufferedInputStream(new BufferedInputStream(sock.getInputStream()));
            BufferedOutputStream out = new BufferedOutputStream(new BufferedOutputStream(sock.getOutputStream())))
        {
            TftpClientProtocol protocol = new TftpClientProtocol();
            protocol.start();
            TftpClientEncoderDecoder encDec = new TftpClientEncoderDecoder();
            System.out.println("Connected to the server!");

            keyboardThread = new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                while (!protocol.shouldTerminate()) {
                    input.set(scanner.nextLine());
                    inputManager(input.get(), encDec, out, protocol);
                    while(!inputFlag){
                        input.set(scanner.nextLine());
                        inputManager(input.get(), encDec, out, protocol);
                    }
                    synchronized (inputLock){
                        while(stopKeyBoard){
                            try {
                                inputLock.wait();
                            } catch (InterruptedException e) {}
                        }
                    }
                }
            }, "userInput");

            listeningThread = new Thread(() -> {
                    int read;
                    while (!protocol.shouldTerminate() && sock.isConnected()) {
                        try{
                        while ((read = in.read()) >= 0) {
                            synchronized (inputLock) {
                                stopKeyBoard = true;
                                byte[] message = encDec.decodeNextByte((byte) read);
                                if (message != null) {
                                    protocol.process(message);
                                    if (protocol.getMsgToServer().length!=0) {
                                        send(protocol.getMsgToServer(), encDec, out);
                                        protocol.setMsgToServerToEmpty();
                                    }
                                    inputLock.notifyAll();
                                    stopKeyBoard = false;
                                }
                            }
                        }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
            }, "process");

            keyboardThread.start();
            listeningThread.start();

            try {
                keyboardThread.join();
                listeningThread.join();
            } catch (InterruptedException e) {
            }
        }
    }

    public static void inputManager(String input, TftpClientEncoderDecoder encdec, BufferedOutputStream out, TftpClientProtocol protocol) {
        String[] line = input.split(" ", 2);
        TftpClientEncoderDecoder.Opcode opcode =  TftpClientEncoderDecoder.Opcode.valueOf(line[0]);
        int opcodeInt = opcode.ordinal();
        byte[] opcodeByte= new byte[]{(byte) ((opcodeInt >> 8) & 0xFF), (byte) (opcodeInt & 0xFF)};
        protocol.setLastOpcode(opcode);
        switch (opcode) {
            case DELRQ:
            case LOGRQ:
                if (line.length > 1) {
                    byte[] name = line[1].getBytes(StandardCharsets.UTF_8);
                    byte[] action = new byte[2+name.length];
                    action[0] = opcodeByte[0];
                    action[1] = opcodeByte[1];
                    for(int i=2;i<action.length;i++){
                        action[i] = name[i-2];
                    }
                    inputFlag = true;
                    send(action,encdec,out);
                } else {
                    inputFlag = false;
                    System.out.println("invalid command");
                }
                break;
            case RRQ:
                if (line.length > 1) {
                    byte[] fileName = line[1].getBytes(StandardCharsets.UTF_8);
                    byte[] action = new byte[2+fileName.length];
                    action[0] = opcodeByte[0];
                    action[1] = opcodeByte[1];
                    for(int i=2;i<action.length;i++){
                        action[i] = fileName[i-2];
                    }
                    String SfileName = new String(fileName, StandardCharsets.UTF_8);
                    File file = new File(protocol.getDirectory(), SfileName);
                    if (file.exists()) {
                        inputFlag = false;
                        System.out.println("file already exists");
                        break;
                    } else {
                        try {
                            if (file.createNewFile()) {
                                protocol.setCreatedFileName(SfileName);
                                inputFlag = true;
                                send(action, encdec, out);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                } else {
                    inputFlag = false;
                    System.out.println("invalid command");
                }
                break;
            case WRQ:
                if (line.length > 1) {
                    byte[] fileName = line[1].getBytes(StandardCharsets.UTF_8);
                    protocol.setCreatedFileName(line[1]);
                    String SfileName = new String(fileName, StandardCharsets.UTF_8);
                    byte[] action = new byte[2+fileName.length];
                    action[0] = opcodeByte[0];
                    action[1] = opcodeByte[1];
                    System.arraycopy(fileName,0,action,2,fileName.length);
                    File file = new File(protocol.getDirectory(), SfileName);
                    if (!file.exists()) {
                        inputFlag = false;
                        System.out.println("file does not exists");
                        break;
                    }
                    else{
                        inputFlag = true;
                        send(action, encdec, out);
                    }
                } else {
                    inputFlag = false;
                    System.out.println("invalid command");
                }
                break;
            case DIRQ:
                inputFlag = true;
                send(opcodeByte, encdec, out);
                break;
            case DISC:
                inputFlag = true;
                send(opcodeByte, encdec, out);
                break;
            default:
                inputFlag = false;
                System.out.println("invalid commend");
        }
    }

    public static void send(byte[] message, TftpClientEncoderDecoder encdec, BufferedOutputStream out) {
        try {
            out.write(encdec.encode(message));
            out.flush();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }





}
