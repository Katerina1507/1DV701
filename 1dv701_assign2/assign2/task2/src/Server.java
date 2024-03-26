import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;

/**
 * the class Server, attributes port and directory
 * receiving requests, server starts
 */

public class Server {
    private int port;
    private String directory;

    /**
     * creating constructor
     * @param port is integer,
     * listening for connection on port
     * @param directory used to serve
     */

    public Server(int port, String directory) {
        this.port = port;
        this.directory = directory;
    }

    /**
     * creating new thread
     * assign new client to separate thread
     */

    void start() {
        try (var server = new ServerSocket(this.port)) {
            while (true) {
                var socket = server.accept();// for connection to be made to this socket and accepts it.
                var thread = new Client(socket, this.directory);
                thread.run();
                System.out.println("Assigned new client to a separate thread");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param args checking the order of the arguments
     * @throws IOException, using try and catch
     * args[0]-port, args[1]-sourcePath
     */

    public static void main(String[] args) throws IOException {
        if (args.length == 2) {
            int port = 0;
            String sourcePath="";
            try {
                 port = Integer.parseInt(args[0]);
                 sourcePath = args[1];
            }catch (Exception e){
                System.out.println("Incorrect arguments"); //not correct order
                return;
            }
                System.out.print(sourcePath);
            File file = new File("../"+sourcePath);
            // checking if file doesn't exist , giving the message
                if (!file.exists()){
                    System.out.println("not correct path to the directory public ");
                    return;
                }
            // if connection done, everything in order, it will be message that server is running
            try {
                System.out.println("Web server is running... "
                        + "\nListening for connection on port " + port
                        + "\ndirectory " + sourcePath
                        + "\nPress ctrl+c to terminate the program");
                new Server(port, sourcePath).start();
            }
            catch (Exception e){
                System.out.println(e.getMessage());
            }
        }
        else {
            System.out.println("Incorrect arguments");
        }
    }
}
