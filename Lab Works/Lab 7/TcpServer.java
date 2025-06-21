import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

public class TcpServer {
    private static final int PORT = 12345;
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();
    private static final int DEFAULT_MSS = 1460;
    private static final int MAX_WINDOW_SIZE = 65536; // 64KB

    public enum TcpMode {
        TAHOE, RENO
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== TCP File Server ===");
        System.out.println("Select TCP Congestion Control Mode:");
        System.out.println("1. TCP Tahoe");
        System.out.println("2. TCP Reno");
        System.out.print("Enter choice (1 or 2): ");

        int choice = scanner.nextInt();
        TcpMode selectedMode = (choice == 2) ? TcpMode.RENO : TcpMode.TAHOE;
        scanner.close();

        System.out.println("\nServer will run in " + selectedMode.name() + " mode.");
        System.out.println("Listening on port " + PORT + "...\n");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New client connected: " + clientSocket.getInetAddress());
                    TcpClientHandler handler = new TcpClientHandler(clientSocket, selectedMode);
                    threadPool.execute(handler);
                } catch (IOException e) {
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not listen on port " + PORT + ": " + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }

    static class TcpClientHandler implements Runnable {
        private final Socket clientSocket;
        private DataInputStream in;
        private DataOutputStream out;
        private final TcpMode mode;
        private final Random random = new Random();

        private int mss = DEFAULT_MSS;
        private int peerMss = DEFAULT_MSS;
        private int rwnd = MAX_WINDOW_SIZE;

        private int cwnd = DEFAULT_MSS;
        private int ssthresh = 65536;
        private boolean inSlowStart = true;
        private boolean inFastRecovery = false;
        private int fastRecoveryHighSeq = 0;

        private int nextSeqNum = 1;
        private int baseSeqNum = 1;

        private final NavigableMap<Integer, SegmentInfo> sentSegments = new ConcurrentSkipListMap<>();
        private int lastAckReceived = 0;
        private int duplicateAckCount = 0;

        private final Timer retransmissionTimer = new Timer("RetxTimer");
        private final Map<Integer, TimerTask> segmentTimers = new ConcurrentHashMap<>();
        private final RTTCalculator rttCalc = new RTTCalculator();

        private static final double PACKET_LOSS_RATE = 0.02; // 2% loss rate
        private int packetsSent = 0;

        private long startTime;
        private int totalPacketsSent = 0;
        private int totalPacketsLost = 0;
        private int totalRetransmissions = 0;
        private int fastRetransmitCount = 0;
        private int timeoutCount = 0;
        private final List<CongestionEvent> congestionEvents = new ArrayList<>();
        private CongestionWindowPlotter plotter;
        private int currentRound = 0;

        public TcpClientHandler(Socket socket, TcpMode mode) {
            this.clientSocket = socket;
            this.mode = mode;
            this.plotter = new CongestionWindowPlotter();
        }

        @Override
        public void run() {
            try {
                in = new DataInputStream(clientSocket.getInputStream());
                out = new DataOutputStream(clientSocket.getOutputStream());

                System.out.println("\n" + "=".repeat(60));
                System.out.println("        TCP " + mode.name() + " SESSION STARTED");
                System.out.println("=".repeat(60));

                performHandshake();
                String filename = receiveFileRequest();
                if (filename != null) {
                    sendFile(filename);
                }
            } catch (IOException e) {
                System.err.println("Error handling client: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void performHandshake() throws IOException {
            System.out.println("\n=== Handshake ===");

            String synMsg = in.readUTF();
            Packet syn = Packet.decode(synMsg);
            System.out.println("Received SYN seq=" + syn.sequenceNumber);

            if (syn.mssOption > 0) {
                peerMss = syn.mssOption;
                mss = Math.min(mss, peerMss);
                System.out.println("MSS negotiated: " + mss + " (peer=" + peerMss + ")");
            }

            Packet synAck = new Packet(0, syn.sequenceNumber + 1,
                    Packet.orFlags(Packet.SYN, Packet.ACK),
                    rwnd, 0, new byte[0], null);
            synAck.mssOption = mss;
            out.writeUTF(synAck.encode());
            out.flush();
            System.out.println("Sent SYN-ACK");

            String ackMsg = in.readUTF();
            Packet ack = Packet.decode(ackMsg);
            System.out.println("Received ACK - Handshake complete\n");
        }

        private String receiveFileRequest() throws IOException {
            System.out.println("=== File Request ===");
            String reqMsg = in.readUTF();
            Packet request = Packet.decode(reqMsg);
            String filename = new String(request.payload, 0, request.payloadLength);

            System.out.println("Requested file: " + filename);

            Packet ack = new Packet(0, request.sequenceNumber + request.payloadLength,
                    Packet.ACK, rwnd, 0, new byte[0], null);
            out.writeUTF(ack.encode());
            out.flush();
            return filename;
        }

        private void sendFile(String filename) throws IOException {
            File file = new File(filename);
            if (!file.exists()) {
                sendErrorResponse("File not found: " + filename);
                return;
            }

            byte[] fileData = Files.readAllBytes(file.toPath());
            int totalBytes = fileData.length;

            System.out.println("\n=== File Transfer ===");
            System.out.println("File: " + filename + " (" + totalBytes + " bytes)");
            System.out.println("Initial cwnd=" + cwnd + ", ssthresh=" + ssthresh + ", mss=" + mss);
            System.out.println("Mode: TCP " + mode.name() + "\n");

            startTime = System.currentTimeMillis();

            int bytesSent = 0;
            while (bytesSent < totalBytes || !sentSegments.isEmpty()) {

                sendNewSegments(fileData, bytesSent, totalBytes);

                bytesSent = calculateBytesSent(totalBytes);

                processAcks();

                if (bytesSent >= totalBytes && sentSegments.isEmpty()) {
                    break;
                }

                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            sendFin();

            printTransferStats(totalBytes);
        }

        private void sendNewSegments(byte[] fileData, int bytesSent, int totalBytes) throws IOException {
            int bytesInFlight = calculateBytesInFlight();
            int effectiveWindow = Math.min(cwnd, rwnd);

            while (bytesInFlight < effectiveWindow && bytesSent < totalBytes) {
                int remainingBytes = totalBytes - bytesSent;
                int segmentSize = Math.min(Math.min(remainingBytes, mss),
                        effectiveWindow - bytesInFlight);

                if (segmentSize <= 0)
                    break;

                byte[] segmentData = Arrays.copyOfRange(fileData, bytesSent, bytesSent + segmentSize);
                Packet segment = new Packet(nextSeqNum, 0, Packet.DATA, rwnd,
                        segmentSize, segmentData, null);

                sendSegment(segment, segmentSize);
                bytesSent += segmentSize;
                bytesInFlight = calculateBytesInFlight();
            }
        }

        private int calculateBytesSent(int totalBytes) {
            int maxSeqSent = nextSeqNum - 1;
            return Math.min(maxSeqSent, totalBytes);
        }

        private void sendSegment(Packet segment, int size) throws IOException {
            packetsSent++;
            totalPacketsSent++;

            if (packetsSent > 5 && random.nextDouble() < PACKET_LOSS_RATE) {
                System.out.println("[LOSS] Dropping seq=" + segment.sequenceNumber + " size=" + size);
                totalPacketsLost++;

                SegmentInfo info = new SegmentInfo(segment, System.currentTimeMillis(), size);
                sentSegments.put(segment.sequenceNumber, info);
                startRetransmissionTimer(segment.sequenceNumber);

                nextSeqNum += size;
                return;
            }

            out.writeUTF(segment.encode());
            out.flush();

            SegmentInfo info = new SegmentInfo(segment, System.currentTimeMillis(), size);
            sentSegments.put(segment.sequenceNumber, info);
            startRetransmissionTimer(segment.sequenceNumber);

            String state = inSlowStart ? "SS" : (inFastRecovery ? "FR" : "CA");
            System.out.println("Sent seq=" + segment.sequenceNumber + " size=" + size +
                    " cwnd=" + cwnd + " [" + state + "]");

            nextSeqNum += size;
        }

        private void processAcks() throws IOException {
            try {
                clientSocket.setSoTimeout(100);
                while (in.available() > 0) {
                    String ackMsg = in.readUTF();
                    Packet ack = Packet.decode(ackMsg);
                    handleAck(ack);
                }
            } catch (SocketTimeoutException e) {
            }
        }

        private void handleAck(Packet ackPacket) {
            int ackNum = ackPacket.acknowledgementNumber;
            rwnd = ackPacket.windowSize;

            System.out.println("Received ACK=" + ackNum + " rwnd=" + rwnd);

            if (ackNum > baseSeqNum) {
                handleNewAck(ackNum, ackPacket);
            } else if (ackNum == lastAckReceived) {
                handleDuplicateAck(ackNum);
            }

            lastAckReceived = ackNum;

            if (ackPacket.sackBlocks != null) {
                handleSackBlocks(ackPacket.sackBlocks);
            }
        }

        private void handleNewAck(int ackNum, Packet ackPacket) {
            int newlyAckedBytes = 0;
            long now = System.currentTimeMillis();

            SegmentInfo oldestSeg = sentSegments.get(baseSeqNum);
            boolean rttSampleTaken = false;

            Iterator<Map.Entry<Integer, SegmentInfo>> it = sentSegments.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, SegmentInfo> entry = it.next();
                int seqNum = entry.getKey();
                SegmentInfo segInfo = entry.getValue();

                if (seqNum + segInfo.size <= ackNum) {
                    newlyAckedBytes += segInfo.size;

                    if (!rttSampleTaken && segInfo.retransmissionCount == 0 && segInfo == oldestSeg) {
                        long rtt = now - segInfo.sendTime;
                        rttCalc.updateRTT(rtt);
                        rttSampleTaken = true;
                    }

                    cancelRetransmissionTimer(seqNum);
                    it.remove();
                }
            }

            baseSeqNum = ackNum;
            duplicateAckCount = 0;

            if (inFastRecovery && mode == TcpMode.RENO) {
                handleFastRecoveryAck(ackNum, newlyAckedBytes);
            } else {
                updateCongestionWindow(newlyAckedBytes);
            }

            logCongestionEvent("NEW_ACK", cwnd, ssthresh);
            recordWindowChange("NEW_ACK");
        }

        private void handleDuplicateAck(int ackNum) {
            duplicateAckCount++;
            System.out.println("[DUP-ACK] #" + duplicateAckCount + " for seq=" + ackNum);

            if (duplicateAckCount == 3 && !inFastRecovery) {
                if (plotter != null) {
                    plotter.recordEvent(currentRound, "TRIPLE_ACK");
                }
                handleFastRetransmit();
            } else if (inFastRecovery && mode == TcpMode.RENO) {
                cwnd += mss;
                logCongestionEvent("CWND_INFLATE", cwnd, ssthresh);
                recordWindowChange("CWND_INFLATE");
                System.out.println("[RENO] Inflated cwnd to " + cwnd);
            }
        }

        private void handleFastRetransmit() {
            fastRetransmitCount++;
            fastRecoveryHighSeq = nextSeqNum;

            System.out.println("\n[FAST-RETRANSMIT] Triggered by 3 duplicate ACKs");

            int oldCwnd = cwnd;
            int oldSsthresh = ssthresh;

            ssthresh = Math.max(cwnd / 2, 2 * mss);

            if (plotter != null) {
                plotter.recordEvent(currentRound, "FAST_RETRANSMIT");
            }

            if (mode == TcpMode.TAHOE) {
                cwnd = mss;
                inSlowStart = true;
                inFastRecovery = false;
                System.out.println("[TAHOE] cwnd=" + oldCwnd + "->" + cwnd +
                        ", ssthresh=" + oldSsthresh + "->" + ssthresh);
            } else {
                cwnd = ssthresh + 3 * mss;
                inSlowStart = false;
                inFastRecovery = true;
                System.out.println("[RENO] Fast Recovery: cwnd=" + oldCwnd + "->" + cwnd +
                        ", ssthresh=" + oldSsthresh + "->" + ssthresh);
                if (plotter != null) {
                    plotter.recordEvent(currentRound, "FAST_RECOVERY");
                }
            }

            SegmentInfo lostSeg = sentSegments.get(baseSeqNum);
            if (lostSeg != null) {
                retransmitSegment(lostSeg, "FAST-RETX");
            }

            duplicateAckCount = 0;
            logCongestionEvent("FAST_RETRANSMIT", cwnd, ssthresh);
            recordWindowChange("FAST_RETRANSMIT");
        }

        private void handleFastRecoveryAck(int ackNum, int newlyAckedBytes) {
            if (plotter != null) {
                plotter.recordEvent(currentRound, "EXIT_FAST_RECOVERY");
            }

            if (ackNum >= fastRecoveryHighSeq) {
                cwnd = ssthresh;
                inFastRecovery = false;
                System.out.println("[RENO] Exiting Fast Recovery: cwnd=" + cwnd);
                logCongestionEvent("EXIT_FAST_RECOVERY", cwnd, ssthresh);
                recordWindowChange("EXIT_FAST_RECOVERY");
            } else {
                cwnd = Math.max(cwnd - newlyAckedBytes + mss, mss);
                System.out.println("[RENO] Partial ACK in Fast Recovery: cwnd=" + cwnd);
                logCongestionEvent("PARTIAL_ACK", cwnd, ssthresh);
                recordWindowChange("PARTIAL_ACK");
            }
        }

        private void updateCongestionWindow(int newlyAckedBytes) {
            int oldCwnd = cwnd;

            if (inSlowStart) {
                cwnd += mss;
                if (cwnd >= ssthresh) {
                    inSlowStart = false;
                    System.out.println("[SLOW-START->CONG-AVOID] cwnd=" + cwnd + ", ssthresh=" + ssthresh);
                }
            } else {
                int increment = Math.max((mss * mss) / cwnd, 1);
                cwnd += increment;
            }

            if (oldCwnd != cwnd) {
                String phase = inSlowStart ? "SLOW_START" : "CONG_AVOID";
                System.out.println("[" + phase + "] cwnd=" + oldCwnd + "->" + cwnd);
                logCongestionEvent(phase, cwnd, ssthresh);
                recordWindowChange(phase);
            }
        }

        private void handleTimeout(int seqNum) {

            SegmentInfo segInfo = sentSegments.get(seqNum);
            if (segInfo == null)
                return;

            if (plotter != null) {
                plotter.recordEvent(currentRound, "TIMEOUT");
            }

            timeoutCount++;
            System.out.println("\n[TIMEOUT] seq=" + seqNum + " (attempt #" +
                    (segInfo.retransmissionCount + 1) + ")");

            int oldCwnd = cwnd;
            int oldSsthresh = ssthresh;

            ssthresh = Math.max(cwnd / 2, 2 * mss);
            cwnd = mss;
            inSlowStart = true;
            inFastRecovery = false;
            duplicateAckCount = 0;

            System.out.println("[TIMEOUT] cwnd=" + oldCwnd + "->" + cwnd +
                    ", ssthresh=" + oldSsthresh + "->" + ssthresh);

            rttCalc.onTimeout();
            retransmitSegment(segInfo, "TIMEOUT");

            logCongestionEvent("TIMEOUT", cwnd, ssthresh);
            recordWindowChange("TIMEOUT");
        }

        private void retransmitSegment(SegmentInfo segInfo, String reason) {
            try {
                segInfo.retransmissionCount++;
                segInfo.sendTime = System.currentTimeMillis();
                totalRetransmissions++;

                out.writeUTF(segInfo.packet.encode());
                out.flush();

                System.out.println("[" + reason + "] Retransmit seq=" + segInfo.packet.sequenceNumber +
                        " size=" + segInfo.size + " (attempt #" + segInfo.retransmissionCount + ")");

                startRetransmissionTimer(segInfo.packet.sequenceNumber);
            } catch (IOException e) {
                System.err.println("Error retransmitting segment: " + e.getMessage());
            }
        }

        private void handleSackBlocks(List<SackBlock> sackBlocks) {
            for (SackBlock block : sackBlocks) {
                Iterator<Map.Entry<Integer, SegmentInfo>> it = sentSegments.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, SegmentInfo> entry = it.next();
                    int seqNum = entry.getKey();
                    SegmentInfo segInfo = entry.getValue();

                    if (seqNum >= block.start && seqNum + segInfo.size <= block.end) {
                        cancelRetransmissionTimer(seqNum);
                        it.remove();
                        System.out.println("[SACK] Removed seq=" + seqNum + " size=" + segInfo.size);
                    }
                }
            }
        }

        private int calculateBytesInFlight() {
            return sentSegments.values().stream()
                    .mapToInt(seg -> seg.size)
                    .sum();
        }

        private void startRetransmissionTimer(int seqNum) {
            cancelRetransmissionTimer(seqNum);

            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    handleTimeout(seqNum);
                }
            };

            segmentTimers.put(seqNum, task);
            long timeout = rttCalc.getTimeoutInterval();
            retransmissionTimer.schedule(task, timeout);
        }

        private void cancelRetransmissionTimer(int seqNum) {
            TimerTask task = segmentTimers.remove(seqNum);
            if (task != null) {
                task.cancel();
            }
        }

        private void sendFin() throws IOException {
            Packet fin = new Packet(nextSeqNum, 0, Packet.FIN, rwnd, 0, new byte[0], null);
            out.writeUTF(fin.encode());
            out.flush();
            System.out.println("\n[FIN] Transfer complete, sent FIN");

            try {
                clientSocket.setSoTimeout(5000);
                String ackMsg = in.readUTF();
                Packet ack = Packet.decode(ackMsg);
                System.out.println("[FIN-ACK] Received ACK=" + ack.acknowledgementNumber);
            } catch (SocketTimeoutException e) {
                System.out.println("[FIN] Timeout waiting for FIN-ACK");
            }
        }

        private void sendErrorResponse(String message) throws IOException {
            byte[] errorData = message.getBytes();
            Packet errorPacket = new Packet(nextSeqNum, 0,
                    Packet.orFlags(Packet.DATA, Packet.FIN),
                    rwnd, errorData.length, errorData, null);
            out.writeUTF(errorPacket.encode());
            out.flush();
            System.out.println("[ERROR] Sent: " + message);
        }

        private void logCongestionEvent(String event, int cwnd, int ssthresh) {
            congestionEvents.add(new CongestionEvent(System.currentTimeMillis() - startTime,
                    event, cwnd, ssthresh));
        }

        private void recordWindowChange(String reason) {
            currentRound++; // Increment round for each window change
            String phase = inSlowStart ? "SLOW-START" : (inFastRecovery ? "FAST-RECOVERY" : "CONG-AVOID");
            if (plotter != null) {
                plotter.recordWindowSize(currentRound, cwnd, ssthresh, phase, calculateSegmentsInFlight());
            }
        }

        private int calculateSegmentsInFlight() {
            return sentSegments.size();
        }

        private void printTransferStats(int totalBytes) {
            long endTime = System.currentTimeMillis();
            double durationSec = (endTime - startTime) / 1000.0;
            double throughputKBps = (totalBytes / 1024.0) / durationSec;
            double lossRate = (totalPacketsLost * 100.0) / totalPacketsSent;

            System.out.println("\n" + "=".repeat(70));
            System.out.println("                    TRANSFER STATISTICS");
            System.out.println("=".repeat(70));
            System.out.println("Mode:                TCP " + mode.name());
            System.out.printf("Duration:            %.2f seconds%n", durationSec);
            System.out.printf("Throughput:          %.2f KB/s%n", throughputKBps);
            System.out.println("Total Bytes:         " + totalBytes);
            System.out.println("Total Packets Sent:  " + totalPacketsSent);
            System.out.println("Packets Lost:        " + totalPacketsLost +
                    String.format(" (%.1f%%)", lossRate));
            System.out.println("Retransmissions:     " + totalRetransmissions);
            System.out.println("Fast Retransmits:    " + fastRetransmitCount);
            System.out.println("Timeouts:            " + timeoutCount);
            System.out.println("Final cwnd:          " + cwnd);
            System.out.println("Final ssthresh:      " + ssthresh);
            System.out.println("MSS:                 " + mss);
            System.out.println("=".repeat(70));

            // Print congestion events
            System.out.println("\nCongestion Control Events:");
            for (CongestionEvent event : congestionEvents.subList(0, Math.min(20, congestionEvents.size()))) {
                System.out.printf("  %6dms: %-15s cwnd=%d ssthresh=%d%n",
                        event.timeMs, event.event, event.cwnd, event.ssthresh);
            }
            if (congestionEvents.size() > 20) {
                System.out.println("  ... (" + (congestionEvents.size() - 20) + " more events)");
            }
            System.out.println();

            // Generate the congestion window plot
            if (plotter != null) {
                plotter.plotWindowData();
            }
        }

        private void cleanup() {
            try {
                retransmissionTimer.cancel();
                for (TimerTask task : segmentTimers.values()) {
                    task.cancel();
                }
                if (in != null)
                    in.close();
                if (out != null)
                    out.close();
                if (!clientSocket.isClosed())
                    clientSocket.close();
                System.out.println("Session ended.\n");
            } catch (IOException e) {
                System.err.println("Error during cleanup: " + e.getMessage());
            }
        }
    }

    static class SegmentInfo {
        Packet packet;
        long sendTime;
        int size;
        int retransmissionCount;

        public SegmentInfo(Packet p, long t, int s) {
            packet = p;
            sendTime = t;
            size = s;
            retransmissionCount = 0;
        }
    }

    static class RTTCalculator {
        private double estimatedRTT = 500.0; // ms
        private double deviationRTT = 100.0; // ms
        private long timeoutInterval = 1000; // ms
        private int consecutiveTimeouts = 0;

        private static final double ALPHA = 0.125;
        private static final double BETA = 0.25;
        private static final int MIN_TIMEOUT = 100;
        private static final int MAX_TIMEOUT = 2000;

        public void updateRTT(long sampleRTT) {
            estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
            deviationRTT = (1 - BETA) * deviationRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
            timeoutInterval = (long) Math.max(MIN_TIMEOUT,
                    Math.min(MAX_TIMEOUT, estimatedRTT + 4 * deviationRTT));
            consecutiveTimeouts = 0;
        }

        public long getTimeoutInterval() {
            long backoff = consecutiveTimeouts > 0 ? (1L << Math.min(consecutiveTimeouts, 5)) : 1L;
            return timeoutInterval * backoff;
        }

        public void onTimeout() {
            consecutiveTimeouts++;
        }
    }

    static class CongestionEvent {
        long timeMs;
        String event;
        int cwnd;
        int ssthresh;

        public CongestionEvent(long t, String e, int c, int s) {
            timeMs = t;
            event = e;
            cwnd = c;
            ssthresh = s;
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
                    (byte) (value >>> 24), (byte) (value >>> 16),
                    (byte) (value >>> 8), (byte) value
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

    static class CongestionWindowPlotter {
        private List<Integer> rounds;
        private List<Integer> congestionWindows;
        private List<Integer> slowStartThresholds;
        private List<String> phases;
        private List<Integer> segments;

        // Event tracking for markers
        private List<Integer> timeoutRounds;
        private List<Integer> tripleAckRounds;
        private List<Integer> fastRetransmitRounds;
        private List<Integer> fastRecoveryRounds;
        private List<Integer> exitFastRecoveryRounds;

        public CongestionWindowPlotter() {
            rounds = new ArrayList<>();
            congestionWindows = new ArrayList<>();
            slowStartThresholds = new ArrayList<>();
            phases = new ArrayList<>();
            segments = new ArrayList<>();

            // Initialize event tracking lists
            timeoutRounds = new ArrayList<>();
            tripleAckRounds = new ArrayList<>();
            fastRetransmitRounds = new ArrayList<>();
            fastRecoveryRounds = new ArrayList<>();
            exitFastRecoveryRounds = new ArrayList<>();
        }

        public void recordWindowSize(int round, int congestionWindow, int ssthresh, String phase,
                int segmentsInFlight) {
            rounds.add(round);
            congestionWindows.add(congestionWindow);
            slowStartThresholds.add(ssthresh);
            phases.add(phase);
            segments.add(segmentsInFlight);
        }

        public void recordEvent(int round, String eventType) {
            switch (eventType) {
                case "TIMEOUT":
                    timeoutRounds.add(round);
                    break;
                case "TRIPLE_ACK":
                    tripleAckRounds.add(round);
                    break;
                case "FAST_RETRANSMIT":
                    fastRetransmitRounds.add(round);
                    break;
                case "FAST_RECOVERY":
                    fastRecoveryRounds.add(round);
                    break;
                case "EXIT_FAST_RECOVERY":
                    exitFastRecoveryRounds.add(round);
                    break;
            }
        }

        public void plotWindowData() {
            if (rounds.isEmpty()) {
                System.out.println("No congestion window data to plot.");
                return;
            }

            try {
                // Create a temporary Python script file
                File scriptFile = new File("plot_window.py");
                try (PrintWriter writer = new PrintWriter(scriptFile)) {
                    writer.println("import matplotlib.pyplot as plt");
                    writer.println("import numpy as np");
                    writer.println();

                    // Write round data
                    writer.print("rounds = [");
                    for (int i = 0; i < rounds.size(); i++) {
                        writer.print(rounds.get(i));
                        if (i < rounds.size() - 1) {
                            writer.print(", ");
                        }
                    }
                    writer.println("]");

                    // Write congestion window data
                    writer.print("congestion_windows = [");
                    for (int i = 0; i < congestionWindows.size(); i++) {
                        writer.print(congestionWindows.get(i));
                        if (i < congestionWindows.size() - 1) {
                            writer.print(", ");
                        }
                    }
                    writer.println("]");

                    // Write slow start threshold data
                    writer.print("ssthresh = [");
                    for (int i = 0; i < slowStartThresholds.size(); i++) {
                        writer.print(slowStartThresholds.get(i));
                        if (i < slowStartThresholds.size() - 1) {
                            writer.print(", ");
                        }
                    }
                    writer.println("]");

                    // Write segments data
                    writer.print("segments = [");
                    for (int i = 0; i < segments.size(); i++) {
                        writer.print(segments.get(i));
                        if (i < segments.size() - 1) {
                            writer.print(", ");
                        }
                    }
                    writer.println("]");

                    // Write event data for markers
                    writeEventData(writer, "timeout_rounds", timeoutRounds);
                    writeEventData(writer, "triple_ack_rounds", tripleAckRounds);
                    writeEventData(writer, "fast_retransmit_rounds", fastRetransmitRounds);
                    writeEventData(writer, "fast_recovery_rounds", fastRecoveryRounds);
                    writeEventData(writer, "exit_fast_recovery_rounds", exitFastRecoveryRounds);

                    // Create the plot
                    writer.println();
                    writer.println("fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(15, 12), height_ratios=[3, 1])");

                    // Plot congestion window
                    writer.println(
                            "ax1.plot(rounds, congestion_windows, 'b-', label='Congestion Window', linewidth=2)");
                    writer.println(
                            "ax1.plot(rounds, ssthresh, 'r--', label='Slow Start Threshold', linewidth=1, alpha=0.7)");

                    // Add event markers
                    writer.println();
                    writer.println("# Add event markers");

                    // Helper function to get window size for a given round
                    writer.println("def get_window_for_round(target_round):");
                    writer.println("    if target_round in rounds:");
                    writer.println("        idx = rounds.index(target_round)");
                    writer.println("        return congestion_windows[idx]");
                    writer.println("    # Find closest round");
                    writer.println(
                            "    closest_idx = min(range(len(rounds)), key=lambda i: abs(rounds[i] - target_round))");
                    writer.println("    return congestion_windows[closest_idx]");
                    writer.println();

                    // Add markers for each event type
                    addMarkers(writer, "timeout_rounds", "red", "X", "Timeout", "15");
                    addMarkers(writer, "triple_ack_rounds", "orange", "v", "3 Duplicate ACKs", "12");
                    addMarkers(writer, "fast_retransmit_rounds", "purple", "^", "Fast Retransmit", "12");
                    addMarkers(writer, "fast_recovery_rounds", "green", "s", "Fast Recovery Start", "10");
                    addMarkers(writer, "exit_fast_recovery_rounds", "cyan", "D", "Fast Recovery Exit", "8");

                    // Configure first subplot
                    writer.println("ax1.set_ylabel('Window Size (bytes)', fontsize=12)");
                    writer.println(
                            "ax1.set_title('TCP Congestion Window Evolution with Events', fontsize=14, fontweight='bold')");
                    writer.println("ax1.legend(loc='upper left', fontsize=10)");
                    writer.println("ax1.grid(True, alpha=0.3)");

                    // Plot segments in flight
                    writer.println("ax2.plot(rounds, segments, 'g-', label='Segments in Flight', linewidth=2)");
                    writer.println("ax2.set_ylabel('Number of Segments', fontsize=12)");
                    writer.println("ax2.set_xlabel('Transmission Round', fontsize=12)");
                    writer.println("ax2.grid(True, alpha=0.3)");
                    writer.println("ax2.legend(loc='upper left', fontsize=10)");

                    // Configure layout and save
                    writer.println("plt.tight_layout()");
                    writer.println("plt.savefig('congestion_window_plot.png', dpi=300, bbox_inches='tight')");
                    writer.println("print('Plot saved as congestion_window_plot.png')");
                    writer.println("plt.show()");
                }

                System.out.println("Generating enhanced congestion window plot with event markers...");
                Process process = null;

                try {
                    process = new ProcessBuilder("python3", "plot_window.py").start();
                } catch (IOException e) {
                    try {
                        process = new ProcessBuilder("python", "plot_window.py").start();
                    } catch (IOException e2) {
                        System.err.println("Failed to execute Python. Make sure Python is installed.");
                        return;
                    }
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println(line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    System.out.println("Enhanced congestion window plot generated successfully!");
                    System.out.println("Events marked: Timeouts, Triple ACKs, Fast Retransmit, Fast Recovery");
                } else {
                    System.err.println("Error generating plot. Exit code: " + exitCode);
                }

            } catch (IOException | InterruptedException e) {
                System.err.println("Error plotting congestion window data: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void writeEventData(PrintWriter writer, String varName, List<Integer> data) {
            writer.print(varName + " = [");
            for (int i = 0; i < data.size(); i++) {
                writer.print(data.get(i));
                if (i < data.size() - 1) {
                    writer.print(", ");
                }
            }
            writer.println("]");
        }

        private void addMarkers(PrintWriter writer, String roundsVar, String color, String marker, String label,
                String size) {
            writer.println("if " + roundsVar + ":");
            writer.println("    marker_windows = [get_window_for_round(r) for r in " + roundsVar + "]");
            writer.println("    ax1.scatter(" + roundsVar + ", marker_windows, ");
            writer.println("               color='" + color + "', marker='" + marker + "', s=" + size + ", ");
            writer.println("               label='" + label + "', edgecolors='black', linewidth=0.5, zorder=5)");
            writer.println();
        }
    }
}