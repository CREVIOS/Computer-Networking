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

public class rdt_sender {
    private static final int PORT = 4374;
    private static final String SERVER_FILE_DIR = "ServerFiles";

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Sender is running on port " + PORT);
        System.out.println("Waiting for receivers...\n");

        File serverDir = new File(SERVER_FILE_DIR);
        if (!serverDir.exists()) {
            serverDir.mkdir();
            System.out.println("Created source file directory: " + SERVER_FILE_DIR);
            System.out.println("Please place files to send in this directory.\n");
        }

        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Receiver connected from " + clientSocket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler).start();
            } catch (IOException e) {
                System.err.println("Error accepting receiver: " + e.getMessage());
            }
        }
    }
}

class ClientHandler implements Runnable {
    private static final String SERVER_FILE_DIR = "ServerFiles";
    private static final int MIN_PACKET_SIZE = 1024;
    private static final int MAX_PACKET_SIZE = 4096;
    private static final int DUPLICATE_ACK_THRESHOLD = 3;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;

    private static final int MAX_CONGESTION_WINDOW = 16;
    private static final int INITIAL_CONGESTION_WINDOW = 4;

    private static final double PACKET_LOSS_RATE = 0.008;
    private static final double PACKET_CORRUPTION_RATE = 0.003;
    private static final Random random = new Random();

    private final Socket clientSocket;
    private final int clientId;
    private BufferedReader reader;
    private PrintWriter writer;

    private final Object lock = new Object();
    private volatile boolean processingComplete = false;

    private Map<Integer, Packet> packetBuffer;
    private Map<Integer, Long> sendTimes;
    private Map<Integer, TimerTask> timeoutTasks;
    private Map<Integer, Integer> retransmissionCounts;
    private Timer timer;

    private double estimatedRTT;
    private double devRTT;
    private double timeout;
    private rtt_plotter rttPlotter;

    private int congestionWindow;
    private int receiverWindow;
    private int effectiveWindow;

    public ClientHandler(Socket socket) {
        this.clientSocket = socket;
        this.clientId = socket.getPort();

        this.estimatedRTT = 100.0;
        this.devRTT = 25.0;
        this.timeout = calculateRTO();
        this.rttPlotter = new rtt_plotter();

        this.congestionWindow = INITIAL_CONGESTION_WINDOW;
        this.receiverWindow = INITIAL_CONGESTION_WINDOW;
        this.effectiveWindow = Math.min(congestionWindow, receiverWindow);
    }

    private double calculateRTO() {
        double rto = this.estimatedRTT + 4 * this.devRTT;
        if (rto < 50)
            rto = 50;
        if (rto > 600)
            rto = 600;
        return rto;
    }

    private int getDynamicPacketSize() {
        return MIN_PACKET_SIZE + random.nextInt(MAX_PACKET_SIZE - MIN_PACKET_SIZE + 1);
    }

    private boolean shouldSimulatePacketLoss() {
        return random.nextDouble() < PACKET_LOSS_RATE;
    }

    private boolean shouldSimulateCorruption() {
        return random.nextDouble() < PACKET_CORRUPTION_RATE;
    }

    private Packet corruptPacket(Packet original) {
        Packet corrupted = new Packet(original.sequenceNumber, original.acknowledgementNumber,
                original.flag, original.windowSize, original.payloadLength,
                original.payload);
        corrupted.checksum = original.checksum + 12345;
        return corrupted;
    }

    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            writer = new PrintWriter(clientSocket.getOutputStream(), true);

            if (!performHandshake()) {
                System.out.println("Receiver #" + clientId + " - Handshake failed");
                return;
            }

            String fileName = receiveFileRequest();
            if (fileName == null) {
                System.out
                        .println("No valid file request received from receiver " + clientSocket.getRemoteSocketAddress());
                return;
            }

            sendFile(fileName);

            System.out.println("\nGenerating RTT plot for receiver #" + clientId + "...");
            rttPlotter.plotRTTData();

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

        this.receiverWindow = synPacket.windowSize;
        this.effectiveWindow = Math.min(congestionWindow, receiverWindow);

        System.out.println("Receiver #" + clientId + " - Received SYN (Receiver RcvWnd: " + receiverWindow + ")");

        Packet synAckPacket = new Packet(0, synPacket.sequenceNumber + 1,
                Packet.orFlags(Packet.SYN, Packet.ACK),
                congestionWindow, 0, null);
        sendPacket(synAckPacket);
        System.out.println("Receiver #" + clientId + " - Sent SYN-ACK (Sender CongWnd: " + congestionWindow + ")");

        String ackRequest = reader.readLine();
        if (ackRequest == null)
            return false;

        Packet ackPacket = Packet.decode(ackRequest);
        if (!ackPacket.flag.equals(Packet.ACK))
            return false;

        this.receiverWindow = ackPacket.windowSize;
        this.effectiveWindow = Math.min(congestionWindow, receiverWindow);

        System.out.println("Receiver #" + clientId + " - Connection established!");
        System.out.println("Receiver #" + clientId + " - Flow Control: CongWnd=" + congestionWindow +
                ", RcvWnd=" + receiverWindow + ", EffectiveWnd=" + effectiveWindow + "\n");
        return true;
    }

    private String receiveFileRequest() throws IOException {
        System.out.println("Receiver #" + clientId + " - Waiting for file request...");

        String fileRequestData = reader.readLine();
        if (fileRequestData == null) {
            System.out.println("Receiver #" + clientId + " - No file request received");
            return null;
        }

        Packet fileRequestPacket = Packet.decode(fileRequestData);

        if (!fileRequestPacket.verifyChecksum()) {
            System.out.println("Receiver #" + clientId + " - File request packet corrupted");
            String errorMsg = "ERROR: Request corrupted";
            Packet errorPacket = new Packet(1, 0, Packet.DATA, congestionWindow,
                    errorMsg.length(), errorMsg.getBytes());
            sendPacket(errorPacket);
            return null;
        }

        if (fileRequestPacket.payload == null || fileRequestPacket.payloadLength == 0) {
            System.out.println("Receiver #" + clientId + " - Empty file request");
            String errorMsg = "ERROR: Empty filename";
            Packet errorPacket = new Packet(1, 0, Packet.DATA, congestionWindow,
                    errorMsg.length(), errorMsg.getBytes());
            sendPacket(errorPacket);
            return null;
        }

        String requestedFileName = new String(fileRequestPacket.payload, 0, fileRequestPacket.payloadLength);
        System.out.println("Receiver #" + clientId + " - Requested file: " + requestedFileName);

        File requestedFile = new File(SERVER_FILE_DIR, requestedFileName);
        if (!requestedFile.exists() || !requestedFile.isFile()) {
            System.out.println("Receiver #" + clientId + " - File not found: " + requestedFileName);
            String errorMsg = "ERROR: File not found";
            Packet errorPacket = new Packet(1, 0, Packet.DATA, congestionWindow,
                    errorMsg.length(), errorMsg.getBytes());
            sendPacket(errorPacket);
            return null;
        }

        String successMsg = "OK: File found";
        Packet successPacket = new Packet(1, 0, Packet.DATA, congestionWindow,
                successMsg.length(), successMsg.getBytes());
        sendPacket(successPacket);
        System.out.println("Receiver #" + clientId + " - File found, sending confirmation");

        return requestedFile.getPath();
    }

    private void sendFile(String filePath) throws IOException {
        this.packetBuffer = new HashMap<>();
        this.sendTimes = new HashMap<>();
        this.timeoutTasks = new HashMap<>();
        this.retransmissionCounts = new HashMap<>();
        this.timer = new Timer("ClientHandler-Timer-" + clientSocket.getPort(), true);
        this.processingComplete = false;
        this.estimatedRTT = 100.0;
        this.devRTT = 25.0;
        this.timeout = calculateRTO();

        byte[] fileContent = Files.readAllBytes(Paths.get(filePath));
        List<Integer> packetSizes = new ArrayList<>();
        int totalPackets = 0;
        int currentOffset = 0;

        while (currentOffset < fileContent.length) {
            int packetSize = getDynamicPacketSize();
            int remainingBytes = fileContent.length - currentOffset;
            int actualSize = Math.min(packetSize, remainingBytes);
            packetSizes.add(actualSize);
            currentOffset += actualSize;
            totalPackets++;
        }

        System.out.println("Sending file " + filePath + " (" + fileContent.length + " bytes, " + totalPackets
                + " packets) to " + clientSocket.getRemoteSocketAddress());
        System.out.println("Dynamic packet size range: " + MIN_PACKET_SIZE + "-" + MAX_PACKET_SIZE + " bytes");
        System.out.println("Packet loss simulation: " + (PACKET_LOSS_RATE * 100) + "%");
        System.out.println("Corruption simulation: " + (PACKET_CORRUPTION_RATE * 100) + "%");
        System.out.println("Initial Flow Control: CongWnd=" + congestionWindow +
                ", RcvWnd=" + receiverWindow + ", EffectiveWnd=" + effectiveWindow);

        int base = 1;
        int nextSeqNum = 1;

        int lastAckReceived = 0;
        int duplicateAckCount = 0;

        while (base <= totalPackets) {
            synchronized (lock) {
                while (nextSeqNum < base + effectiveWindow && nextSeqNum <= totalPackets) {
                    if (processingComplete)
                        break;

                    int offset = 0;
                    for (int i = 0; i < nextSeqNum - 1; i++) {
                        offset += packetSizes.get(i);
                    }

                    int length = packetSizes.get(nextSeqNum - 1);
                    byte[] data = new byte[length];
                    System.arraycopy(fileContent, offset, data, 0, length);

                    Packet packet = new Packet(nextSeqNum, 0, Packet.DATA, congestionWindow, length, data);
                    packetBuffer.put(nextSeqNum, packet);
                    sendTimes.put(nextSeqNum, System.currentTimeMillis());
                    retransmissionCounts.put(nextSeqNum, 0);

                    final int currentSeqNum = nextSeqNum;

                    if (shouldSimulatePacketLoss()) {
                        System.out.println(
                                "Simulating loss of Packet " + currentSeqNum + " (Seq# " + currentSeqNum + ")");
                    } else if (shouldSimulateCorruption()) {
                        Packet corruptedPacket = corruptPacket(packet);
                        sendPacketRaw(corruptedPacket);
                        System.out.println("Sending CORRUPTED Packet " + currentSeqNum + " with Seq# " +
                                currentSeqNum + " (EffWnd: " + effectiveWindow + ")");
                    } else {
                        sendPacket(packet);
                        System.out.println("Sending Packet " + currentSeqNum + " with Seq# " +
                                currentSeqNum + " (" + length + " bytes, EffWnd: " + effectiveWindow + ")");
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
                    int newReceiverWindow = ackPacket.windowSize;
                    long receiveTime = System.currentTimeMillis();

                    synchronized (lock) {
                        if (processingComplete)
                            continue;

                        boolean windowChanged = (newReceiverWindow != this.receiverWindow);
                        this.receiverWindow = newReceiverWindow;
                        int oldEffectiveWindow = this.effectiveWindow;
                        this.effectiveWindow = Math.min(congestionWindow, receiverWindow);

                        if (windowChanged) {
                            System.out.println("Receiver window updated: RcvWnd=" + receiverWindow +
                                    " -> EffectiveWnd=" + effectiveWindow +
                                    " (was " + oldEffectiveWindow + ")");
                        }

                        if (ackNum >= base) {
                            long sampleRTT = -1;

                            if (sendTimes.containsKey(ackNum)) {
                                sampleRTT = receiveTime - sendTimes.get(ackNum);
                            }

                            if (sampleRTT > 0) {
                                updateRTT(sampleRTT);
                                System.out.println("ACK " + ackNum + " received. RTT sample = " + sampleRTT +
                                        "ms. New EstimatedRTT = " + String.format("%.0f", estimatedRTT) +
                                        "ms, DevRTT = " + String.format("%.2f", devRTT) +
                                        "ms, RTO = " + String.format("%.1f", this.timeout) + "ms" +
                                        " (RcvWnd: " + receiverWindow + ", EffWnd: " + effectiveWindow + ")");
                            } else {
                                System.out.println("ACK " + ackNum + " received. (RcvWnd: " + receiverWindow +
                                        ", EffWnd: " + effectiveWindow + ")");
                            }

                            if (congestionWindow < MAX_CONGESTION_WINDOW) {
                                congestionWindow = Math.min(congestionWindow + 1, MAX_CONGESTION_WINDOW);
                                this.effectiveWindow = Math.min(congestionWindow, receiverWindow);
                                System.out.println("Congestion window increased: CongWnd=" + congestionWindow +
                                        ", EffWnd=" + effectiveWindow);
                            }

                            for (int i = base; i <= ackNum; i++) {
                                TimerTask task = timeoutTasks.remove(i);
                                if (task != null)
                                    task.cancel();
                                packetBuffer.remove(i);
                                sendTimes.remove(i);
                                retransmissionCounts.remove(i);
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
                                    "Received Duplicate ACK for Seq " + ackNum + " (Count: " + duplicateAckCount +
                                            ", RcvWnd: " + receiverWindow + ")");

                            if (duplicateAckCount >= DUPLICATE_ACK_THRESHOLD) {
                                int lostPacketSeq = ackNum + 1;
                                if (packetBuffer.containsKey(lostPacketSeq)) {
                                    System.out.println("Triple Duplicate ACK! Fast Retransmit Triggered for Packet "
                                            + lostPacketSeq);

                                    congestionWindow = Math.max(congestionWindow / 2, 1);
                                    this.effectiveWindow = Math.min(congestionWindow, receiverWindow);
                                    System.out.println(
                                            "Congestion window reduced due to loss: CongWnd=" + congestionWindow +
                                                    ", EffWnd=" + effectiveWindow);

                                    Packet packetToResend = packetBuffer.get(lostPacketSeq);
                                    sendPacket(packetToResend);
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
                                    + ", Last ACK: " + lastAckReceived + " (RcvWnd: " + receiverWindow + ")");
                        }
                    }
                } else {
                    System.out.println("Receiver " + clientSocket.getRemoteSocketAddress()
                            + " appears to have closed the connection (read null).");
                    processingComplete = true;
                    break;
                }
            } catch (SocketTimeoutException e) {
                System.out.println(
                        "Sender: No ACK received within socket read timeout. Waiting for packet timers or next ACK.");
            }
            if (processingComplete)
                break;
        }

        if (!processingComplete) {
            if (base > totalPackets) {
                System.out.println("All file data sent, initiating connection termination with " + clientSocket.getRemoteSocketAddress());
                initiateConnectionTermination();
            } else {
                System.out.println("File sending incomplete, not initiating termination. Base: " + base + ", TotalPackets: "
                        + totalPackets);
            }
        }

        synchronized (lock) {
            this.processingComplete = true;
        }
        this.timer.cancel();
        this.timer.purge();
        System.out.println(
                "File sending process for " + filePath + " to " + clientSocket.getRemoteSocketAddress() + " finished.");
        System.out.println("Final Flow Control Stats: CongWnd=" + congestionWindow +
                ", RcvWnd=" + receiverWindow + ", EffectiveWnd=" + effectiveWindow);
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

        rttPlotter.recordRTT(sampleRTT, this.estimatedRTT, this.timeout);
    }

    private void handleTimeout(int seqNum) {
        synchronized (lock) {
            if (processingComplete) {
                System.out.println("Timer fired for " + seqNum + " but processing is complete. Ignoring.");
                return;
            }

            if (this.packetBuffer.containsKey(seqNum)) {
                int retransmissionCount = retransmissionCounts.getOrDefault(seqNum, 0);

                if (retransmissionCount >= 5) {
                    System.out.println("Packet " + seqNum + " exceeded maximum retransmissions (5). Dropping.");
                    this.packetBuffer.remove(seqNum);
                    this.sendTimes.remove(seqNum);
                    this.retransmissionCounts.remove(seqNum);
                    TimerTask task = this.timeoutTasks.remove(seqNum);
                    if (task != null)
                        task.cancel();
                    return;
                }

                System.out.println("Timeout for Packet " + seqNum + "! Retransmitting... (Attempt "
                        + (retransmissionCount + 1) + "/5)");
                retransmissionCounts.put(seqNum, retransmissionCount + 1);

                congestionWindow = Math.max(congestionWindow / 2, 1);
                this.effectiveWindow = Math.min(congestionWindow, receiverWindow);
                System.out.println("Congestion window reduced due to timeout: CongWnd=" + congestionWindow +
                        ", EffWnd=" + effectiveWindow);

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

        System.out.println("-> Sent Packet " + packet.sequenceNumber + " (Flag: " + packet.flag + ", Len: "
                + packet.payloadLength + ", CS:" + packet.checksum + ") to "
                + clientSocket.getRemoteSocketAddress());
    }

    private void sendPacketRaw(Packet packet) {
        String encodedPacket = packet.encode();
        writer.println(encodedPacket);
        writer.flush();

        System.out.println("-> Sent Packet " + packet.sequenceNumber + " (Flag: " + packet.flag + ", Len: "
                + packet.payloadLength + ", CS:" + packet.checksum + ") to "
                + clientSocket.getRemoteSocketAddress());
    }
    
    private void initiateConnectionTermination() {
        try {
            System.out.println("Initiating connection termination (FIN handshake)");
            
            // Send FIN packet
            Packet finPacket = new Packet(0, 0, Packet.FIN, congestionWindow, 0, null);
            sendPacket(finPacket);
            System.out.println("Sent FIN packet");
            
            // Wait for FIN-ACK with increased timeout
            clientSocket.setSoTimeout(5000);
            String finAckData = reader.readLine();
            
            if (finAckData != null) {
                Packet finAckPacket = Packet.decode(finAckData);
                if (finAckPacket.flag.equals(Packet.orFlags(Packet.FIN, Packet.ACK))) {
                    System.out.println("Received FIN-ACK");
                    
                    // Send final ACK
                    Packet finalAck = new Packet(0, 0, Packet.ACK, congestionWindow, 0, null);
                    sendPacket(finalAck);
                    System.out.println("Sent final ACK, termination complete");
                    
                    // Wait a moment to ensure ACK is delivered before closing
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        // Ignore
                    }
                } else {
                    System.out.println("Received unexpected packet instead of FIN-ACK: " + finAckPacket.flag);
                }
            } else {
                System.out.println("No response received for FIN packet");
            }
        } catch (IOException e) {
            System.out.println("Error during connection termination: " + e.getMessage());
        }
    }
}