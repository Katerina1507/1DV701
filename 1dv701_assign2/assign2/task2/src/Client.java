import exceptions.ErrorFileNotFound;
import exceptions.ErrorReadFile;
import exceptions.ErrorWriteFile;

import java.io.*;
import java.net.Socket;

/**
 * creating class Client
 * attributes socket and path
 */

public class Client implements Runnable {
    private Socket socket;
    private String path;


    /**
     * create the constructor
     * @param socket is client
     * @param directory which is used to serve
     */
    public Client(Socket socket, String directory) {
        this.socket = socket;
        this.path = directory;
    }

    /**
     * printing information for the user
     */
    @Override
    public void run() {
        String host = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();

        System.out.println("Host: " + host);
        try {
            start();
        } catch (IOException e) {
            System.out.println(
                    "Connection from " + host + " is closed, cause by user side");
            e.printStackTrace();
        }
        System.out.println("Closed connection from " + host);
    }

    /**
     * reading  input from a user (using BufferedReader)
     * @throws IOException
     */
    public void start() throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream());// returns and output stream for this socket
        DataOutputStream outToClient = new DataOutputStream(socket.getOutputStream());

        ClientManager generator = new ClientManager(out, outToClient); // need for HTTP response
        String clientRequest = input.readLine();// reading client's request


        /**
         * declare path,
         * check if a client request is empty or null
         */
        path = "../" + path;
        try {
            if (clientRequest == null || clientRequest.isEmpty()) {
                generator.outToClient(path + "/500.html", "500 Internal Server Error"); // 500 status code
            } else {
                String[] a = clientRequest.split(" ");//here i split client's request
                System.out.println("Request: Method " + a[0] + ", Path: " + a[1] + ", Version: " + a[2]);
                a = clientRequest.split(" ");
                String method = a[0].toUpperCase();
                String arguments = a[1];

                /**
                 * checking directory
                 * redirect to index.html
                 */
                if (arguments.endsWith("/")) {
                    arguments += "index.html";
                } else if (!arguments.contains(".")) {
                    arguments += "/index.html";
                }

                String status;
                String page;
                if (method.contains("GET")) {
                    if (arguments.contains("user1/image.jpg")) {
                        status = "302 Found"; // 302 status code
                        page = "/302.html";
                    }
                    else {
                        File file = new File(path + "\\" + arguments);
                        if (!file.exists()) {
                            status = "404 File Not Found"; // 404 status code
                            page = "/404.html";
                            System.out.println("Server request file does not exist");
                        } else {
                            status = "200 OK"; // 200 status code
                            page = "\\" + arguments;
                            System.out.println("Server request file exist");
                        }
                    }
                } else {
                    status = "501 Not Implemented"; // 501 status code
                    page = "/501.html";
                }
                generator.outToClient(path + page, status);
            }
        }
        catch (ErrorFileNotFound exc) {
            generator.outToClient(path + "/404.html", "404 File Not Found");
        }
        catch (ErrorReadFile | ErrorWriteFile exc) {
            generator.outToClient(path + "/500.html", "500 Internal Server Error"); // 500 status code
        }
        catch (Exception exc) {
            System.out.println(exc.getMessage());
        }
        out.close();
        outToClient.close();
    }
}
