import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class RandomAccountGenerator {
    private String accNum;
    private int pin;
    private int amt;
    private String cardNum;
    int id = 0;
    private Bank bank;

    RandomAccountGenerator(Bank bank) {
        this.bank = bank;
    }

    public void generateAccount() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        String numStr = "0123456789";

        while (id < 11) {
            id++;
            this.pin = 1000 + random.nextInt(999);
            this.amt = 1000 + random.nextInt(9999);

            sb.setLength(0);
            for (int i = 0; i < 6; i++) {
                int index = random.nextInt(numStr.length());
                sb.append(numStr.charAt(index));
            }
            this.cardNum = sb.toString();

            sb.setLength(0);
            for (int i = 0; i < 8; i++) {
                int index = random.nextInt(numStr.length());
                sb.append(numStr.charAt(index));
            }
            this.accNum = sb.toString();

            Account account = new Account(this.accNum, this.pin, this.cardNum, this.amt);
            this.bank.addAccount(account);
        }
    }
}

class Account {
    double bal = 0;
    private String accNum;
    private int pin;
    private String cardNum;
    private Map<String, Long> withdrawCooldowns = new ConcurrentHashMap<>();

    Account(String accNum, int pin, String cardNum, double bal) {
        this.accNum = accNum;
        this.pin = pin;
        this.cardNum = cardNum;
        this.bal = bal;
    }

    public String getAccNum() {
        return this.accNum;
    }

    public int getPin() {
        return this.pin;
    }

    public String getCardNum() {
        return this.cardNum;
    }

    public synchronized boolean withdraw(double amount) {
        if (isWithdrawOnCooldown()) {
            return false;
        }

        double remains = this.bal - amount;
        if (remains >= 1000) {
            this.bal -= amount;
            setWithdrawCooldown();
            return true;
        } else {
            return false;
        }
    }

    public synchronized void setWithdrawCooldown() {
        withdrawCooldowns.put("lastWithdraw", System.currentTimeMillis());
    }

    public synchronized boolean isWithdrawOnCooldown() {
        Long lastWithdraw = withdrawCooldowns.get("lastWithdraw");
        if (lastWithdraw == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long elapsedSeconds = (currentTime - lastWithdraw) / 1000;
        return elapsedSeconds < 30;
    }

    public synchronized long getWithdrawCooldownRemaining() {
        Long lastWithdraw = withdrawCooldowns.get("lastWithdraw");
        if (lastWithdraw == null) {
            return 0;
        }

        long currentTime = System.currentTimeMillis();
        long elapsedSeconds = (currentTime - lastWithdraw) / 1000;

        if (elapsedSeconds >= 30) {
            return 0;
        } else {
            return 30 - elapsedSeconds;
        }
    }

    public synchronized double getBal() {
        return this.bal;
    }
}

class Bank {
    private HashMap<String, Account> accounts;
    private Set<String> doneTxns;
    private Map<String, String> cachedResps;

    Bank() {
        this.accounts = new HashMap<String, Account>();
        this.doneTxns = Collections.synchronizedSet(new HashSet<>());
        this.cachedResps = new ConcurrentHashMap<>();
    }

    public HashMap<String, Account> getAccounts() {
        return this.accounts;
    }

    public void addAccount(Account account) {
        this.accounts.put(account.getCardNum(), account);
    }

    public Account getAccount(String cardNum) {
        return this.accounts.get(cardNum);
    }

    public synchronized void addDoneTxn(String txnId) {
        doneTxns.add(txnId);
    }

    public synchronized boolean isTxnDone(String txnId) {
        return doneTxns.contains(txnId);
    }

    public synchronized void cacheResp(String txnId, String resp) {
        cachedResps.put(txnId, resp);
    }

    public synchronized String getCachedResp(String txnId) {
        return cachedResps.get(txnId);
    }
}

class ATM extends Thread {
    private Socket clientSocket;
    private DataInputStream in;
    private DataOutputStream out;
    private BufferedReader keyboardIn;
    private static int id;
    private Bank bank;
    private Account authAcc = null;
    private String authCard = null;
    private boolean isAuthed = false;

    ATM(Socket socket, int id, Bank bank) {
        this.bank = bank;
        this.clientSocket = socket;
        this.id = id;
        this.setName("ATM " + this.id + ": ");
    }

    public void run() {
        try {
            System.out.println("Client Connected: " + clientSocket.getInetAddress().getHostAddress());

            in = new DataInputStream(this.clientSocket.getInputStream());
            out = new DataOutputStream(this.clientSocket.getOutputStream());
            keyboardIn = new BufferedReader(new InputStreamReader(System.in));

            String clientMsg = "";

            while (!clientMsg.equalsIgnoreCase("stop")) {
                if (in.available() > 0) {
                    clientMsg = in.readUTF();
                    System.out.println(Thread.currentThread().getName() + " received: " + clientMsg);

                    String[] parts = clientMsg.split(":");
                    String msgType = parts[0].toUpperCase();

                    String txnId = "";
                    if (msgType.equals("WITHDRAW")) {
                        txnId = UUID.randomUUID().toString().substring(0, 8);
                    } else if (parts.length >= 2) {
                        txnId = parts[1];
                    }

                    if (msgType.equals("ACK")) {
                        if (parts.length >= 2) {
                            bank.addDoneTxn(txnId);
                            System.out.println("Transaction " + txnId + " acknowledged");
                        }
                        continue;
                    }

                    if (bank.isTxnDone(txnId) && !txnId.isEmpty()) {
                        String cachedResp = bank.getCachedResp(txnId);
                        if (cachedResp != null) {
                            out.writeUTF(cachedResp);
                            out.flush();
                            System.out
                                    .println(Thread.currentThread().getName() + " sent cached response: " + cachedResp);
                            continue;
                        }
                    }

                    String resp = "";

                    switch (msgType) {
                        case "AUTH":
                            if (isAuthed) {
                                resp = "ERROR Already authenticated";
                            } else if (parts.length >= 3) {
                                String cardNum = parts[1];
                                String pin = parts[2];
                                resp = handleAuth(cardNum, pin, txnId);
                            } else {
                                resp = "ERROR Invalid AUTH format";
                            }
                            break;

                        case "BALANCE_REQ":
                            if (!isAuthed) {
                                resp = "NOT_AUTHENTICATED:" + txnId;
                            } else {
                                resp = handleBalanceRequest();
                            }
                            break;

                        case "WITHDRAW":
                            if (!isAuthed) {
                                resp = "NOT_AUTHENTICATED:" + txnId;
                            } else if (parts.length >= 2) {
                                double amount = Double.parseDouble(parts[1]);
                                resp = handleWithdrawal(amount);
                            } else {
                                resp = "INSUFFICIENT_FUNDS";
                            }
                            break;

                        case "LOGOUT":
                            if (isAuthed && authCard != null) {
                                System.out.println(
                                        Thread.currentThread().getName() + " manual logout for card: " + authCard);

                                authAcc = null;
                                authCard = null;
                                isAuthed = false;

                                resp = "LOGOUT_OK";
                            } else {
                                resp = "NOT_AUTHENTICATED";
                            }
                            break;

                        case "STOP":
                            resp = "GOODBYE!!";
                            break;

                        default:
                            resp = "UNKNOWN_COMMAND:" + txnId;
                    }

                    out.writeUTF(resp);
                    out.flush();

                    if (!txnId.isEmpty()) {
                        bank.cacheResp(txnId, resp);
                    }

                    System.out.println(Thread.currentThread().getName() + " sent: " + resp);
                }

                if (System.in.available() > 0) {
                    String serverMsg = keyboardIn.readLine();
                    if (serverMsg.equalsIgnoreCase("stop")) {
                        break;
                    }
                }

                Thread.sleep(100);
            }
        } catch (Exception e) {
            System.out.println("Error handling client: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                in.close();
                out.close();
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Connection closed for " + Thread.currentThread().getName());
        }
    }

    private String handleAuth(String cardNum, String pin, String txnId) {
        try {
            Account account = bank.getAccount(cardNum);

            if (account == null) {
                return "AUTH_FAIL";
            }

            if (account.getPin() == Integer.parseInt(pin)) {
                authAcc = account;
                authCard = cardNum;
                isAuthed = true;
                return "AUTH_OK";
            } else {
                authAcc = null;
                authCard = null;
                isAuthed = false;
                return "AUTH_FAIL";
            }
        } catch (NumberFormatException e) {
            return "AUTH_FAIL Invalid PIN format";
        }
    }

    private String handleBalanceRequest() {
        if (authAcc == null) {
            return "NOT_AUTHENTICATED";
        }

        double balance = authAcc.getBal();
        return "BALANCE_RES" + ":" + balance;
    }

    private String handleWithdrawal(double amount) {
        if (authAcc == null) {
            return "NOT_AUTHENTICATED";
        }

        if (authAcc.isWithdrawOnCooldown()) {
            long remainTime = authAcc.getWithdrawCooldownRemaining();
            return "WITHDRAW_COOLDOWN:" + remainTime + " seconds remaining";
        }

        boolean success = authAcc.withdraw(amount);
        if (success) {
            return "WITHDRAW_OK";
        } else {
            return "INSUFFICIENT_FUNDS";
        }
    }
}

public class Server {
    private static final int PORT = 5001;
    private static int id = 0;

    public static void main(String[] args) {
        Bank newBank = new Bank();
        RandomAccountGenerator generator = new RandomAccountGenerator(newBank);
        generator.generateAccount();

        HashMap<String, Account> accounts = newBank.getAccounts();

        System.out.println("Available accounts for testing:");
        for (Map.Entry<String, Account> acc : accounts.entrySet()) {
            System.out.println("Card number: " + acc.getKey() + " | PIN: " + acc.getValue().getPin() +
                    " | Balance: " + acc.getValue().getBal());
        }

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Bank server started on port " + serverSocket.getLocalPort());
            System.out.println("Waiting for ATM connections...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                ATM newClient = new ATM(clientSocket, ++id, newBank);
                newClient.start();
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}