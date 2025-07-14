import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class CongestionWindowPlotter {
    private List<Integer> rounds;
    private List<Integer> congestionWindows;
    private List<Integer> slowStartThresholds;
    private List<String> phases; // "SLOW-START", "CONG-AVOID", "FAST-RECOVERY"
    private List<Integer> segments; // Number of segments in flight

    public CongestionWindowPlotter() {
        rounds = new ArrayList<>();
        congestionWindows = new ArrayList<>();
        slowStartThresholds = new ArrayList<>();
        phases = new ArrayList<>();
        segments = new ArrayList<>();
    }

    /**
     * Record a congestion window measurement
     * 
     * @param round            The current transmission round
     * @param congestionWindow The current congestion window size
     * @param ssthresh         The current slow start threshold
     * @param phase            The current congestion control phase
     * @param segmentsInFlight Number of segments currently in flight
     */
    public void recordWindowSize(int round, int congestionWindow, int ssthresh, String phase, int segmentsInFlight) {
        rounds.add(round);
        congestionWindows.add(congestionWindow);
        slowStartThresholds.add(ssthresh);
        phases.add(phase);
        segments.add(segmentsInFlight);
    }

    /**
     * Generate and display a plot of the congestion window data
     */
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

                // Write phases data
                writer.print("phases = [");
                for (int i = 0; i < phases.size(); i++) {
                    writer.print("'" + phases.get(i) + "'");
                    if (i < phases.size() - 1) {
                        writer.print(", ");
                    }
                }
                writer.println("]");

                // Create the plot
                writer.println();
                writer.println("plt.figure(figsize=(15, 8))");

                // Create two subplots
                writer.println("fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(15, 10), height_ratios=[2, 1])");

                // Plot congestion window and ssthresh on first subplot
                writer.println("ax1.plot(rounds, congestion_windows, 'b-', label='Congestion Window', linewidth=2)");
                // writer.println("ax1.plot(rounds, ssthresh, 'r--', label='Slow Start
                // Threshold', linewidth=2)");

                // Plot segments in flight as a simple line plot (removed
                // drawstyle='steps-post')
                writer.println("ax2.plot(rounds, segments, 'g-', label='Segments in Flight', linewidth=2)");
                writer.println("ax2.set_ylabel('Number of Segments')");
                writer.println("ax2.grid(True)");
                writer.println("ax2.legend(loc='upper left')");

                // Configure first subplot
                writer.println("ax1.set_ylabel('Window Size (bytes)')");
                writer.println("ax1.set_title('TCP Congestion Window Evolution')");
                writer.println("ax1.legend(loc='upper left')");
                writer.println("ax1.grid(True)");

                // Configure x-axis
                writer.println("ax2.set_xlabel('Transmission Round')");
                writer.println("plt.xticks(rotation=45)");
                writer.println("plt.tight_layout()");
                writer.println("plt.savefig('congestion_window_plot.png', dpi=300, bbox_inches='tight')");
                writer.println("plt.show()");
            }

            // Execute the Python script
            System.out.println("Generating congestion window plot...");
            Process process = null;

            try {
                // Try python3 first (most common on macOS)
                process = new ProcessBuilder("python3", "plot_window.py").start();
            } catch (IOException e) {
                try {
                    // Fall back to python if python3 fails
                    process = new ProcessBuilder("python", "plot_window.py").start();
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
                System.out.println(
                        "Congestion window plot generated successfully. Saved as 'congestion_window_plot.png'");
            } else {
                System.err.println("Error generating congestion window plot. Exit code: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Error plotting congestion window data: " + e.getMessage());
            e.printStackTrace();
        }
    }
}