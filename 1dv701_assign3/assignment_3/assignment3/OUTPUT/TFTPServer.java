
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class TFTPServer {

  private final int TFTPPORT;
  private final String ROOTDIRECTORY;
  private final TFTPService tftpService = new TFTPService();

  public TFTPServer(int TFTPPORT, String ROOTDIR) {
    this.TFTPPORT = TFTPPORT;
    this.ROOTDIRECTORY = ROOTDIR;
  }

  /**
   * This method starts the TFTP server and listens for incoming requests on a specified port.
   * It receives the requests and spawns a new thread to handle the request.
   * @throws SocketException if there is an error creating the datagram socket
   */

  private void start() throws SocketException {
    byte[] buf = new byte[TFTPSettings.BUFSIZE];

    // Create a datagram socket to listen for incoming requests
    DatagramSocket socket = new DatagramSocket(null);

    // Bind the socket to the local bind point
    SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
    socket.bind(localBindPoint);

    System.out.printf("Listening at port %d: \n", TFTPPORT);
    // Loop continuously to listen for incoming requests
    while (true) {

      // Receive a datagram packet from the socket and parse the request
      final InetSocketAddress clientAddress = receiveFrom(socket, buf);
      final StringBuffer requestedFile = new StringBuffer();//
      final int reqType = parseRQ(buf, requestedFile);//

      // Spawn a new thread to handle the request
      new Thread(() -> {

        try (DatagramSocket sendSocket = new DatagramSocket(0);){

          // Connect the send socket to the client's address
          sendSocket.connect(clientAddress);

          System.out.printf(
                  "%s request for %s from %s using port %d\n",
                  (reqType == OperationType.OP_RRQ) ? "Read" : "Write",
                  requestedFile,
                  clientAddress.getHostName(),
                  clientAddress.getPort()
          );

          // Handle the request based on the request type
          switch (reqType) {
            case OperationType.OP_RRQ:
              requestedFile.insert(0, ROOTDIRECTORY + "/");
              handleRQ(sendSocket, requestedFile.toString(), OperationType.OP_RRQ);
              break;
            case OperationType.OP_WRQ:
              requestedFile.insert(0, ROOTDIRECTORY + "/");
              handleRQ(sendSocket, requestedFile.toString(), OperationType.OP_WRQ);
              break;
            default:
              tftpService.sendError(sendSocket, 4);
              break;
          }
        } catch (SocketException e) {
          System.out.println("connection closed");
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }).start();
    }
  }

  class TFTPService {
    private static final int BLOCK = 1; // initial block number
    private int attM = 0; // number of attempts to send data
    private int socketConnectionAttempt = 0; // number of attempts to establish socket connection
    private DatagramPacket getPacket; // received packet from server

    /**
     * This method handles the write request operation processing.
     * It receives a DatagramSocket instance and an OutputStream instance,
     * and uses them to establish a socket connection and write data to the output stream.
     * @param sendSocket the DatagramSocket instance used to establish the socket connection and send data
     * @param fileOutputStream the OutputStream instance used to write data
     * @throws TimeoutException if a timeout occurs while waiting for a response from the server
     * @throws IOException  if an I/O error occurs during the data transfer
     */

    public void writeRequestOperationProcessing(DatagramSocket sendSocket, OutputStream fileOutputStream) throws TimeoutException, IOException {
      short numOfblock = BLOCK - 1; // block number initialized to initial position minus one
      int counter = 0; // counter to keep track of data block size
      boolean isWrite = true; //  determine if it is a write request
      this.socketConnectionAttempt = 0; // reset socket connection attempt
      this.attM = 0; // reset data send attempt
      do {
        if (socketConnectionAttempt <= TFTPSettings.ATTEMPT_COUNT_MAX && attM <= TFTPSettings.ATTEMPT_COUNT_MAX && isWrite) {
          // check if attempts to establish socket connection and send data are within the limit and it's a write request

          ByteBuffer bufPutReq; // buffer to hold data

          bufPutReq = ByteBuffer.allocate(4); // allocate memory for buffer
          bufPutReq.putShort((short) OperationType.OP_ACK); // add acknowledgement operation type to buffer
          bufPutReq.putShort((short) numOfblock++); // add block number to buffer and increment it

          isWrite = receive_DATA_send_ACK(numOfblock, sendSocket, bufPutReq); // receive data from server and send acknowledgement back

          if (this.getPacket == null) {
            sendError(sendSocket, ErrorCode.UNKNOWN_TRANSFER_ID.getCode()); // send error message if no data received
            break;
          } else {
            int sizePackage = getPacket.getLength() - TFTPSettings.HEADERSIZE; // calculate size of data block received
            fileOutputStream.write(getPacket.getData(), OperationType.OP_ACK, sizePackage); // write data to output stream
            fileOutputStream.flush(); // flush output stream
            counter = sizePackage % (TFTPSettings.BUFSIZE-TFTPSettings.HEADERSIZE); // calculate remaining data block size
          }
        }

        if (counter > 0 || attM == TFTPSettings.ATTEMPT_COUNT_MAX || socketConnectionAttempt == TFTPSettings.ATTEMPT_COUNT_MAX || !isWrite) {
          if (counter > 0) {
            ByteBuffer bufferPutRequest;

            bufferPutRequest = ByteBuffer.allocate(4); // allocate memory for buffer
            bufferPutRequest.putShort((short) OperationType.OP_ACK); // add acknowledgement operation type to buffer
            bufferPutRequest.putShort((short) numOfblock); // add block number to buffer

            DatagramPacket finalAck = new DatagramPacket(bufferPutRequest.array(),
                    bufferPutRequest.array().length); // create datagram packet for final acknowledgement
            sendSocket.send(finalAck); // send final acknowledgement
          }

          attM = 0; // reset data send attempt
          socketConnectionAttempt = 0; // reset socket connection attempt
          break;
        }

      } while (true); // loop indefinitely
    }

    /**
     * Sends ACK packet to server and receives data packet
     * @param blockCounter The expected block number of the received data packet
     * @param datagramSocket The socket used to communicate with the server
     * @param bufPut The ByteBuffer array used to store the ACK packe
     * @return if data packet was received successfully, false otherwise
     * @throws TimeoutException If socket times out during communication
     */


    private boolean receive_DATA_send_ACK(short blockCounter, DatagramSocket datagramSocket, ByteBuffer bufPut) throws TimeoutException {
      // Create ACK packet with the ByteBuffer array
      DatagramPacket ack = new DatagramPacket(bufPut.array(), bufPut.array().length);
      // Create buffer and packet for receiving data
      byte[] buffer = new byte[TFTPSettings.BUFSIZE];
      DatagramPacket receivingPacket = new DatagramPacket(buffer, buffer.length);
      try {
        // Send ACK packet
        datagramSocket.send(ack);
        // Set timeout for socket
        datagramSocket.setSoTimeout(TFTPSettings.RETRANSMISSION_TIME);
        // Receive data packet
        datagramSocket.receive(receivingPacket);
        // Store received packet
        this.getPacket = receivingPacket;
        // Extract block number from received packet
        int sum = ((buffer[2] & 0xff) << 8) | (buffer[3] & 0xff);
        // Check if received block number matches expected block number
        if (blockCounter == sum) {
          System.out.println("Acknowledgment arrived from: " + sum);
        } else {
          // Increment attempt count
          attM++;
          // If maximum attempts not reached, retry sending ACK
          if (attM++ < TFTPSettings.ATTEMPT_COUNT_MAX) {
            return receive_DATA_send_ACK(blockCounter, datagramSocket, bufPut);
          } else {
            // If maximum attempts reached, send error and return false
            sendError(datagramSocket, ErrorCode.NOT_DEFINED.getCode());
            return false;
          }
        }
        // Reset attempt count
        attM = 0;
      } catch (IOException e) {
        // Increment socket connection attempt count
        socketConnectionAttempt++;
        // If maximum attempts not reached, retry sending ACK
        if (socketConnectionAttempt < TFTPSettings.ATTEMPT_COUNT_MAX) {
          System.out.println("Error occurred for block" + blockCounter + "after" + socketConnectionAttempt + " attempts.");
          return receive_DATA_send_ACK(blockCounter, datagramSocket, bufPut);
        } else {
          // If maximum attempts reached, close socket and return false
          System.out.println("Connection closed after multiple attempts.");
          datagramSocket.close();
          return false;
        }
      }
      // Return true if successful
      return true;
    }


    /**
     * This method processes the read request operation.
     * @param sendSocket The DatagramSocket used to send data to the client.
     * @param inStream The InputStream used to read the data from the file.
     * @param buffer The buffer used to hold the data read from the file.
     * @throws TimeoutException Thrown when the operation times out.
     * @throws IOException IOException Thrown when an I/O error occurs.
     */

    public void readRequestOperationProcessing(DatagramSocket sendSocket, InputStream inStream, byte[] buffer) throws TimeoutException, IOException {
      short blockCounter = BLOCK;// block number initialized to initial position
      int count;// counter to keep track of data block size
      this.attM = 0; // reset data send attempt
      this.socketConnectionAttempt = 0;  // reset socket connection attempt
      boolean isRead = true; //  determine if it is a read request
      do {
        int stream = inStream.read(buffer); // read data from the input stream
        if (stream == -1) {
          stream = 0;// if no data is read, set the length to 0
        }
        count = stream % 512; // calculate remaining data block size
        // check if attempts to establish socket connection and send data are within the limit and it's a read request
        if (socketConnectionAttempt < TFTPSettings.ATTEMPT_COUNT_MAX && attM<TFTPSettings.ATTEMPT_COUNT_MAX) {
          ByteBuffer dataBuff = ByteBuffer.allocate(TFTPSettings.BUFSIZE + TFTPSettings.HEADERSIZE + 5);
          dataBuff.putShort((short) OperationType.OP_DAT);
          dataBuff.putShort(blockCounter);// add block number to buffer
          dataBuff.put(buffer);// add data block to buffer
          DatagramPacket datagramPacketForSend = new DatagramPacket(dataBuff.array(), stream + TFTPSettings.HEADERSIZE);
          isRead = send_DATA_receive_ACK(blockCounter++, sendSocket, datagramPacketForSend);
        }
        // check if maximum attempts to establish socket connection or send data have been reached, or if transmission has been completed
        boolean isMaxAttemptsReached = socketConnectionAttempt >= TFTPSettings.ATTEMPT_COUNT_MAX ||
                attM >= TFTPSettings.ATTEMPT_COUNT_MAX;
        boolean isTransmissionCompleted = !isRead || count != 0;
        // reset data send attempt and socket connection attempt, and break the loo
        if (isMaxAttemptsReached || isTransmissionCompleted) {
          socketConnectionAttempt = 0;
          attM = 0;
          break;
        }
      } while (true);
    }

    /**
     * Sends a data packet to the server and waits for acknowledgement.
     * @param blockCounter the block counter of the data packet being sent
     * @param datagramSocket the datagram socket used for sending and receiving packet
     * @param packet the packet containing the data to be sent
     * @return  true if acknowledgement is received, false otherwise
     * @throws TimeoutException if the maximum number of retries is exceeded
     * @throws IOException if an IO error occurs during sending or receiving
     */

    private boolean send_DATA_receive_ACK(short blockCounter, DatagramSocket datagramSocket, DatagramPacket packet) throws TimeoutException, IOException {
      byte[] buffer = new byte[TFTPSettings.BUFSIZE];
      DatagramPacket receivingPacket = new DatagramPacket(buffer, buffer.length);
      int sum;
      int retryCount = 0;
      boolean isAckReceived = false;
      // Attempt to send data packet and receive acknowledgement
      while (!isAckReceived && retryCount < TFTPSettings.ATTEMPT_COUNT_MAX) {
        try {
          datagramSocket.send(packet);
          datagramSocket.setSoTimeout(TFTPSettings.RETRANSMISSION_TIME);
          datagramSocket.receive(receivingPacket);
          sum = (buffer[2] << 8) | (buffer[3] & 255);
          // Check if acknowledgement received matches the block counter of the data packet sent
          if (blockCounter == sum) {
            System.out.println("Acknowledgement sent by:" + sum);
            isAckReceived = true;
          } else {
            System.out.println("Block counter does not match, retransmitting");
          }
        } catch (SocketTimeoutException ste) {
          System.out.println("Socket timeout occurred, retransmitting");
        } catch (IOException e) {
          System.out.println("IOException occurred, retransmitting");
        }
        retryCount++;
      }
      // If maximum retries reached without acknowledgement, send error message and return false
      if (!isAckReceived) {
        System.out.println("Maximum retries reached, sending error message");
        sendError(datagramSocket, ErrorCode.NOT_DEFINED.getCode());
        return false;
      }
      // Return true if acknowledgement is received
      return true;
    }


    /**
     * method sends an error packet to the client using the provided DatagramSocket and error code
     * @param socket The DatagramSocket to use for sending the packet.
     * @param errorCode The error code to include in the error packet.
     * @throws IOException if there's an error while sending the packet.
     */

    public void sendError(DatagramSocket socket, int errorCode) throws IOException {
      Map<Integer, String> errorMessages = new HashMap<>();
      errorMessages.put(0, "Not defined");
      errorMessages.put(1, "File not found");
      errorMessages.put(2, "Access violation");
      errorMessages.put(3, "Disk full or allocation exceed");
      errorMessages.put(4, "Illegal TFTP operation");
      errorMessages.put(5, "Unknown transfer ID");
      errorMessages.put(6, "File already exists");
      errorMessages.put(7, "No such user");
      if (!errorMessages.containsKey(errorCode)) {
        throw new IllegalArgumentException("Invalid error code: " + errorCode);
      }
      String errorMessage = errorMessages.get(errorCode);
      System.out.println("Error code: " + errorCode);
      System.out.println("Error message: " + errorMessage);
      byte[] errorPacket = createErrorPacket(errorCode, errorMessage);
      DatagramPacket sendPacket = new DatagramPacket(errorPacket, errorPacket.length);
      socket.send(sendPacket);
      socket.close();
    }

    /**
     * This method creates an error packet using the provided error code and message.
     * @param errorCode The error code to include in the error packet.
     * @param errorMessage The error message to include in the error packet.
     * @return A byte array representing the error packet.
     */

    private byte[] createErrorPacket(int errorCode, String errorMessage) {
      ByteBuffer buffer = ByteBuffer.allocate(OperationType.OP_ERR + errorMessage.length() + 1);
      buffer.putShort((short) OperationType.OP_ERR);
      buffer.putShort((short) errorCode);
      buffer.put(errorMessage.getBytes());
      buffer.put((byte) 0);
      return buffer.array();
    }
  }

  /**
   * Receives data from a DatagramSocket and returns the InetSocketAddress of the sender.
   * @param socket the DatagramSocket to receive data from
   * @param buf  buf a byte array to store the received data
   * @return the InetSocketAddress of the sender
   */
  private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) {
    DatagramPacket data = new DatagramPacket(buf, buf.length);
    try {
      socket.receive(data);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return new InetSocketAddress(data.getAddress(), data.getPort());
  }


  /**
   *  request message received from the client and extracts the requested file name.
   * @param buf the request message in byte array format.
   * @param requestedFile a StringBuffer object to store the requested file name
   * @return the opcode contained in the request message
   */

  private int parseRQ(byte[] buf, StringBuffer requestedFile) {
    int opcode = ((buf[0] & 0xff) << 8) | (buf[1] & 0xff);
    int filenameEnd = 2;
    while (filenameEnd < buf.length && buf[filenameEnd] != 0) {
      requestedFile.append((char) buf[filenameEnd]);
      filenameEnd++;
    }
    return opcode;
  }

  /**
   * This method handles the read and write requests sent by the client.
   * @param sendSocket  DatagramSocket used to send the response
   * @param requestedFile Path to the file requested by the client
   * @param opcode operation code (RRQ or WRQ).
   * @throws IOException if there's an issue with the I/O operations.
   */

  private void handleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) throws IOException {
    byte[] buffer = new byte[TFTPSettings.BUFSIZE - TFTPSettings.HEADERSIZE];// Set up buffer and file path.

    Path path = Paths.get(requestedFile.split("\n")[0]);
    File pathFile = path.toFile();
    // Process read request.
    switch (opcode) {
      case OperationType.OP_RRQ:
        try{
          // If file doesn't exist, send error packet with error code 1.
          if (!pathFile.exists()) {
            throw new FileNotFoundException(path + "file not found");
          } else if (!pathFile.canWrite() || !pathFile.canRead()) {
            throw new AccessDeniedException(path + "access denied");
          }

          // If everything is fine, start processing the read request operation.
          FileInputStream inStream = new FileInputStream(pathFile);
          tftpService.readRequestOperationProcessing(sendSocket, inStream, buffer);
          // Send error packet with error code FILE_NOT_FOUND if the file cannot be found.
        } catch (AccessDeniedException e) {
          // If file exists but can't be read or written, send error packet with error code ACCESS_VIOLATION.
          tftpService.sendError(sendSocket, ErrorCode.ACCESS_VIOLATION.getCode());
        } catch (FileNotFoundException e) {
          tftpService.sendError(sendSocket, ErrorCode.FILE_NOT_FOUND.getCode());
        } catch (IOException e) {
          // Send error packet with error code NOT_DEFINED for any other I/O issues.
          tftpService.sendError(sendSocket, ErrorCode.NOT_DEFINED.getCode());
        } catch (TimeoutException te) {
          System.out.println("timeout exception");
          // Send error packet with error code PREMATURE_TERMINATION if there's a timeout.
          tftpService.sendError(sendSocket, ErrorCode.PREMATURE_TERMINATION.getCode());
        }


        break;

      // Process write request.
      case OperationType.OP_WRQ:
        FileOutputStream fileOutputStream = null;

        try{
          if (pathFile.exists() && !pathFile.canWrite()) {
            throw new AccessDeniedException(path + "access denied");
          }
          if (pathFile.exists()) {
            //throw new FileAlreadyExistsException(path + " file already exists");
          }
          fileOutputStream = new FileOutputStream(pathFile);
          // If everything is fine, start processing the write request operation.
          tftpService.writeRequestOperationProcessing(sendSocket, fileOutputStream);
          // Send error packet with error code NOT_DEFINED for any I/O issues.
        } catch (AccessDeniedException e) {
          // If file exists but can't be read or written, send error packet with error code ACCESS_VIOLATION.
          tftpService.sendError(sendSocket, ErrorCode.ACCESS_VIOLATION.getCode());
        } catch (FileAlreadyExistsException e) {
          // sendError() method is not implemented here, but it's assumed that it will send the appropriate error packet.
          tftpService.sendError(sendSocket, ErrorCode.FILE_ALREADY_EXISTS.getCode());
        } catch (IOException e) {
          tftpService.sendError(sendSocket, ErrorCode.NOT_DEFINED.getCode());
          // Send error packet with error code PREMATURE_TERMINATION if there's a timeout.
        } catch (TimeoutException te) {
          System.out.println("timeout exception");
          tftpService.sendError(sendSocket, ErrorCode.PREMATURE_TERMINATION.getCode());
        } finally {
          if(fileOutputStream != null){
            fileOutputStream.close();
          }
        }
        break;
      // If operation code is not recognized, send error packet with error code NOT_DEFINED.
      default: 
      System.err.println("wrong request.error packet is sent.");
      tftpService.sendError(sendSocket, ErrorCode.NOT_DEFINED.getCode());
      return; 
    }
  }

  /**
   * The main method of the TFTPServer class, which accepts command-line arguments and starts the server.
   * @param args The command-line arguments passed to the program.
   */

  public static void main(String[] args) {

    int portNumber;
    String directory;

    // Check if the number of arguments is correct
    if (args.length != 2) {
      System.err.print("wrong number of arguments.");
      System.out.println("for example, TFTPServer 7777");
      System.exit(0);
    }

    // Parse the arguments into variables
    portNumber = Integer.parseInt(args[0]);
    directory = args[1];

    // Create an instance of the TFTPServer class with the provided arguments
    TFTPServer server = new TFTPServer(portNumber,  directory);

    // Print out server information
    System.out.println("-------------------------------------------------------------------------");
    System.out.println("Port number:" + portNumber);
    System.out.println("Serving directory:" + directory);
    System.out.println("-------------------------------------------------------------------------");

    // Start the server
    try {
      server.start();
    } catch (SocketException e) {
      e.printStackTrace();
    }

    System.out.println("server's connection starts");
  }

  enum ErrorCode {
    // define enum constants with corresponding integer code and error message
    NOT_DEFINED(0,"Not defined, see error message"),
    FILE_NOT_FOUND(1,"File not found"),
    ACCESS_VIOLATION(2,"Access violation"),
    DISK_FULL_OR_ALLOCATION_EXCEED(3,"Disk full or allocation exceeded"),
    ILLEGAL_TFTP_OPERATION(4,"Illegal TFTP operation"),
    UNKNOWN_TRANSFER_ID(5,"Unknown transfer ID"),
    FILE_ALREADY_EXISTS(6,"File already exist"),
    NO_SUCH_USER(7,"No such user"),
    PREMATURE_TERMINATION(8, "Premature termination");

    private final int codeNum; // declare private field to hold the integer code
    private final String codeMessage; // declare private field to hold the error message

    // constructor that initializes the private fields for each enum constant
    ErrorCode(int code, String codeMessage) {
      this.codeNum = code;
      this.codeMessage= codeMessage;
    }

    // public method that returns the integer code for a given error code
    public int getCode(){
      return codeNum;
    }

    // public method that returns the error message for a given error code
    public String getCodeMessage(){
      return codeMessage;
    }
  }

  public static class OperationType {
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;
  }

  public static class TFTPSettings {
    public static final int BUFSIZE = 516;
    public static final int HEADERSIZE = 4;
    public static final int RETRANSMISSION_TIME = 500;
    public static final int ATTEMPT_COUNT_MAX=7;
  }

}