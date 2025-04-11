import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;


class Checker{

    public boolean isPrime(String str){
        int n= Integer.parseInt(str);
        for (int i = 2; i <= Math.sqrt(n); i++) {
            if (n % i == 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isPalindrome(String str){
        
        if(str.equals(new StringBuilder(str).reverse().toString())){
            return true;
        }
        return false;
    }

    public String CapToSmall(String str){
        StringBuilder sb = new StringBuilder(str);

        for (int i = 0; i < str.length(); i++) {
            if(str.charAt(i)>=65 && str.charAt(i)<=90){
                char ch= Character.toLowerCase(str.charAt(i));
                sb.setCharAt(i, ch);
            }
        }

        str= sb.toString();
        return str;}

}




public class Server_1 {
    private static final int PORT = 5001;

    public static void main(String[] args) {
        try (
                ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started at port: " + serverSocket.getLocalPort());
            System.out.println("Waiting for client connection...");

            Socket clientSocket = serverSocket.accept();
            System.out.println("Client Connected: " + clientSocket.getInetAddress().getHostAddress());

            DataInputStream input = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream output = new DataOutputStream(clientSocket.getOutputStream());
            BufferedReader keyboardInput = new BufferedReader(new InputStreamReader(System.in));

            String clientMessage = "";
            String serverMessage = "";
            String message = "To find Prime Number write 'prime 10',\n To find Palindrome write 'palindrome 122',\n To convert Capital to Small 'cts string',\n To exit write 'stop' \n";
            output.writeUTF(message);

            try {
                while (!clientMessage.equalsIgnoreCase("stop") && !serverMessage.equalsIgnoreCase("stop")) {
                    if (input.available() > 0) {
                        clientMessage = input.readUTF();
                        System.out.println("Client says: " + clientMessage);
                        String[] arr = clientMessage.split(" ",2);
                        Checker checker = new Checker();
                        if (arr[0].toLowerCase().equals("prime")) {
                            if (checker.isPrime(arr[1])) {
                                serverMessage = "Prime";
                            } else {
                                serverMessage = "Not Prime";
                            }
                            output.writeUTF(serverMessage);
                            output.flush();
                        }
                        else if (arr[0].toLowerCase().equals("palindrome")) {
                            if (checker.isPalindrome(arr[1])) {
                                serverMessage = "Palindrome";
                            } else {
                                serverMessage = "Not Palindrome";
                            }
                            output.writeUTF(serverMessage);
                            output.flush();
                        }
                        else if (arr[0].toLowerCase().equals("cts")) {
                            serverMessage = checker.CapToSmall(arr[1]);
                            output.writeUTF(serverMessage);
                            output.flush();
                        }
                        else if (clientMessage.equalsIgnoreCase("stop")) {
                            break;
                        }
                    }

                    if (System.in.available() > 0) {
                        System.out.print("Enter message to send: ");
                        serverMessage = keyboardInput.readLine();
                        output.writeUTF(serverMessage);
                        output.flush();

                        if (serverMessage.equalsIgnoreCase("stop")) {
                            break;
                        }
                    }

                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                System.out.println("Connection interrupted");
            } finally {
                input.close();
                output.close();
                clientSocket.close();
                System.out.println("Connection closed");
            }

        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}