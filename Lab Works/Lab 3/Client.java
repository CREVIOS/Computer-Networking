import java.io.*;
import java.net.Socket;

public class Client {
    private static final int port = 5001;
    private static final String ipadd = "localhost";

    public static void main(String[] args) throws IOException {
        try {
            Socket socket = new Socket(ipadd, port);
            System.out.println("Client Connected at server Handshaking port " + socket.getPort());
            System.out.println("Client's communication port " + socket.getLocalPort());
            System.out.println("Client is Connected");

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            BufferedReader keyIn = new BufferedReader(new InputStreamReader(System.in));
            DataInputStream in = new DataInputStream(socket.getInputStream());

            String msg = "";
            String resp = "";

            while (true) {

                boolean authed = false;
                while (!authed) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("AUTH:");

                    System.out.println("Send to Bank - \nCard number: ");
                    sb.append(keyIn.readLine().toString()).append(":");

                    System.out.println("\nPIN number: ");
                    sb.append(keyIn.readLine().toString());

                    msg = sb.toString();
                    out.writeUTF(msg);
                    out.flush();

                    resp = in.readUTF();
                    System.out.println("Bank replies: " + resp);

                    if (resp.equals("AUTH_OK")) {
                        authed = true;
                    } else if (resp.equals("AUTH_FAIL")) {
                        System.out.println("Authentication failed. Please try again.");
                    }
                }

                boolean sessionActive = true;
                while (sessionActive && authed) {
                    System.out.println("\nChoose operation:");
                    System.out.println("1. Check Balance");
                    System.out.println("2. Withdraw");
                    System.out.println("3. Logout");
                    System.out.println("4. Exit Program");

                    String choice = keyIn.readLine().toString();

                    switch (choice) {
                        case "1":

                            out.writeUTF("BALANCE_REQ");
                            out.flush();

                            resp = in.readUTF();
                            System.out.println("Bank replies: " + resp);
                            out.writeUTF("ACK");
                            out.flush();
                            break;

                        case "2":

                            System.out.println("Enter withdrawal amount: ");
                            String amount = keyIn.readLine().toString();
                            out.writeUTF("WITHDRAW:" + amount);
                            out.flush();

                            resp = in.readUTF();
                            System.out.println("Bank replies: " + resp);

                            if (resp.startsWith("WITHDRAW_COOLDOWN:")) {
                                System.out.println("Withdrawal is on cooldown. Please try again later.");
                            }

                            out.writeUTF("ACK");
                            out.flush();
                            break;

                        case "3":

                            out.writeUTF("LOGOUT");
                            out.flush();

                            resp = in.readUTF();
                            System.out.println("Bank replies: " + resp);

                            if (resp.equals("LOGOUT_OK")) {
                                System.out.println("Successfully logged out.");
                                sessionActive = false;
                            }
                            break;

                        case "4":

                            out.writeUTF("STOP");
                            out.flush();
                            in.readUTF();
                            System.out.println("Exiting program.");
                            out.close();
                            keyIn.close();
                            in.close();
                            socket.close();
                            return;

                        default:
                            System.out.println("Invalid option. Please try again.");
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Connection Failed: " + e.getMessage());
        }
    }
}