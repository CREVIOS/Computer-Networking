import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

class Packet {
    int sequenceNumber;
    int acknowledgementNumber;
    String flag;
    int windowSize;
    int payloadLength;
    byte[] payload;
    long timestamp;
    long checksum;

    public static final String SYN = "1000";
    public static final String ACK = "0100";
    public static final String DATA = "0010";
    public static final String FIN = "0001";

    public Packet(int sequenceNumber, int acknowledgementNumber, String flag,
            int windowSize, int payloadLength, byte[] payload) {
        this.sequenceNumber = sequenceNumber;
        this.acknowledgementNumber = acknowledgementNumber;
        this.flag = flag;
        this.windowSize = windowSize;
        this.payload = payload;
        this.payloadLength = payloadLength;
        this.timestamp = System.currentTimeMillis();
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

    public boolean verifyChecksum() {
        long originalChecksum = this.checksum;
        long calculatedChecksum = calculateChecksum();
        return originalChecksum == calculatedChecksum;
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

        if (payload != null && payloadLength > 0) {
            for (int i = 0; i < payloadLength; i++) {
                sb.append((int) payload[i]).append(" ");
            }
        }
        return sb.toString().trim();
    }

    public static Packet decode(String str) {
        String[] parts = str.split(" ");
        int sequenceNumber = Integer.parseInt(parts[0]);
        int acknowledgementNumber = Integer.parseInt(parts[1]);
        String flag = parts[2];
        int windowSize = Integer.parseInt(parts[3]);
        int payloadLength = Integer.parseInt(parts[4]);
        long timestamp = Long.parseLong(parts[5]);
        long checksum = Long.parseLong(parts[6]);

        byte[] payload = null;
        if (payloadLength > 0) {
            payload = new byte[payloadLength];
            for (int i = 0; i < payloadLength && (7 + i) < parts.length; i++) {
                payload[i] = (byte) Integer.parseInt(parts[7 + i]);
            }
        } else {
            payload = new byte[0];
        }

        Packet packet = new Packet(sequenceNumber, acknowledgementNumber, flag,
                windowSize, payloadLength, payload);
        packet.timestamp = timestamp;
        packet.checksum = checksum;
        return packet;
    }

    public static String orFlags(String... flags) {
        int result = 0;
        for (String flag : flags) {
            result |= Integer.parseInt(flag, 2);
        }
        return String.format("%4s", Integer.toBinaryString(result)).replace(' ', '0');
    }
}

public class rdt_receiver {
    private static final String SERVER_ADDRESS = "10.33.29.30";
    private static final int SERVER_PORT = 4374;
    private static final String RECEIVED_FILES_DIR = "ReceivedFiles";
    private static final int CONNECTION_TIMEOUT = 5000;

    private static final double ACK_FORGET_RATE = 0.001;
    private static final Random random = new Random();

    private static final int MAX_RECEIVE_BUFFER = 20;
    private static final int INITIAL_WINDOW_SIZE = 10;
    private static final int MIN_WINDOW_SIZE = 1;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    private int totalPacketsReceived = 0;
    private int totalPacketsLost = 0;
    private int totalPacketsCorrupted = 0;
    private int totalAcksSent = 0;
    private int duplicateAcksSent = 0;
    private int outOfOrderPackets = 0;
    private int forgottenAcks = 0;

    private int currentReceiveWindow;
    private int bufferedPackets;

    private int duplicatePacketCount = 0;

    private String requestedFileName;

    public static void main(String[] args) {
        new rdt_receiver().start();
    }

    public void start() {
        try {
            System.out.println("Connecting to sender at " + SERVER_ADDRESS + ":" + SERVER_PORT);

            File receivedDir = new File(RECEIVED_FILES_DIR);
            if (!receivedDir.exists()) {
                receivedDir.mkdir();
                System.out.println("Created directory: " + RECEIVED_FILES_DIR);
            }

            currentReceiveWindow = INITIAL_WINDOW_SIZE;
            bufferedPackets = 0;

            establishConnection();

            if (requestFile()) {
                receiveFile();
            }

            closeConnection();

            printStatistics();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void establishConnection() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT), CONNECTION_TIMEOUT);

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);

        System.out.println("Connected to sender!");

        Packet synPacket = new Packet(0, 0, Packet.SYN, currentReceiveWindow, 0, null);
        sendPacket(synPacket);
        System.out.println("Sent SYN (Initial RcvWnd: " + currentReceiveWindow + ")");

        String response = reader.readLine();
        if (response != null) {
            Packet synAckPacket = Packet.decode(response);
            if (synAckPacket.flag.equals(Packet.orFlags(Packet.SYN, Packet.ACK))) {
                System.out.println("Received SYN-ACK (Sender Window: " + synAckPacket.windowSize + ")");

                Packet ackPacket = new Packet(1, synAckPacket.sequenceNumber + 1,
                        Packet.ACK, currentReceiveWindow, 0, null);
                sendPacket(ackPacket);
                System.out.println("Connection established! (Receiver RcvWnd: " + currentReceiveWindow + ")\n");
            }
        }
    }

    private boolean requestFile() throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter the filename you want to download: ");
        String fileName = scanner.nextLine().trim();

        if (fileName.isEmpty()) {
            System.out.println("Error: Filename cannot be empty");
            return false;
        }

        System.out.println("Requesting file: " + fileName);

        requestedFileName = fileName;

        byte[] fileNameBytes = fileName.getBytes();
        Packet fileRequestPacket = new Packet(1, 0, Packet.DATA, currentReceiveWindow,
                fileNameBytes.length, fileNameBytes);
        sendPacket(fileRequestPacket);
        System.out.println("File request sent (RcvWnd: " + currentReceiveWindow + ")");

        String response = reader.readLine();
        if (response == null) {
            System.out.println("Error: No response from sender");
            return false;
        }

        Packet responsePacket = Packet.decode(response);

        if (!responsePacket.verifyChecksum()) {
            System.out.println("Error: Sender response corrupted");
            return false;
        }

        if (responsePacket.payload == null || responsePacket.payloadLength == 0) {
            System.out.println("Error: Empty response from sender");
            return false;
        }

        String serverResponse = new String(responsePacket.payload, 0, responsePacket.payloadLength);
        System.out.println("Sender response: " + serverResponse);

        if (serverResponse.startsWith("OK:")) {
            System.out.println("File found on sender. Starting download...");
            System.out.println("Dynamic Receive Window: Max=" + MAX_RECEIVE_BUFFER +
                    ", Current=" + currentReceiveWindow + "\n");
            return true;
        } else if (serverResponse.startsWith("ERROR:")) {
            System.out.println("Sender error: " + serverResponse);
            return false;
        } else {
            System.out.println("Unknown sender response: " + serverResponse);
            return false;
        }
    }

    private int calculateReceiveWindow() {
        int availableBuffer = MAX_RECEIVE_BUFFER - bufferedPackets;

        int newWindow = Math.max(availableBuffer, (bufferedPackets < MAX_RECEIVE_BUFFER) ? MIN_WINDOW_SIZE : 0);

        return newWindow;
    }

    private void updateReceiveWindow() {
        int oldWindow = currentReceiveWindow;
        currentReceiveWindow = calculateReceiveWindow();

        if (oldWindow != currentReceiveWindow) {
            System.out.println("    Window updated: " + oldWindow + " -> " + currentReceiveWindow +
                    " (Buffered: " + bufferedPackets + "/" + MAX_RECEIVE_BUFFER + ")");
        }
    }

    private boolean shouldForgetAck() {
        return random.nextDouble() < ACK_FORGET_RATE;
    }

    private void receiveFile() throws IOException {
        Path filePath = Paths.get(RECEIVED_FILES_DIR, requestedFileName);

        System.out.println("Receiving file: " + requestedFileName);
        System.out.println("ACK forget simulation: " + (ACK_FORGET_RATE * 100) + "%\n");

        try (FileOutputStream fileOutput = new FileOutputStream(filePath.toFile())) {
            Map<Integer, byte[]> receivedPackets = new TreeMap<>();
            Set<Integer> processedPackets = new HashSet<>();
            int expectedSeqNum = 1;
            int lastAcked = 0;
            boolean receivedFIN = false;

            while (!receivedFIN) {
                String packetData = reader.readLine();
                if (packetData == null)
                    break;

                Packet packet = Packet.decode(packetData);

                if (packet.flag.equals(Packet.FIN)) {
                    System.out.println("\n FIN packet received, ending data reception");
                    Packet finAckPacket = new Packet(0, 0, Packet.orFlags(Packet.FIN, Packet.ACK),
                            currentReceiveWindow, 0, null);
                    sendPacket(finAckPacket);
                    System.out.println("Sent FIN-ACK (Final RcvWnd: " + currentReceiveWindow + ")");
                    receivedFIN = true;
                    break;
                }

                totalPacketsReceived++;

                if (!packet.verifyChecksum()) {
                    System.out.println(" Packet " + packet.sequenceNumber + " checksum failed");
                    totalPacketsCorrupted++;
                    updateReceiveWindow();
                    if (!shouldForgetAck()) {
                        sendAck(lastAcked);
                    } else {
                        System.out.println(" Forgot to send ACK (simulated)");
                        forgottenAcks++;
                    }
                    continue;
                }

                System.out.println(" Received Packet " + packet.sequenceNumber +
                        " (" + packet.payloadLength + " bytes)");

                if (!processedPackets.contains(packet.sequenceNumber)) {
                    duplicatePacketCount = 0; 
                    if (bufferedPackets >= MAX_RECEIVE_BUFFER) {
                        System.out.println("    Buffer full! Dropping packet " + packet.sequenceNumber);
                        updateReceiveWindow();
                        if (!shouldForgetAck()) {
                            sendAck(lastAcked);
                        } else {
                            System.out.println("    Forgot to send ACK (simulated)");
                            forgottenAcks++;
                        }
                        continue;
                    }

                    receivedPackets.put(packet.sequenceNumber, packet.payload);
                    processedPackets.add(packet.sequenceNumber);
                    bufferedPackets++;

                    if (packet.sequenceNumber > expectedSeqNum) {
                        outOfOrderPackets++;
                        int lostPackets = packet.sequenceNumber - expectedSeqNum;
                        totalPacketsLost += lostPackets;
                        System.out.println("  Out-of-order packet (expected " + expectedSeqNum + 
                            ", received " + packet.sequenceNumber + ") - Detected " + lostPackets + 
                            " lost packet(s) - Buffered: " + bufferedPackets);
                    }

                    int packetsWritten = 0;
                    while (receivedPackets.containsKey(expectedSeqNum)) {
                        byte[] data = receivedPackets.get(expectedSeqNum);
                        fileOutput.write(data);
                        receivedPackets.remove(expectedSeqNum);
                        bufferedPackets--;
                        packetsWritten++;
                        expectedSeqNum++;
                    }

                    if (packetsWritten > 0) {
                        System.out.println(
                                "  Wrote " + packetsWritten + " packet(s) to file. Buffered: " + bufferedPackets);
                    }

                    int ackNum = expectedSeqNum - 1;
                    updateReceiveWindow();

                    if (!shouldForgetAck()) {
                        sendAck(ackNum);

                        if (ackNum > lastAcked) {
                            System.out
                                    .println(" Sent ACK " + ackNum + " (cumulative, RcvWnd: " + currentReceiveWindow
                                            + ")");
                            lastAcked = ackNum;
                        } else {
                            System.out.println(
                                    " Sent duplicate ACK " + ackNum + " (RcvWnd: " + currentReceiveWindow + ")");
                            duplicateAcksSent++;
                        }
                    } else {
                        System.out.println(" Forgot to send ACK " + ackNum + " (simulated)");
                        forgottenAcks++;
                    }
                } else {
                    duplicatePacketCount++;
                    System.out.println(" Duplicate packet" + packet.sequenceNumber +  "(already received) - Count: " + duplicatePacketCount);
                    updateReceiveWindow();

                    if (duplicatePacketCount >= 5 || !shouldForgetAck()) {
                        sendAck(lastAcked);
                        if (duplicatePacketCount >= 5) {
                            System.out.println(" Forced ACK due to excessive duplicates");
                            duplicatePacketCount = 0;
                        }
                    } else {
                        System.out.println(" Forgot to send ACK (simulated)");
                        forgottenAcks++;
                    }
                }
            }

            for (Map.Entry<Integer, byte[]> entry : receivedPackets.entrySet()) {
                fileOutput.write(entry.getValue());
                bufferedPackets--;
            }
        }

        System.out.println("\nFile saved to: " + filePath);
        System.out.println("File size: " + Files.size(filePath) + " bytes");
        System.out.println("Final buffer state: " + bufferedPackets + "/" + MAX_RECEIVE_BUFFER +
                " (Window: " + currentReceiveWindow + ")");
    }

    private void sendAck(int ackNum) {
        Packet ackPacket = new Packet(0, ackNum, Packet.ACK, currentReceiveWindow, 0, null);
        sendPacket(ackPacket);
        totalAcksSent++;
    }

    private void sendPacket(Packet packet) {
        writer.println(packet.encode());
        writer.flush();
    }

    private void closeConnection() throws IOException {
        System.out.println("\nClosing connection...");
        
        
        socket.setSoTimeout(2000);
        
        try {
            
            String finalAckData = reader.readLine();
            if (finalAckData != null) {
                Packet finalAck = Packet.decode(finalAckData);
                if (finalAck.flag.equals(Packet.ACK)) {
                    System.out.println("Received final ACK");
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("Timeout waiting for final ACK, closing anyway");
        } finally {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
            System.out.println("Connection closed");
        }
    }

    private void printStatistics() {
        System.out.println("\n=== Transfer Statistics ===");
        System.out.println("Total packets received: " + totalPacketsReceived);
        System.out.println("Packets lost: " + totalPacketsLost);
        System.out.println("Packets corrupted: " + totalPacketsCorrupted);
        System.out.println("Out-of-order packets: " + outOfOrderPackets);
        System.out.println("Total ACKs sent: " + totalAcksSent);
        System.out.println("Duplicate ACKs sent: " + duplicateAcksSent);
        System.out.println("Forgotten ACKs (simulated): " + forgottenAcks);

        double lossRate = totalPacketsReceived > 0
                ? (totalPacketsLost + totalPacketsCorrupted) * 100.0 / totalPacketsReceived
                : 0;
        System.out.println("Effective loss and corruption rate: " + String.format("%.1f", lossRate) + "%");

        // System.out.println("\n=== Flow Control Statistics ===");
        // System.out.println("Max receive buffer: " + MAX_RECEIVE_BUFFER + " packets");
        // System.out.println("Final receive window: " + currentReceiveWindow);
        // System.out.println("Final buffered packets: " + bufferedPackets);
    }
}