import java.io.*;
import java.net.Socket;

public class client_task_1 {

    private static final int port = 5001;
    private static final String ipadd = "172.20.10.5";

    public static void main(String[] args) throws IOException {
        try {
            Socket socket = new Socket(ipadd, port);
            System.out.println("Client Connected at server Handshaking port " + socket.getPort());
            System.out.println("Client’s communcation port " + socket.getLocalPort());
            System.out.println("Client is Connected");
            System.out.println("Enter the messages that you want to send and send \"stop\" to close the connection:");

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            BufferedReader readFromSys = new BufferedReader(new InputStreamReader(System.in));
            String str = "";

            DataInputStream input = new DataInputStream(socket.getInputStream());
            // BufferedReader readFromSer = new BufferedReader(new
            // InputStreamReader(input));
            String str2 = "";

            while (!str.equals("stop")) {
                if (input.available() > 0) {
                    str2 = input.readUTF();
                    System.out.println("Client says: " + str2);

                    if (str2.equalsIgnoreCase("stop")) {
                        break;
                    }
                }

                if (System.in.available() > 0) {
                    System.out.println("Send to Server: ");
                    str = readFromSys.readLine();
                    output.writeUTF(str);
                    output.flush();

                    if (str.equalsIgnoreCase("stop")) {
                        break;
                    }
                }
            }

            output.close();
            readFromSys.close();
            input.close();
            // readFromSer.close();
            socket.close();

        } catch (Exception e) {
            System.out.println("Connection Failed" + e.getMessage());
        }

    }
}