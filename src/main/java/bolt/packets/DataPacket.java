package bolt.packets;

import bolt.BoltPacket;
import bolt.BoltSession;
import bolt.util.SequenceNumber;

import java.util.Arrays;
import java.util.Objects;

/**
 * The data packet header structure is as following:
 * <p>
 * <pre>
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0|M|R|O|              Packet Sequence Number                   | TODO change M|R fields to 3 bit delivery type
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |     Destination Socket ID     |            Class ID           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Reliability Sequence Number  |     Order Sequence Number     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |F|    Message Chunk Number     |           Message ID          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 * The data packet header starts with 0.
 * <p>
 * M signifies whether the packet is a message.
 * <p>
 * R signifies whether delivery is reliable.
 * <p>
 * Packet sequence number uses the following 29 bits after the flag bits.
 * Bolt uses packet-based sequencing, i.e., the sequence number is increased
 * by 1 for each sent data packet in the order of packet sending. Sequence
 * number is wrapped after it is increased to the maximum number (2^29 - 1).
 * <p>
 * Following is the 16-bit Destination Socket ID.
 * The Destination ID is used for UDP multiplexer. Multiple Bolt socket
 * can be bound on the same UDP port and this Bolt socket ID is used to
 * differentiate the Bolt connections.
 * <p>
 * The 16-bit Class ID is used to identify the type of packet being received.
 * An ID of 0 means the packet is not class-ful - it just contains raw data.
 * <p>
 * The next 16-bit field is the reliability order number. This field only exists
 * if the R flag is set to 1.
 * <p>
 * The next 16-bit field is the packet order number. This field only exists
 * if the O flag is set to 1. The packet should be received and processed in
 * this order.
 * <p>
 * The next 32-bit field in the header is for the messaging. This field only
 * exists if the M header flag is set to 1. The first bit "F" flags whether
 * the packet is the last message chunk (1), or not (0).
 * The Message Chunk Number is the position of this packet in the message.
 * The Message ID uniquely identifies this message from others.
 */
public class DataPacket implements BoltPacket, Comparable<BoltPacket> {

    private byte[] data;

    private DeliveryType delivery;

    private int packetSeqNumber;

    private int destinationID;

    private int classID;

    private boolean finalMessageChunk;

    private int messageChunkNumber;

    private int messageId;

    private int orderSeqNumber;

    private int reliabilitySeqNumber;

    private int dataLength;

    private BoltSession session;

    public DataPacket() {
    }

    /**
     * Create a DataPacket from the given raw data.
     *
     * @param encodedData all bytes of packet including headers.
     */
    public DataPacket(byte[] encodedData) {
        this(encodedData, encodedData.length);
    }

    public DataPacket(byte[] encodedData, int length) {
        decode(encodedData, length);
    }

    void decode(final byte[] encodedData, final int length) {
        byte deliveryType = (byte) PacketUtil.decodeInt(encodedData, 0, 28, 31);
        delivery = DeliveryType.fromId(deliveryType);

        packetSeqNumber = PacketUtil.decodeInt(encodedData, 0) & SequenceNumber.MAX_PACKET_SEQ_NUM;
        destinationID = PacketUtil.decodeInt(encodedData, 4, 16, 32);
        classID = PacketUtil.decodeInt(encodedData, 4, 0, 16);

        final int headerLength = DataPacket.computeHeaderLength(delivery);
        if (delivery.isReliable()) {
            reliabilitySeqNumber = PacketUtil.decodeShort(encodedData, 8);
        }
        if (delivery.isOrdered()) {
            orderSeqNumber = PacketUtil.decodeShort(encodedData, 10);
        }
        // If is message.
        if (delivery.isMessage()) {
            final int headerPos = delivery.isOrdered() ? 12 : 10;
            finalMessageChunk = PacketUtil.isBitSet(encodedData[headerPos], 7);
            messageChunkNumber = PacketUtil.decodeInt(encodedData, headerPos, 16, 31);
            messageId = PacketUtil.decodeInt(encodedData, headerPos, 0, 16);
        }

        dataLength = length - headerLength;
        data = new byte[dataLength];
        System.arraycopy(encodedData, headerLength, data, 0, dataLength);
    }

    public static int computeHeaderLength(final DeliveryType deliveryType) {
        return 8
                + (deliveryType.isMessage()  ? 4 : 0)
                + (deliveryType.isOrdered()  ? 2 : 0)
                + (deliveryType.isReliable() ? 2 : 0);
    }

    public byte[] getData() {
        return this.data;
    }

    public void setData(byte[] data) {
        this.data = data;
        this.dataLength = data.length;
    }

    public int getLength() {
        return dataLength;
    }

    public void setLength(int length) {
        dataLength = length;
    }

    public int getPacketSeqNumber() {
        return this.packetSeqNumber;
    }

    public void setPacketSeqNumber(final int sequenceNumber) {
        this.packetSeqNumber = sequenceNumber;
    }


    public int getMessageId() {
        return this.messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public int getDestinationID() {
        return this.destinationID;
    }

    public void setDestinationID(int destinationID) {
        this.destinationID = destinationID;
    }

    public int getOrderSeqNumber()
    {
        return orderSeqNumber;
    }

    public void setOrderSeqNumber(int orderSeqNumber)
    {
        this.orderSeqNumber = orderSeqNumber;
    }

    public int getReliabilitySeqNumber() {
        return reliabilitySeqNumber;
    }

    public void setReliabilitySeqNumber(int reliabilitySeqNumber) {
        this.reliabilitySeqNumber = reliabilitySeqNumber;
    }

    /**
     * Complete header (8 - 14 bytes) + data packet for transmission
     */
    public byte[] getEncoded() {
        final int headerLength = DataPacket.computeHeaderLength(delivery);
        byte[] result = new byte[headerLength + dataLength];

        byte[] flagsAndSeqNum = PacketUtil.encodeInt(packetSeqNumber);
        flagsAndSeqNum[0] = PacketUtil.setBit(flagsAndSeqNum[0], 7, false);

        final byte delId = delivery.getId();
        flagsAndSeqNum[0] = PacketUtil.setBit(flagsAndSeqNum[0], 6, ((delId >> 2) & 1) == 1);
        flagsAndSeqNum[0] = PacketUtil.setBit(flagsAndSeqNum[0], 5, ((delId >> 1) & 1) == 1);
        flagsAndSeqNum[0] = PacketUtil.setBit(flagsAndSeqNum[0], 4, (delId & 1) == 1);

        System.arraycopy(flagsAndSeqNum, 0, result, 0, 4);
        System.arraycopy(PacketUtil.encode(destinationID), 2, result, 4, 2);
        System.arraycopy(PacketUtil.encode(classID), 2, result, 6, 2);

        if (delivery.isReliable()) {
            final byte[] reliabilitySeqNumBits = PacketUtil.encodeShort(reliabilitySeqNumber);
            System.arraycopy(reliabilitySeqNumBits, 0, result, 8, 2);
        }
        if (delivery.isOrdered()) {
            final byte[] orderSeqNumBits = PacketUtil.encodeShort(orderSeqNumber);
            System.arraycopy(orderSeqNumBits, 0, result, 10, 2);
        }
        if (delivery.isMessage()) {
            final byte[] messageBits = new byte[4];
            messageBits[0] = PacketUtil.setBit(messageBits[0], 7, finalMessageChunk);
            PacketUtil.encodeMapToBytes(messageChunkNumber, messageBits, 15, 15);
            PacketUtil.encodeMapToBytes(messageId, messageBits, 31, 16);
            System.arraycopy(messageBits, 0, result, headerLength - 4, 4);
        }
        System.arraycopy(data, 0, result, headerLength, dataLength);
        return result;
    }

    public void copyFrom(final DataPacket src) {
        setClassID(src.getClassID());
        setPacketSeqNumber(src.getPacketSeqNumber());
        setSession(src.getSession());
        setDestinationID(src.getDestinationID());

        setData(src.getData());
        setDelivery(src.getDelivery());
        setFinalMessageChunk(src.isFinalMessageChunk());
        setMessageChunkNumber(src.getMessageChunkNumber());
        setMessageId(src.getMessageId());
        setOrderSeqNumber(src.getOrderSeqNumber());
        setReliabilitySeqNumber(src.getReliabilitySeqNumber());
    }

    public int getClassID() {
        return classID;
    }

    public void setClassID(int classID) {
        this.classID = classID;
    }

    public boolean isControlPacket() {
        return false;
    }

    public boolean forSender() {
        return false;
    }

    public boolean isConnectionHandshake() {
        return false;
    }

    public boolean isReliable() {
        return delivery.isReliable();
    }

    public boolean isOrdered() {
        return delivery.isOrdered();
    }

    public void setDelivery(DeliveryType delivery)
    {
        this.delivery = delivery;
    }

    public DeliveryType getDelivery()
    {
        return delivery;
    }

    public boolean isMessage() {
        return delivery.isMessage();
    }

    public int getMessageChunkNumber() {
        return messageChunkNumber;
    }

    public void setMessageChunkNumber(int messageChunkNumber) {
        this.messageChunkNumber = messageChunkNumber;
    }

    public boolean isFinalMessageChunk() {
        return finalMessageChunk;
    }

    public void setFinalMessageChunk(boolean finalMessageChunk) {
        this.finalMessageChunk = finalMessageChunk;
    }

    public int getControlPacketType() {
        return -1;
    }

    public BoltSession getSession() {
        return session;
    }

    public void setSession(BoltSession session) {
        this.session = session;
    }

    public int compareTo(BoltPacket other) {
        return (getPacketSeqNumber() - other.getPacketSeqNumber());
    }

    public boolean isClassful() {
        return classID > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataPacket that = (DataPacket) o;
        return delivery == that.delivery&&
                packetSeqNumber == that.packetSeqNumber &&
                finalMessageChunk == that.finalMessageChunk &&
                messageChunkNumber == that.messageChunkNumber &&
                messageId == that.messageId &&
                destinationID == that.destinationID &&
                classID == that.classID &&
                orderSeqNumber == that.orderSeqNumber &&
                reliabilitySeqNumber == that.reliabilitySeqNumber &&
                Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data, delivery, packetSeqNumber, finalMessageChunk, messageChunkNumber, messageId, destinationID, orderSeqNumber, reliabilitySeqNumber, classID);
    }

    @Override
    public String toString() {
        return "DataPacket{" +
                "classID=" + classID +
                ", delivery=" + delivery +
                ", packetSeqNumber=" + packetSeqNumber +
                ", finalMessageChunk=" + finalMessageChunk +
                ", messageChunkNumber=" + messageChunkNumber +
                ", messageId=" + messageId +
                ", destinationID=" + destinationID +
                ", orderSeqNumber=" + orderSeqNumber +
                ", reliabilitySeqNumber=" + reliabilitySeqNumber +
                ", data=" + Arrays.toString(data) +
                '}';
    }
}
