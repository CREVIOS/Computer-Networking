import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.CRC32;

public class TcpClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 12345;
    private static final int CLIENT_WINDOW_SIZE = 65536; // 64KB
    private static final int CLIENT_MSS = 1460;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int negotiatedMSS = CLIENT_MSS;
    private int expectedSequenceNumber = 1;
    private final NavigableMap<Integer, ReceivedSegment> receivedBuffer = new TreeMap<>();
    private final ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
    private int currentWindowSize = CLIENT_WINDOW_SIZE;
    private int lastAckSent = 0;
    private int duplicateAckCount = 0;

    // Statistics
    private int totalDataPacketsReceived = 0;
    private int duplicatePacketsReceived = 0;
    private int outOfOrderPacketsReceived = 0;
    private long totalBytesReceived = 0;
    private int totalAcksSent = 0;
    private final Random random = new Random();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== TCP Client ===");
        System.out.print("Enter filename to request from server: ");
        String filename = scanner.nextLine().trim();

        if (filename.isEmpty()) {
            filename = "test.txt";
            System.out.println("No filename entered. Using default: " + filename);
        }

        scanner.close();

        try {
            TcpClient client = new TcpClient();
            client.connectAndRequestFile(filename);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void connectAndRequestFile(String filename) throws IOException {
        System.out.println("Connecting to " + SERVER_HOST + ":" + SERVER_PORT + "...");

        socket = new Socket(SERVER_HOST, SERVER_PORT);
        socket.setSoTimeout(10000); // 10 second timeout
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        System.out.println("Connected successfully!");

        try {
            performHandshake();
            requestFile(filename);
            receiveFile(filename);
        } finally {
            cleanup();
        }
    }

    private void performHandshake() throws IOException {
        System.out.println("\n=== TCP Handshake ===");

        // SYN
        Packet syn = new Packet(1000, 0, Packet.SYN, currentWindowSize, 0, new byte[0], null);
        syn.mssOption = CLIENT_MSS;
        sendPacket(syn, "SYN");

        // SYN-ACK
        String synAckMessage = in.readUTF();
        Packet synAck = Packet.decode(synAckMessage);
        if (!synAck.flag.equals(Packet.orFlags(Packet.SYN, Packet.ACK))) {
            throw new IOException("Handshake failed: Expected SYN-ACK from server.");
        }

        if (synAck.mssOption > 0) {
            negotiatedMSS = Math.min(CLIENT_MSS, synAck.mssOption);
            System.out.println("Received SYN-ACK with Server MSS=" + synAck.mssOption +
                    ", Negotiated MSS=" + negotiatedMSS);
        }

        // ACK
        Packet ack = new Packet(1001, synAck.sequenceNumber + 1, Packet.ACK,
                currentWindowSize, 0, new byte[0], null);
        sendPacket(ack, "ACK");

        System.out.println("Handshake completed successfully.\n");
    }

    private void requestFile(String filename) throws IOException {
        System.out.println("=== File Request ===");
        System.out.println("Requesting file: " + filename);

        byte[] filenameBytes = filename.getBytes();
        Packet request = new Packet(2000, 0, Packet.DATA, currentWindowSize,
                filenameBytes.length, filenameBytes, null);
        sendPacket(request, "FILE_REQUEST");

        String ackMessage = in.readUTF();
        Packet ack = Packet.decode(ackMessage);
        System.out.println("Server acknowledged file request (ACK=" + ack.acknowledgementNumber + ")\n");
    }

    private void receiveFile(String filename) throws IOException {
        System.out.println("=== File Transfer ===");
        long startTime = System.currentTimeMillis();
        boolean transferComplete = false;
        expectedSequenceNumber = 1;
        lastAckSent = 0;

        System.out.println("Starting file transfer...");
        System.out.println("Expected initial sequence: " + expectedSequenceNumber);
        System.out.println("Window size: " + currentWindowSize + " bytes");
        System.out.println("MSS: " + negotiatedMSS + " bytes\n");

        while (!transferComplete) {
            try {
                String packetMessage = in.readUTF();
                Packet dataPacket = Packet.decode(packetMessage);

                if (dataPacket == null) {
                    System.err.println("[ERROR] Failed to decode packet, skipping...");
                    continue;
                }

                totalDataPacketsReceived++;

                // Check for FIN flag (end of transfer)
                if (dataPacket.flag.endsWith("1")) { // FIN bit set
                    System.out.println("[FIN] Received final packet seq=" + dataPacket.sequenceNumber);

                    // Process any remaining data
                    if (dataPacket.payloadLength > 0 && dataPacket.sequenceNumber == expectedSequenceNumber) {
                        processInOrderSegment(dataPacket);
                        processBufferedSegments();
                    }

                    sendAck(expectedSequenceNumber + dataPacket.payloadLength, "FIN-ACK");
                    transferComplete = true;
                    break;
                }

                System.out.println("Received seq=" + dataPacket.sequenceNumber +
                        " len=" + dataPacket.payloadLength +
                        " expected=" + expectedSequenceNumber);

                if (dataPacket.sequenceNumber == expectedSequenceNumber) {
                    // In-order packet
                    handleInOrderPacket(dataPacket);
                } else if (dataPacket.sequenceNumber > expectedSequenceNumber) {
                    // Out-of-order packet (gap detected)
                    handleOutOfOrderPacket(dataPacket);
                } else {
                    // Duplicate packet
                    handleDuplicatePacket(dataPacket);
                }

            } catch (SocketTimeoutException e) {
                System.err.println("[TIMEOUT] No data from server for 10 seconds");
                transferComplete = true;
            } catch (EOFException e) {
                System.out.println("[EOF] Server closed connection. Transfer finished.");
                transferComplete = true;
            } catch (IOException e) {
                System.err.println("[ERROR] IO Error during transfer: " + e.getMessage());
                transferComplete = true;
            }
        }

        long endTime = System.currentTimeMillis();
        double transferTime = (endTime - startTime) / 1000.0;
        printTransferStats(transferTime);
        saveReceivedFile(filename);
    }

    private void handleInOrderPacket(Packet packet) throws IOException {
        System.out.println("  [IN-ORDER] Processing seq=" + packet.sequenceNumber);

        processInOrderSegment(packet);
        processBufferedSegments();

        // Send ACK for cumulative data
        sendAck(expectedSequenceNumber, "DATA-ACK");
        lastAckSent = expectedSequenceNumber;
        duplicateAckCount = 0;
    }

    private void handleOutOfOrderPacket(Packet packet) throws IOException {
        outOfOrderPacketsReceived++;

        if (receivedBuffer.containsKey(packet.sequenceNumber)) {
            // Duplicate out-of-order packet
            duplicatePacketsReceived++;
            System.out.println("  [DUPLICATE-OOO] Already buffered seq=" + packet.sequenceNumber);
        } else {
            // New out-of-order packet
            receivedBuffer.put(packet.sequenceNumber,
                    new ReceivedSegment(packet, System.currentTimeMillis()));
            System.out.println("  [OUT-OF-ORDER] Buffered seq=" + packet.sequenceNumber +
                    " (buffer size: " + receivedBuffer.size() + ")");
        }

        // Send duplicate ACK to trigger fast retransmit
        sendDuplicateAck();
    }

    private void handleDuplicatePacket(Packet packet) throws IOException {
        duplicatePacketsReceived++;
        System.out.println("  [DUPLICATE] Old seq=" + packet.sequenceNumber +
                " (expected=" + expectedSequenceNumber + ")");

        // Send duplicate ACK
        sendDuplicateAck();
    }

    private void sendDuplicateAck() throws IOException {
        duplicateAckCount++;
        sendAck(lastAckSent, "DUP-ACK #" + duplicateAckCount);

        if (duplicateAckCount == 3) {
            System.out.println("  [FAST-RETRANSMIT] Triggered by 3 duplicate ACKs");
        }
    }

    private void processInOrderSegment(Packet segment) {
        if (segment.payload != null && segment.payloadLength > 0) {
            fileBuffer.write(segment.payload, 0, segment.payloadLength);
            totalBytesReceived += segment.payloadLength;
        }
        expectedSequenceNumber = segment.sequenceNumber + segment.payloadLength;
        System.out.println("    [PROCESSED] Seq=" + segment.sequenceNumber +
                " -> Next expected=" + expectedSequenceNumber);
    }

    private void processBufferedSegments() {
        int processedSegments = 0;
        while (receivedBuffer.containsKey(expectedSequenceNumber)) {
            ReceivedSegment bufferedSegment = receivedBuffer.remove(expectedSequenceNumber);
            System.out.println("    [BUFFER] Processing buffered seq=" + expectedSequenceNumber);
            processInOrderSegment(bufferedSegment.packet);
            processedSegments++;
        }
        if (processedSegments > 0) {
            System.out.println("    [INFO] Processed " + processedSegments + " segments from buffer");
        }
    }

    private void sendAck(int ackNumber, String type) throws IOException {
        // Calculate current window size (simple flow control)
        int availableWindow = Math.max(currentWindowSize - receivedBuffer.size() * negotiatedMSS,
                negotiatedMSS);

        Packet ack = new Packet(3000 + totalAcksSent, ackNumber, Packet.ACK,
                availableWindow, 0, new byte[0], null);

        // Add SACK blocks if we have out-of-order segments
        if (!receivedBuffer.isEmpty()) {
            List<SackBlock> sackBlocks = generateSackBlocks();
            ack.sackBlocks = sackBlocks;
        }

        sendPacket(ack, type + " (ACK=" + ackNumber + ", win=" + availableWindow + ")");
        totalAcksSent++;
    }

    private List<SackBlock> generateSackBlocks() {
        List<SackBlock> sackBlocks = new ArrayList<>();

        // Find contiguous blocks in the received buffer
        Integer start = null;
        Integer prev = null;

        for (Integer seq : receivedBuffer.keySet()) {
            if (start == null) {
                start = seq;
                prev = seq;
            } else if (seq == prev + receivedBuffer.get(prev).packet.payloadLength) {
                prev = seq;
            } else {
                // End of contiguous block
                sackBlocks.add(new SackBlock(start, prev + receivedBuffer.get(prev).packet.payloadLength));
                start = seq;
                prev = seq;
            }
        }

        // Add final block
        if (start != null) {
            sackBlocks.add(new SackBlock(start, prev + receivedBuffer.get(prev).packet.payloadLength));
        }

        return sackBlocks;
    }

    private void sendPacket(Packet packet, String description) throws IOException {
        out.writeUTF(packet.encode());
        out.flush();
        System.out.println("-> Sent " + description);
    }

    private void saveReceivedFile(String originalFilename) {
        if (fileBuffer.size() == 0) {
            System.out.println("No data received or file was empty, nothing to save.");
            return;
        }

        // Check if it's an error message
        String potentialError = new String(fileBuffer.toByteArray());
        if (potentialError.startsWith("File not found") || potentialError.startsWith("Error:")) {
            System.err.println("Server Response: " + potentialError);
            return;
        }

        byte[] fileData = fileBuffer.toByteArray();
        String outputFilename = "received_" + originalFilename;
        try {
            Files.write(Paths.get(outputFilename), fileData);
            System.out.println("\n=== File Saved ===");
            System.out.println("File: " + outputFilename + " (" + fileData.length + " bytes)");
            System.out.println("Location: " + Paths.get(outputFilename).toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error saving file: " + e.getMessage());
        }
    }

    private void printTransferStats(double transferTime) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("                       TRANSFER STATISTICS");
        System.out.println("=".repeat(70));
        System.out.printf("Transfer Time:        %.2f seconds%n", transferTime);
        System.out.println("Total Bytes Received: " + totalBytesReceived);
        System.out.printf("Throughput:           %.2f KB/s%n", (totalBytesReceived / 1024.0) / transferTime);
        System.out.println("Total Packets Received: " + totalDataPacketsReceived);
        System.out.println("Duplicate Packets:     " + duplicatePacketsReceived);
        System.out.println("Out-of-Order Packets:  " + outOfOrderPacketsReceived);
        System.out.println("Total ACKs Sent:       " + totalAcksSent);

        if (totalDataPacketsReceived > 0) {
            double efficiency = ((double) (totalDataPacketsReceived - duplicatePacketsReceived)
                    / totalDataPacketsReceived) * 100;
            System.out.printf("Packet Efficiency:     %.1f%%%n", efficiency);
        }
        System.out.println("Negotiated MSS:        " + negotiatedMSS + " bytes");
        System.out.println("Window Size:           " + currentWindowSize + " bytes");
        System.out.println("=".repeat(70));
    }

    private void cleanup() {
        try {
            if (in != null)
                in.close();
            if (out != null)
                out.close();
            if (socket != null && !socket.isClosed())
                socket.close();
            System.out.println("\nConnection closed.");
        } catch (IOException e) {
            System.err.println("Error closing client resources: " + e.getMessage());
        }
    }

    static class ReceivedSegment {
        Packet packet;
        long receiveTime;

        public ReceivedSegment(Packet p, long t) {
            packet = p;
            receiveTime = t;
        }
    }

    static class Packet {
        int sequenceNumber;
        int acknowledgementNumber;
        String flag;
        int windowSize;
        int payloadLength;
        byte[] payload;
        long timestamp;
        long checksum;
        List<SackBlock> sackBlocks;
        int mssOption = 0;

        public static final String SYN = "1000";
        public static final String ACK = "0100";
        public static final String DATA = "0010";
        public static final String FIN = "0001";

        public Packet(int sequenceNumber, int acknowledgementNumber, String flag,
                int windowSize, int payloadLength, byte[] payload, List<SackBlock> sacks) {
            this.sequenceNumber = sequenceNumber;
            this.acknowledgementNumber = acknowledgementNumber;
            this.flag = flag;
            this.windowSize = windowSize;
            this.payload = payload;
            this.payloadLength = payloadLength;
            this.timestamp = System.currentTimeMillis();
            this.sackBlocks = sacks;
            this.checksum = calculateChecksum();
        }

        private long calculateChecksum() {
            CRC32 crc = new CRC32();
            crc.update(intToBytes(sequenceNumber));
            crc.update(intToBytes(acknowledgementNumber));
            crc.update(flag.getBytes());
            crc.update(intToBytes(windowSize));
            crc.update(intToBytes(payloadLength));
            if (payload != null && payloadLength > 0) {
                crc.update(payload, 0, payloadLength);
            }
            return crc.getValue();
        }

        private byte[] intToBytes(int value) {
            return new byte[] {
                    (byte) (value >>> 24),
                    (byte) (value >>> 16),
                    (byte) (value >>> 8),
                    (byte) value
            };
        }

        public String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(sequenceNumber).append(" ")
                    .append(acknowledgementNumber).append(" ")
                    .append(flag).append(" ")
                    .append(windowSize).append(" ")
                    .append(payloadLength).append(" ")
                    .append(timestamp).append(" ")
                    .append(checksum).append(" ");

            if (mssOption > 0) {
                sb.append("MSS ").append(mssOption).append(" ");
            }

            if (sackBlocks != null && !sackBlocks.isEmpty()) {
                sb.append("SACK ").append(sackBlocks.size()).append(" ");
                for (SackBlock b : sackBlocks) {
                    sb.append(b.start).append(" ").append(b.end).append(" ");
                }
            }

            if (payload != null && payloadLength > 0) {
                for (int i = 0; i < payloadLength; i++) {
                    sb.append((int) payload[i]).append(" ");
                }
            }
            return sb.toString().trim();
        }

        public static Packet decode(String str) {
            try {
                String[] parts = str.split(" ");
                int sequenceNumber = Integer.parseInt(parts[0]);
                int acknowledgementNumber = Integer.parseInt(parts[1]);
                String flag = parts[2];
                int windowSize = Integer.parseInt(parts[3]);
                int payloadLength = Integer.parseInt(parts[4]);
                long timestamp = Long.parseLong(parts[5]);
                long checksum = Long.parseLong(parts[6]);

                List<SackBlock> sackBlocks = null;
                int mssOption = 0;
                int idx = 7;

                // Parse options
                while (idx < parts.length) {
                    String option = parts[idx++];
                    if ("MSS".equals(option) && idx < parts.length) {
                        mssOption = Integer.parseInt(parts[idx++]);
                    } else if ("SACK".equals(option) && idx < parts.length) {
                        int count = Integer.parseInt(parts[idx++]);
                        sackBlocks = new ArrayList<>();
                        for (int i = 0; i < count && idx < parts.length - 1; i++) {
                            int start = Integer.parseInt(parts[idx++]);
                            int end = Integer.parseInt(parts[idx++]);
                            sackBlocks.add(new SackBlock(start, end));
                        }
                    } else {
                        idx--;
                        break;
                    }
                }

                byte[] payload = null;
                if (payloadLength > 0) {
                    payload = new byte[payloadLength];
                    for (int i = 0; i < payloadLength && (idx + i) < parts.length; i++) {
                        payload[i] = (byte) Integer.parseInt(parts[idx + i]);
                    }
                } else {
                    payload = new byte[0];
                }

                Packet packet = new Packet(sequenceNumber, acknowledgementNumber, flag,
                        windowSize, payloadLength, payload, sackBlocks);
                packet.timestamp = timestamp;
                packet.checksum = checksum;
                packet.mssOption = mssOption;
                return packet;
            } catch (Exception e) {
                System.err.println("[ERROR] Failed to decode packet: " + e.getMessage());
                return null;
            }
        }

        public static String orFlags(String... flags) {
            int result = 0;
            for (String flag : flags) {
                result |= Integer.parseInt(flag, 2);
            }
            return String.format("%4s", Integer.toBinaryString(result)).replace(' ', '0');
        }
    }

    static class SackBlock {
        int start, end;

        public SackBlock(int s, int e) {
            start = s;
            end = e;
        }

        @Override
        public String toString() {
            return "[" + start + "-" + end + ")";
        }
    }
}