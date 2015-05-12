package cz.filipklimes.psi.udp;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * DISCLAIMER: This code is full of technical debt. It has been written under deadline pressure and huge amount of
 * frustration. All classes must be in one file because a genius at FEE CTU decided it's the best way to implement
 * a program in Java.
 */

/**
 * Main class of the program.
 *
 * @author klimesf
 */
public class Robot {

    public static final int PORT_TO = 4000;
    public static final int PORT_FROM = 4000;
    public static boolean verbose = false;

    public static void main(String[] args) throws Exception {
        DatagramSocket socket;
        InetAddress address;
        int fromPort = Robot.PORT_FROM, portTo = Robot.PORT_TO;
        socket = new DatagramSocket(fromPort);
        address = InetAddress.getByName(args[0]);

        for (String val : args) {
            if (val.equals("-v")) {
                Robot.verbose = true;
                break;
            }
        }

        System.out.printf("Connecting to server %s:%s\n", args[0], portTo);

        SocketHandler handler = new PhotoReceiver(socket, address, portTo);
        handler.handle();

        if (!socket.isClosed()) {
            socket.close();
        }
    }
}

interface SocketHandler {
    void handle() throws IOException, CorruptedPacketException;
}

/**
 * Receives photos.
 *
 * @author klimesf
 */
class PhotoReceiver implements SocketHandler {

    private final DatagramSocket socket;
    private final InetAddress address;
    private final Integer port;
    private final FileOutputStream fos;
    private ConnectionId connectionId;
    private Map<Integer, KarelPacket> windowPackets = new HashMap<>();
    private int pointer;
    private Long totalBytes;
    private Long totalPackets;
    private Long successfulPackets;
    private Set<Integer> receivedKeys = new HashSet<>();

    PhotoReceiver(DatagramSocket socket, InetAddress address, Integer port) throws FileNotFoundException {
        this.socket = socket;
        this.address = address;
        this.port = port;
        fos = new FileOutputStream("foto.png");
        pointer = 0;
        totalBytes = (long) 0;
        totalPackets = (long) 0;
        successfulPackets = (long) 0;
    }

    /**
     * @throws IOException
     * @throws CorruptedPacketException
     */
    public void handle() throws IOException, CorruptedPacketException {
        this.openConnection();
        this.receiveData();
        this.fos.close();
    }

    private void receiveData() throws IOException, CorruptedPacketException {
        DatagramPacket packet;
        KarelPacket receivedKarelPacket, ackKarelPacket;

        System.out.println("Starting to download photo: \n\n");

        while (!socket.isClosed()) {
            ackKarelPacket = null; // Reset new packet

            // Receive reply with data
            packet = new DatagramPacket(new byte[255 + 9], 255 + 9);
            socket.receive(packet);
            receivedKarelPacket = KarelPacket.parseFromDatagramPacket(packet);
            ++totalPackets;

            if (Robot.verbose) {
                System.out.println("RECV(data): " + receivedKarelPacket.toString());
            }

            // Process the packet and reply with acknowledge
            if (receivedKarelPacket.getId().equals(connectionId)) {
                printPercent();
                int pointerBefore = pointer; // Just for the lulz

                if (receivedKarelPacket.getFlag().isCarryingData()) {
                    ackKarelPacket = receiveDataPacket(receivedKarelPacket);
                } else if (receivedKarelPacket.getFlag().isClosingConnection()) {
                    ackKarelPacket = receiveClosingPacket(receivedKarelPacket);
                } else {
                    throw new RuntimeException("Unknown type of packet.");
                }

                // Shit happens, I would love to know why
                if (pointer < pointerBefore && pointerBefore < 60000) {
                    throw new RuntimeException("LOLWUT");
                }

                // Send acknowledge packet
                sendAcknowledgePacket(ackKarelPacket);

                // If we received closing flag packet, shutdown the connection
                if (receivedKarelPacket.getFlag().isClosingConnection()) {
                    System.out.println("Closing connection.");
                    socket.close();
                    break;
                }
            } else {
                System.out.println("RECV wrong connection id");
            }
        }
    }

    /**
     * Receives closing KarelPacket and creates appropriate response.
     *
     * @param receivedKarelPacket The received closing packet.
     * @return The response packet. Could be null, if no response should be sent.
     */
    private KarelPacket receiveClosingPacket(KarelPacket receivedKarelPacket) {
        if (receivedKarelPacket.getFlag().isFinishing()) {
            System.out.printf("Received a finishing packet. Received %d bytes in %d successful of %d total packets during this session.\n", totalBytes, successfulPackets, totalPackets);
            return KarelPacket.createAcknowledgePacket(connectionId, receivedKarelPacket.getSq().toInteger(), receivedKarelPacket.getFlag());
        } else {
            System.out.println("Received an error packet.");
            return null;
        }
    }

    /**
     * Receives data packet and creates appropriate response.
     *
     * @param receivedKarelPacket The received data packet.
     * @return The response packet. Could be null, if no response should be sent.
     * @throws IOException
     */
    private KarelPacket receiveDataPacket(KarelPacket receivedKarelPacket) throws IOException {
        KarelPacket ackKarelPacket;
        if (receivedKarelPacket.getSq().toInteger().equals(pointer)) {
            // If we received packet which continues in the window
            ackKarelPacket = acceptPacket(receivedKarelPacket);

        } else if (receivedKarelPacket.getSq().toInteger().compareTo(pointer) > 0) {
            // If we received packet which is further in line than the pointer
            if (Robot.verbose) {
                System.out.println("Received packet which doesn't follow up, saving for further use.");
            }
            ackKarelPacket = saveForFurtherUse(receivedKarelPacket);

        } else { // if (receivedKarelPacket.getSq().toInteger().compareTo(pointer) < 0) {
            // If sq is lower than the pointer
            if (receivedKeys.contains(receivedKarelPacket.getSq().toInteger())) {
                // If we received packet which we already had
                ackKarelPacket = KarelPacket.createAcknowledgePacket(connectionId, pointer, receivedKarelPacket.getFlag());
            } else {
                // If the pointer overflowed
                receivedKeys = new HashSet<>();
                ackKarelPacket = saveForFurtherUse(receivedKarelPacket);
            }
        }
        return ackKarelPacket;
    }

    private void printPercent() {
        if (!Robot.verbose) {
            long percent = Math.round(((double) totalBytes / 204218.) * 100);
            int milestone = 100 / 50;
            StringBuilder sb = new StringBuilder(52).append('|');
            for (int i = 0; i < 100; i++) {
                if ((i + 1) % milestone == 0) {
                    if (i < percent) {
                        sb.append('#');
                    } else {
                        sb.append(' ');
                    }
                }
            }
            sb.append('|');
            System.out.printf("%3d%% %s\n", percent, sb.toString());
        }
    }

    /**
     * Saves packet for further user and creates correct acknowledge packet.
     *
     * @param receivedKarelPacket The received KarelPacket.
     * @return The KarelPacket which acknowledges the server about the current state.
     */
    private KarelPacket saveForFurtherUse(KarelPacket receivedKarelPacket) {
        KarelPacket ackKarelPacket;
        windowPackets.put(receivedKarelPacket.getSq().toInteger(), receivedKarelPacket);
        receivedKeys.add(receivedKarelPacket.getSq().toInteger());
        ackKarelPacket = KarelPacket.createAcknowledgePacket(connectionId, pointer, receivedKarelPacket.getFlag());
        return ackKarelPacket;
    }

    /**
     * Accepts KarelPacket, saves its data to a file, iterates over previously stored packets and creates a correct
     * acknowledge packet.
     *
     * @param receivedKarelPacket The received KarelPacket.
     * @return The KarelPacket which acknowledges the server about the current state.
     * @throws IOException
     */
    private KarelPacket acceptPacket(KarelPacket receivedKarelPacket) throws IOException {
        KarelPacket ackKarelPacket;
        fos.write(receivedKarelPacket.getData().getBytes());
        receivedKeys.add(receivedKarelPacket.getSq().toInteger());
        addToPointer(receivedKarelPacket.getData().getLength());
        totalBytes += receivedKarelPacket.getData().getLength();
        ++successfulPackets;
        // If we already received packets further in the window
        while (windowPackets.containsKey(pointer)) {
            KarelPacket next = windowPackets.get(pointer);
            windowPackets.remove(pointer);
            fos.write(next.getData().getBytes());
            totalBytes += next.getData().getLength();
            addToPointer(next.getData().getLength());
            ++successfulPackets;
        }
        if (Robot.verbose) {
            System.out.printf("WINDOW: pointer: %d, total: %d\n", pointer, totalBytes);
        }
        ackKarelPacket = KarelPacket.createAcknowledgePacket(connectionId, pointer, receivedKarelPacket.getFlag());
        return ackKarelPacket;
    }

    private void addToPointer(int length) {
        pointer += length;
        pointer = (new Integer(pointer).shortValue()) & 0xffff;
    }

    /**
     * Sends given acknowledge packet.
     *
     * @param ackKarelPacket The acknowledge packet to be sent.
     * @throws IOException
     */
    private void sendAcknowledgePacket(KarelPacket ackKarelPacket) throws IOException {
        if (ackKarelPacket != null) {
            DatagramPacket packet;// Else, if we should send ack packet, send it
            packet = ackKarelPacket.createDatagramPacket(address, port);
            socket.send(packet);
            if (Robot.verbose) {
                System.out.println("SEND(ack): " + ackKarelPacket.toString());
            }
        }
    }

    /**
     * Opens connection to the server.
     *
     * @throws IOException
     * @throws CorruptedPacketException
     */
    private void openConnection() throws IOException, CorruptedPacketException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future futureResult = executorService.submit(new InitPacketReceiver(this));
        while (connectionId == null) {
            this.sendInitPacket(socket, address, port);
            try {
                futureResult.get(100, TimeUnit.MILLISECONDS);
            } catch (CancellationException e) {
            } catch (InterruptedException e) {
            } catch (ExecutionException e) {
            } catch (TimeoutException e) {
                if (connectionId == null) {
                    System.err.println("Opening packet has been lost, sending a new one.");
                }
            }
        }
        futureResult.cancel(true);
        executorService.shutdown();
    }

    private class InitPacketReceiver implements Runnable {
        private PhotoReceiver parent;

        public InitPacketReceiver(PhotoReceiver parent) {
            this.parent = parent;
        }

        @Override
        public void run() {
            try {
                while (connectionId == null) {
                    parent.receiveInitPacket(socket);
                }
            } catch (CorruptedPacketException e) {
            } catch (IOException e) {
            }
        }
    }

    private void sendInitPacket(DatagramSocket socket, InetAddress address, Integer port) throws IOException {
        KarelPacket karelPacket;
        DatagramPacket packet;

        // Send init packet
        karelPacket = KarelPacket.createOpeningPacket(PacketData.createPhotoCommand());
        packet = karelPacket.createDatagramPacket(address, port);
        socket.send(packet);
        if (Robot.verbose) {
            System.out.println("SEND(init): " + karelPacket.toString());
        }
    }

    private void receiveInitPacket(DatagramSocket socket) throws CorruptedPacketException, IOException {
        // Receive reply with connection id
        DatagramPacket packet = new DatagramPacket(new byte[9], 9);
        socket.receive(packet);
        KarelPacket karelPacket = KarelPacket.parseFromDatagramPacket(packet);

        if (karelPacket.getFlag().isOpening()) {
            connectionId = karelPacket.getId();
            if (Robot.verbose) {
                System.out.println("RECV(init): " + karelPacket.toString());
            }
            System.out.printf("Opened connection with id %s.\n", connectionId.toString());
        } else if (karelPacket.getFlag().isCarryingData()) {
            if (Robot.verbose) {
                System.out.println("RECV(init): rubbish");
            }
        }
    }
}


// *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *
// --- PACKET  --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- -
// *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** *

/**
 * Helpers for conversion between byte array and integer.
 *
 * @author klimesf
 */
class Helpers {
    public static Integer byteArrayToInteger(byte[] bytes) {
        Short value = new BigInteger(bytes).shortValue();
        return value & 0xffff;
    }

    public static byte[] intToByteArray(int value) {
        ByteBuffer dbuf = ByteBuffer.allocate(4);
        dbuf.putInt(value);
        return dbuf.array();
    }
}

abstract class TwoByteNumber {
    private final byte[] bytes;

    public TwoByteNumber(byte a, byte b) {
        this.bytes = new byte[2];
        this.bytes[0] = a;
        this.bytes[1] = b;
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return Helpers.byteArrayToInteger(bytes).toString();
    }

    public Integer toInteger() {
        return Helpers.byteArrayToInteger(bytes);
    }
}

class ConnectionId {

    private final byte[] bytes;

    ConnectionId(byte a, byte b, byte c, byte d) {
        this.bytes = new byte[4];
        this.bytes[0] = a;
        this.bytes[1] = b;
        this.bytes[2] = c;
        this.bytes[3] = d;
    }

    public ConnectionId(int a, int b, int c, int d) {
        this((byte) a, (byte) b, (byte) c, (byte) d);
    }

    public byte[] getBytes() {
        return bytes;
    }

    public static ConnectionId parseFromDatagramPacket(DatagramPacket packet) {
        return new ConnectionId(packet.getData()[0], packet.getData()[1], packet.getData()[2], packet.getData()[3]);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ConnectionId) {
            ConnectionId other = (ConnectionId) obj;
            return other.getBytes()[0] == bytes[0] &&
                    other.getBytes()[1] == bytes[1] &&
                    other.getBytes()[2] == bytes[2] &&
                    other.getBytes()[3] == bytes[3];
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%01X", b));
        }
        return sb.toString();
    }
}

class SequenceNumber extends TwoByteNumber {
    public SequenceNumber(int a, int b) {
        super((byte) a, (byte) b);
    }

    public static SequenceNumber parseFromDatagramPacket(DatagramPacket packet) {
        return new SequenceNumber(packet.getData()[4], packet.getData()[5]);
    }
}

class AcknowledgeNumber extends TwoByteNumber {
    public AcknowledgeNumber(int a, int b) {
        super((byte) a, (byte) b);
    }

    public static AcknowledgeNumber parseFromDatagramPacket(DatagramPacket packet) {
        return new AcknowledgeNumber(packet.getData()[6], packet.getData()[7]);
    }
}

class FlagNumber {

    private static final byte SYN_FLAG = 0b00000100;
    private static final byte FIN_FLAG = 0b00000010;
    private static final byte RST_FLAG = 0b00000001;
    private final byte bytes;

    public FlagNumber(byte a) {
        this.bytes = a;
    }

    public byte getBytes() {
        return bytes;
    }

    public static FlagNumber createOpeningFlag() {
        return new FlagNumber(SYN_FLAG);
    }

    public static FlagNumber createFinishingFlag() {
        return new FlagNumber(FIN_FLAG);
    }

    public static FlagNumber createCancelFlag() {
        return new FlagNumber(RST_FLAG);
    }

    public static FlagNumber parseFromDatagramPacket(DatagramPacket packet) {
        return new FlagNumber(packet.getData()[8]);
    }

    public boolean isClosingConnection() {
        return this.getBytes() == FIN_FLAG || this.getBytes() == RST_FLAG;
    }

    public boolean isOpening() {
        return this.getBytes() == SYN_FLAG;
    }

    public boolean isFinishing() {
        return this.getBytes() == FIN_FLAG;
    }

    public boolean isCancel() {
        return this.getBytes() == RST_FLAG;
    }

    public boolean isCarryingData() {
        return !this.isClosingConnection() && !this.isOpening();
    }

    @Override
    public String toString() {
        return String.format("%01X", bytes);
    }
}

/**
 * Data of KarelPacket.
 */
class PacketData {

    private final static byte PHOTO_COMMAND = 0x1;
    private final static byte FIRMWARE_COMMAND = 0x1;

    private final byte[] bytes;
    private int length = -1;

    PacketData(byte[] data) {
        this.bytes = data;
    }

    public int getLength() {
        return bytes != null && bytes.length > 0 ? bytes.length : 0;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public static PacketData createPhotoCommand() {
        return new PacketData(new byte[]{PHOTO_COMMAND});
    }

    public static PacketData createFirmwareCommand() {
        return new PacketData(new byte[]{FIRMWARE_COMMAND});
    }

    public static PacketData parseFromDatagramPacket(DatagramPacket packet) {
        ByteBuffer bf = ByteBuffer.wrap(packet.getData());
        bf.getInt();
        bf.getShort();
        bf.getShort();
        bf.get();
        byte[] buf = new byte[packet.getLength() - 9];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = bf.get();
        }
        return new PacketData(buf);
    }
}

/**
 * KarelPacket is a packet used for communicating with server Karel.
 * It can't be sent itself, but it is an abstraction for DatagramPacket.
 */
class KarelPacket {
    private final ConnectionId id;
    private final SequenceNumber sq;
    private final AcknowledgeNumber ack;
    private final FlagNumber flag;
    private final PacketData data;

    /**
     * @param id   Id of the connection.
     * @param sq   Sequence number of the packet.
     * @param ack  Acknowledge number of the packet.
     * @param flag Flag number of the packet.
     * @param data Data of the packet.
     */
    public KarelPacket(ConnectionId id,
                       SequenceNumber sq,
                       AcknowledgeNumber ack,
                       FlagNumber flag,
                       PacketData data) {
        this.id = id;
        this.sq = sq;
        this.ack = ack;
        this.flag = flag;
        this.data = data;
    }

    /**
     * Creates datagram packet containing the data of this KarelPacket.
     *
     * @param address Address the packet should be sent to.
     * @param port    Port the packet should be sent to.
     * @return The DatagramPacket.
     */
    public DatagramPacket createDatagramPacket(InetAddress address, Integer port) {
        byte[] message = this.buildBytes();
        return new DatagramPacket(message, message.length, address, port);
    }

    @Override
    public String toString() {
        return String.format("id=%s seq=%s ack=%s flag=%s data(%d)",
                id.toString(),
                sq.toString(),
                ack.toString(),
                flag.toString(),
                data.getLength()
        );
    }

    public ConnectionId getId() {
        return id;
    }

    public SequenceNumber getSq() {
        return sq;
    }

    public AcknowledgeNumber getAck() {
        return ack;
    }

    public FlagNumber getFlag() {
        return flag;
    }

    public PacketData getData() {
        return data;
    }

    /**
     * Builds byte array for DatagramPacket.
     *
     * @return The byte array with all information.
     */
    private byte[] buildBytes() {
        byte[] message = new byte[4 + 2 + 2 + 1 + data.getLength()];
        System.arraycopy(id.getBytes(), 0, message, 0, 4);
        System.arraycopy(sq.getBytes(), 0, message, 4, 2);
        System.arraycopy(ack.getBytes(), 0, message, 6, 2);
        message[8] = flag.getBytes();
        System.arraycopy(data.getBytes(), 0, message, 9, data.getBytes().length);
        return message;
    }

    /**
     * Creates KarelPacket with given data.
     *
     * @param id   Id of the connection.
     * @param sq   Packet sequence number.
     * @param ack  Packet acknowledge number.
     * @param flag Packet flag.
     * @param data Packet data.
     * @return The KarelPacket with data.
     */
    public static KarelPacket createPacket(ConnectionId id,
                                           SequenceNumber sq,
                                           AcknowledgeNumber ack,
                                           FlagNumber flag,
                                           PacketData data) {
        return new KarelPacket(id, sq, ack, flag, data);
    }

    /**
     * Creates opening packet with given PacketData.
     *
     * @param packetData The PacketData to be appended.
     * @return The created KarelPacket.
     */
    public static KarelPacket createOpeningPacket(PacketData packetData) {
        return new KarelPacket(
                new ConnectionId(0x0, 0x0, 0x0, 0x0),
                new SequenceNumber(0x0, 0x0),
                new AcknowledgeNumber(0x0, 0x0),
                FlagNumber.createOpeningFlag(),
                packetData
        );
    }

    /**
     * Maps given DatagramPacket to KarelPacket.
     *
     * @param packet The incoming DatagramPacket.
     * @return The KarelPacket with data.
     */
    public static KarelPacket parseFromDatagramPacket(DatagramPacket packet) throws CorruptedPacketException {
        if (packet.getData().length < 9) {
            throw new CorruptedPacketException("Corrupted DatagramPacket. Could not parse data.");
        }
        return new KarelPacket(
                ConnectionId.parseFromDatagramPacket(packet),
                SequenceNumber.parseFromDatagramPacket(packet),
                AcknowledgeNumber.parseFromDatagramPacket(packet),
                FlagNumber.parseFromDatagramPacket(packet),
                PacketData.parseFromDatagramPacket(packet)
        );
    }

    /**
     * Creates packet which acknowledges the server about accepted sequence of data.
     *
     * @param connectionId Id of the connection.
     * @param pointer      Value of the pointer.
     * @return The KarelPacket with acknowledge data.
     */
    public static KarelPacket createAcknowledgePacket(ConnectionId connectionId, Integer pointer, FlagNumber flag) {
        byte[] acknowledge = Helpers.intToByteArray(pointer);
        return new KarelPacket(
                connectionId,
                new SequenceNumber(0x0, 0x0),
                new AcknowledgeNumber(acknowledge[2], acknowledge[3]),
                flag,
                new PacketData(new byte[]{})
        );
    }
}

/**
 * Exception which is thrown when the program finds out that incoming packet is corrupted.
 */
class CorruptedPacketException extends Exception {
    public CorruptedPacketException(String s) {
        super(s);
    }
}
