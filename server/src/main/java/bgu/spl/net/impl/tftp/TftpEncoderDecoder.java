package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.MessageEncoderDecoder;

public class TftpEncoderDecoder implements MessageEncoderDecoder<byte[]> {
    //TODO: Implement here the TFTP encoder and decoder
    private byte[] bytesArr;
    private Opcode opcode;
    protected int opt_expected_len;

    public enum Opcode {
        None, RRQ, WRQ, DATA, ACK, ERROR, DIRQ, LOGRQ, DELRQ, BCAST, DISC
    }

    public TftpEncoderDecoder(){
        this.bytesArr = new byte[0];
        this.opcode = Opcode.None;
        this.opt_expected_len = Integer.MAX_VALUE;
    }

    public byte[] poolBytes() {
        byte[] message = bytesArr.clone();
        bytesArr = new byte[0];
        setOpcode(Opcode.None);
        return message;
    }

    private void setOpcode(Opcode opcode) {
        this.opcode = opcode;
        switch (opcode) {
            case None:
                opt_expected_len = Integer.MAX_VALUE;
                break;
            case RRQ:
            case WRQ:
            case DIRQ:
            case LOGRQ:
            case DELRQ:
            case DISC:
                opt_expected_len = 2;
                break;
            case BCAST:
                opt_expected_len = 3;
                break;
            case ACK:
            case ERROR:
                opt_expected_len = 4;
                break;
            case DATA:
                opt_expected_len = 6;
                break;
        }
    }

    private Opcode getOpcode() {
        return opcode;
    }

    private Opcode peekOpcode() {
        assert bytesArr.length >= 2;
        int u16Opcode = ((bytesArr[0] << 8) & 0xFF | (bytesArr[1] & 0xFF));
        return Opcode.values()[u16Opcode];

    }

    protected static Opcode peekOpcode(byte[] message) {
        assert message.length >= 2;
        int u16Opcode = ((message[0] & 0xFF) << 8 | (message[1] & 0xFF));
        return Opcode.values()[u16Opcode];
    }

    public byte[] encode(byte[] message) {
        //do we need to throw an error when msg has over 512 bytes?
        int u16Opcode = ((message[0] & 0xFF) << 8 | (message[1] & 0xFF));
        TftpEncoderDecoder.Opcode opcode = Opcode.values()[u16Opcode];
        switch (opcode) {
            case None:
                throw new IllegalArgumentException("Invalid opcode");
            case RRQ:
            case WRQ:
            case ERROR:
            case BCAST:
            case LOGRQ:
            case DELRQ:
                byte[] result = new byte[message.length + 1];
                System.arraycopy(message, 0, result, 0, message.length);
                result[message.length] = (byte) 0x00;
                return result;
            case DATA:
            case ACK:
                return message;
            default:
                throw new IllegalArgumentException("Invalid opcode");
        }
    }

    private boolean haveAddedZero(Opcode opcode) {
        switch (opcode) {
            case RRQ:
            case WRQ:
            case ERROR:
            case BCAST:
            case LOGRQ:
            case DELRQ:
            case None:
                return true;
            default:
                return false;
        }
    }

    public byte[] decodeNextByte(byte nextByte) {
        if (bytesArr.length >= opt_expected_len && nextByte == 0x00) {
            Opcode opcode = getOpcode();
            System.out.println(opcode.ordinal());
            byte[] message = poolBytes();
            return message;
        } else {
            byte[] newBytes = new byte[bytesArr.length + 1];
            System.arraycopy(bytesArr, 0, newBytes, 0, bytesArr.length);
            newBytes[bytesArr.length] = nextByte;
            bytesArr = newBytes;
            if (bytesArr.length == 2) {
                setOpcode(peekOpcode());
            }
            if (opcode == Opcode.DATA && bytesArr.length == 4) {
                int size = ((bytesArr[2] & 0xFF) << 8) | (bytesArr[3] & 0xFF);
                opt_expected_len = 6 + size;
            }
            if (!haveAddedZero(opcode) && bytesArr.length == opt_expected_len) {
                Opcode opcode = getOpcode();
                byte[] message = poolBytes();
                return message;
            }
            return null;
        }
    }
}