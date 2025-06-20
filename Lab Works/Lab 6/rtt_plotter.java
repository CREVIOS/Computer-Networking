import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class rtt_plotter {
    private List<Long> timestamps;
    private List<Long> sampleRTTs;
    private List<Double> estimatedRTTs;
    private List<Double> timeoutIntervals;
    private long startTime;

    public rtt_plotter() {
        timestamps = new ArrayList<>();
        sampleRTTs = new ArrayList<>();
        estimatedRTTs = new ArrayList<>();
        timeoutIntervals = new ArrayList<>();
        startTime = System.currentTimeMillis();
    }

    /**
     * Record an RTT measurement
     * 
     * @param sampleRTT       The measured RTT in milliseconds
     * @param estimatedRTT    The calculated estimated RTT in milliseconds
     * @param timeoutInterval The calculated timeout interval in milliseconds
     */
    public void recordRTT(long sampleRTT, double estimatedRTT, double timeoutInterval) {
        long timestamp = System.currentTimeMillis() - startTime;
        timestamps.add(timestamp);
        sampleRTTs.add(sampleRTT);
        estimatedRTTs.add(estimatedRTT);
        timeoutIntervals.add(timeoutInterval);
    }

    /**
     * Generate and display a plot of the RTT data
     */
    public void plotRTTData() {
        if (timestamps.isEmpty()) {
            System.out.println("No RTT data to plot.");
            return;
        }

        try {
            // Create a temporary Python script file
            File scriptFile = new File("plot_rtt.py");
            try (PrintWriter writer = new PrintWriter(scriptFile)) {
                writer.println("import matplotlib.pyplot as plt");
                writer.println("import numpy as np");
                writer.println();

                // Write time data (convert to seconds)
                writer.print("time_data = [");
                for (int i = 0; i < timestamps.size(); i++) {
                    writer.print(timestamps.get(i) / 1000.0);
                    if (i < timestamps.size() - 1) {
                        writer.print(", ");
                    }
                }
                writer.println("]");

                // Write sample RTT data
                writer.print("sample_rtt = [");
                for (int i = 0; i < sampleRTTs.size(); i++) {
                    writer.print(sampleRTTs.get(i));
                    if (i < sampleRTTs.size() - 1) {
                        writer.print(", ");
                    }
                }
                writer.println("]");

                // Write estimated RTT data
                writer.print("estimated_rtt = [");
                for (int i = 0; i < estimatedRTTs.size(); i++) {
                    writer.print(estimatedRTTs.get(i));
                    if (i < estimatedRTTs.size() - 1) {
                        writer.print(", ");
                    }
                }
                writer.println("]");

                // Write timeout interval data
                writer.print("timeout_interval = [");
                for (int i = 0; i < timeoutIntervals.size(); i++) {
                    writer.print(timeoutIntervals.get(i));
                    if (i < timeoutIntervals.size() - 1) {
                        writer.print(", ");
                    }
                }
                writer.println("]");

                // Create the plot
                writer.println();
                writer.println("plt.figure(figsize=(10, 6))");
                writer.println("plt.plot(time_data, sample_rtt, 'r-', label='Sample RTT')");
                writer.println("plt.plot(time_data, estimated_rtt, 'b-', label='Estimated RTT')");
                writer.println("plt.plot(time_data, timeout_interval, 'g--', label='Timeout Interval')");
                writer.println("plt.xlabel('Time (seconds)')");
                writer.println("plt.ylabel('RTT (milliseconds)')");
                writer.println("plt.title('RTT Measurements During File Transfer')");
                writer.println("plt.legend(loc='upper right')");
                writer.println("plt.grid(True)");
                writer.println("plt.savefig('rtt_plot.png')");
                writer.println("plt.show()");
            }

            // Execute the Python script - try different Python commands for macOS
            System.out.println("Generating RTT plot...");
            Process process = null;

            try {
                // Try python3 first (most common on macOS)
                process = new ProcessBuilder("python3", "plot_rtt.py").start();
            } catch (IOException e) {
                try {
                    // Fall back to python if python3 fails
                    process = new ProcessBuilder("python", "plot_rtt.py").start();
                } catch (IOException e2) {
                    System.err.println("Failed to execute Python. Make sure Python is installed.");
                    return;
                }
            }

            // Print any output from the Python script
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            // Print any errors from the Python script
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                }
            }

            // Wait for the process to complete
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("RTT plot generated successfully. Saved as 'rtt_plot.png'");
            } else {
                System.err.println("Error generating RTT plot. Exit code: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Error plotting RTT data: " + e.getMessage());
            e.printStackTrace();
        }
    }
}