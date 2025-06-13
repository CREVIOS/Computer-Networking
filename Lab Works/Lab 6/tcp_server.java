import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
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

public class tcp_server {
    private static final int PORT = 4374;
    private static final String SERVER_FILE_DIR = "ServerFiles";
    private static final int PACKET_SIZE = 1024;
    private static final int DUPLICATE_ACK_THRESHOLD = 3;

    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Server is running on port " + PORT);
        System.out.println("Waiting for clients...\n");

        // Create server file directory if it doesn't exist
        File serverDir = new File(SERVER_FILE_DIR);
        if (!serverDir.exists()) {
            serverDir.mkdir();
            System.out.println("Created server directory: " + SERVER_FILE_DIR);
            System.out.println("Please place files to send in this directory.\n");
        }

        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected from " + clientSocket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler).start();
            } catch (IOException e) {
                System.err.println("Error accepting client: " + e.getMessage());
            }
        }
    }
}

class ClientHandler implements Runnable {
    private static final String SERVER_FILE_DIR = "ServerFiles";
    private static final int PACKET_SIZE = 1024;
    private static final int DUPLICATE_ACK_THRESHOLD = 3;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;
    private final Socket clientSocket;
    private final int clientId;
    private final int windowSize = 4;
    private BufferedReader reader;
    private PrintWriter writer;

    private final Object lock = new Object();
    private volatile boolean processingComplete = false;

    private Map<Integer, Packet> packetBuffer;
    private Map<Integer, Long> sendTimes;
    private Map<Integer, TimerTask> timeoutTasks;
    private Timer timer;

    private double estimatedRTT;
    private double devRTT;
    private double timeout; // RTO

    private static final boolean SIMULATE_PACKET_LOSS = true;
    private static final int PACKET_TO_LOSE = 4;

    public ClientHandler(Socket socket) {
        this.clientSocket = socket;
        this.clientId = socket.getPort();

        this.estimatedRTT = 100.0;
        this.devRTT = 25.0;
        this.timeout = calculateRTO();
    }

    private double calculateRTO() {
        double rto = this.estimatedRTT + 4 * this.devRTT;
        if (rto < 50)
            rto = 50;
        if (rto > 600)
            rto = 600;
        return rto;
    }

    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            writer = new PrintWriter(clientSocket.getOutputStream(), true);

            if (!performHandshake()) {
                System.out.println("Client #" + clientId + " - Handshake failed");
                return;
            }

            String fileName = selectFile();
            if (fileName == null) {
                System.out.println("No file selected or available for client " + clientSocket.getRemoteSocketAddress());
                return;
            }
            System.out.println("Client " + clientSocket.getRemoteSocketAddress() + " requested file: " + fileName);

            sendFile(fileName);

        } catch (Exception e) {
            System.err.println(
                    "Error in ClientHandler for " + clientSocket.getRemoteSocketAddress() + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (reader != null)
                    reader.close();
                if (writer != null)
                    writer.close();
                if (clientSocket != null && !clientSocket.isClosed())
                    clientSocket.close();
                System.out.println("Connection with " + clientSocket.getRemoteSocketAddress() + " closed.");
            } catch (IOException ioe) {
                System.err.println("Error closing resources for " + clientSocket.getRemoteSocketAddress() + ": "
                        + ioe.getMessage());
            }
            synchronized (lock) {
                processingComplete = true;
            }
            if (timer != null) {
                timer.cancel();
                timer.purge();
            }
        }
    }

    private boolean performHandshake() throws IOException {
        String synRequest = reader.readLine();
        if (synRequest == null)
            return false;

        Packet synPacket = Packet.decode(synRequest);
        if (!synPacket.flag.equals(Packet.SYN))
            return false;

        System.out.println("Client #" + clientId + " - Received SYN");

        Packet synAckPacket = new Packet(0, synPacket.sequenceNumber + 1,
                Packet.orFlags(Packet.SYN, Packet.ACK),
                windowSize, 0, null);
        sendPacket(synAckPacket);
        System.out.println("Client #" + clientId + " - Sent SYN-ACK");

        String ackRequest = reader.readLine();
        if (ackRequest == null)
            return false;

        Packet ackPacket = Packet.decode(ackRequest);
        if (!ackPacket.flag.equals(Packet.ACK))
            return false;

        System.out.println("Client #" + clientId + " - Connection established!\n");
        return true;
    }

    private String selectFile() {
        File serverDir = new File(SERVER_FILE_DIR);
        File[] files = serverDir.listFiles();

        if (files == null || files.length == 0) {
            System.out.println("No files available in ServerFiles directory.");
            return null;
        }
        File selectedFile = files[0];
        return selectedFile.getPath();
    }

    private void sendFile(String filePath) throws IOException {
        this.packetBuffer = new HashMap<>();
        this.sendTimes = new HashMap<>();
        this.timeoutTasks = new HashMap<>();
        this.timer = new Timer("ClientHandler-Timer-" + clientSocket.getPort(), true);
        this.processingComplete = false;
        this.estimatedRTT = 100.0;
        this.devRTT = 25.0;
        this.timeout = calculateRTO();

        byte[] fileContent = Files.readAllBytes(Paths.get(filePath));
        int totalPackets = (int) Math.ceil((double) fileContent.length / PACKET_SIZE);

        System.out.println("Sending file " + filePath + " (" + fileContent.length + " bytes, " + totalPackets
                + " packets) to " + clientSocket.getRemoteSocketAddress());

        int base = 1;
        int nextSeqNum = 1;

        int lastAckReceived = 0;
        int duplicateAckCount = 0;
        boolean packetLostSimulatedThisRun = false;

        while (base <= totalPackets) {
            synchronized (lock) {
                while (nextSeqNum < base + windowSize && nextSeqNum <= totalPackets) {
                    if (processingComplete)
                        break;

                    int offset = (nextSeqNum - 1) * PACKET_SIZE;
                    int length = Math.min(PACKET_SIZE, fileContent.length - offset);
                    byte[] data = new byte[length];
                    System.arraycopy(fileContent, offset, data, 0, length);

                    Packet packet = new Packet(nextSeqNum, 0, Packet.DATA, windowSize, length, data);
                    packetBuffer.put(nextSeqNum, packet);
                    sendTimes.put(nextSeqNum, System.currentTimeMillis());

                    final int currentSeqNum = nextSeqNum;
                    if (SIMULATE_PACKET_LOSS && currentSeqNum == PACKET_TO_LOSE && !packetLostSimulatedThisRun) {
                        System.out.println(
                                "Simulating loss of Packet " + currentSeqNum + " (Seq# " + currentSeqNum + ")");
                        packetLostSimulatedThisRun = true;
                    } else {
                        sendPacket(packet);
                        System.out.println("Sending Packet " + currentSeqNum + " with Seq# " +
                                currentSeqNum);
                    }

                    TimerTask timeoutTask = new TimerTask() {
                        @Override
                        public void run() {
                            handleTimeout(currentSeqNum);
                        }
                    };
                    try {
                        timer.schedule(timeoutTask, (long) this.timeout);
                        timeoutTasks.put(currentSeqNum, timeoutTask);
                    } catch (IllegalStateException e) {
                        if (processingComplete)
                            System.out.println("Timer cancelled, cannot schedule timeout for packet " + currentSeqNum);
                        else
                            throw e;
                    }
                    nextSeqNum++;
                }
            }

            if (processingComplete && base > totalPackets)
                break;

            try {
                clientSocket.setSoTimeout(Math.max(100, (int) (this.timeout * 1.5)));
                String ackResponse = reader.readLine();

                if (ackResponse != null) {
                    Packet ackPacket = Packet.decode(ackResponse);
                    if (!ackPacket.verifyChecksum()) {
                        System.out.println("Received corrupted ACK. Ignoring.");
                        continue;
                    }
                    int ackNum = ackPacket.acknowledgementNumber;
                    long receiveTime = System.currentTimeMillis();

                    synchronized (lock) {
                        if (processingComplete)
                            continue;

                        if (ackNum >= base) {
                            long sampleRTT = -1;

                            if (sendTimes.containsKey(ackNum)) {
                                sampleRTT = receiveTime - sendTimes.get(ackNum);
                            } else if (ackNum > 0 && sendTimes.containsKey(ackNum - windowSize + 1)
                                    && ackNum - windowSize + 1 > 0) {
                                // Heuristic: if it's an ACK for a window, estimate RTT from earliest packet in
                                // window
                                // This part is tricky; simple approach is just to use ACK for specific packets
                                // if possible
                                // For simplicity, we only update RTT if ackNum directly matches a sendTime.
                            }

                            if (sampleRTT > 0) {
                                updateRTT(sampleRTT);
                                System.out.println("ACK " + ackNum + " received. RTT sample = " + sampleRTT +
                                        "ms. New EstimatedRTT = " + String.format("%.0f", estimatedRTT) +
                                        "ms, DevRTT = " + String.format("%.2f", devRTT) +
                                        "ms, RTO = " + String.format("%.1f", this.timeout) + "ms");
                            } else {
                                System.out.println("ACK " + ackNum + " received.");
                            }

                            for (int i = base; i <= ackNum; i++) {
                                TimerTask task = timeoutTasks.remove(i);
                                if (task != null)
                                    task.cancel();
                                packetBuffer.remove(i);
                                sendTimes.remove(i);
                            }

                            base = ackNum + 1;
                            lastAckReceived = ackNum;
                            duplicateAckCount = 0;

                            if (base > totalPackets) {
                                System.out.println("All packets (" + totalPackets + ") successfully acknowledged!");
                                break;
                            }
                        } else if (ackNum == lastAckReceived && ackNum < base - 1) {
                            duplicateAckCount++;
                            System.out.println(
                                    "Received Duplicate ACK for Seq " + ackNum + " (Count: " + duplicateAckCount + ")");

                            if (duplicateAckCount >= DUPLICATE_ACK_THRESHOLD) {
                                int lostPacketSeq = ackNum + 1;
                                if (packetBuffer.containsKey(lostPacketSeq)) {
                                    System.out.println("Triple Duplicate ACK! Fast Retransmit Triggered for Packet "
                                            + lostPacketSeq);
                                    Packet packetToResend = packetBuffer.get(lostPacketSeq);

                                    sendPacket(packetToResend); // Resend
                                    sendTimes.put(lostPacketSeq, System.currentTimeMillis());

                                    TimerTask oldTask = timeoutTasks.remove(lostPacketSeq);
                                    if (oldTask != null)
                                        oldTask.cancel();

                                    if (!processingComplete) {
                                        TimerTask newTimeoutTask = new TimerTask() {
                                            @Override
                                            public void run() {
                                                handleTimeout(lostPacketSeq);
                                            }
                                        };
                                        try {
                                            timer.schedule(newTimeoutTask, (long) this.timeout);
                                            timeoutTasks.put(lostPacketSeq, newTimeoutTask);
                                        } catch (IllegalStateException e) {
                                            System.out.println("Timer cancelled during fast retransmit scheduling for "
                                                    + lostPacketSeq);
                                        }
                                    }
                                    duplicateAckCount = 0;
                                }
                            }
                        } else if (ackNum < lastAckReceived) {
                            System.out.println("Received old/out-of-order ACK for " + ackNum + ". Current base: " + base
                                    + ", Last ACK: " + lastAckReceived);
                        }
                    }
                } else {
                    System.out.println("Client " + clientSocket.getRemoteSocketAddress()
                            + " appears to have closed the connection (read null).");
                    processingComplete = true;
                    break;
                }
            } catch (SocketTimeoutException e) {
                System.out.println(
                        "Server: No ACK received within socket read timeout. Waiting for packet timers or next ACK.");
            }
            if (processingComplete)
                break;
        }

        if (!processingComplete) {
            if (base > totalPackets) {
                System.out.println("Sending end-of-transmission marker to " + clientSocket.getRemoteSocketAddress());
                Packet endPacket = new Packet(-1, 0, Packet.DATA, 0, 0, null); // Using DATA flag as per original
                sendPacket(endPacket);
            } else {
                System.out.println("File sending incomplete, not sending end marker. Base: " + base + ", TotalPackets: "
                        + totalPackets);
            }
        }

        // Final cleanup for this sendFile operation
        synchronized (lock) {
            this.processingComplete = true; // Signal completion
        }
        this.timer.cancel();
        this.timer.purge();
        System.out.println(
                "File sending process for " + filePath + " to " + clientSocket.getRemoteSocketAddress() + " finished.");
    }

    private void updateRTT(long sampleRTT) {

        if (sampleRTT <= 0)
            sampleRTT = 1;

        boolean isFirstSample = (this.estimatedRTT == 100.0 && this.devRTT == 25.0);

        if (isFirstSample) {
            this.estimatedRTT = sampleRTT;
            this.devRTT = sampleRTT / 2.0;
        } else {
            this.estimatedRTT = (1 - ALPHA) * this.estimatedRTT + ALPHA * sampleRTT;
            this.devRTT = (1 - BETA) * this.devRTT + BETA * Math.abs(sampleRTT - this.estimatedRTT);
        }

        if (this.estimatedRTT < 1)
            this.estimatedRTT = 1;
        if (this.devRTT < 1)
            this.devRTT = 0.5;

        this.timeout = calculateRTO();
    }

    private void handleTimeout(int seqNum) {
        synchronized (lock) {
            if (processingComplete) {
                System.out.println("Timer fired for " + seqNum + " but processing is complete. Ignoring.");
                return;
            }

            if (this.packetBuffer.containsKey(seqNum)) {
                System.out.println("Timeout for Packet " + seqNum + "! Retransmitting...");
                Packet packetToResend = this.packetBuffer.get(seqNum);

                if (packetToResend == null) {
                    System.err.println("CRITICAL ERROR: Packet " + seqNum
                            + " was in packetBuffer keys but object was null during timeout!");
                    this.packetBuffer.remove(seqNum);
                    TimerTask task = this.timeoutTasks.remove(seqNum);
                    if (task != null)
                        task.cancel();
                    return;
                }

                sendPacket(packetToResend);
                this.sendTimes.put(seqNum, System.currentTimeMillis());

                this.timeout = Math.min(this.timeout * 2, 2000);
                System.out.println("RTO exponentially backed off to: " + String.format("%.1f", this.timeout)
                        + "ms due to timeout on packet " + seqNum);

                TimerTask oldTask = this.timeoutTasks.remove(seqNum);
                if (oldTask != null)
                    oldTask.cancel();

                if (!processingComplete) {
                    TimerTask newTimeoutTask = new TimerTask() {
                        @Override
                        public void run() {
                            handleTimeout(seqNum);
                        }
                    };
                    try {
                        this.timer.schedule(newTimeoutTask, (long) this.timeout);
                        this.timeoutTasks.put(seqNum, newTimeoutTask);
                    } catch (IllegalStateException e) {
                        System.out.println("Timer was cancelled. Cannot reschedule timeout for packet " + seqNum
                                + " after its timeout event.");
                    }
                }
            } else {
                System.out.println("Timeout for Packet " + seqNum
                        + ", but it was already acknowledged or removed. No action needed.");
                TimerTask task = this.timeoutTasks.remove(seqNum);
                if (task != null)
                    task.cancel();
            }
        }
    }

    private void sendPacket(Packet packet) {

        String encodedPacket = packet.encode();
        writer.println(encodedPacket);
        writer.flush();

        if (packet.sequenceNumber == -1) {
            System.out.println("-> Sent End Marker to " + clientSocket.getRemoteSocketAddress());
        } else {
            System.out.println("-> Sent Packet " + packet.sequenceNumber + " (Flag: " + packet.flag + ", Len: "
                    + packet.payloadLength + ", CS:" + packet.checksum + ") to "
                    + clientSocket.getRemoteSocketAddress());
        }
    }
}
