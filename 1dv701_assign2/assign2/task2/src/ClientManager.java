import exceptions.ErrorFileNotFound;
import exceptions.ErrorReadFile;
import exceptions.ErrorWriteFile;

import java.io.*;
import java.util.Date;

/**
 * the class Client Manager
 * attributes header, content
 */
public class ClientManager {

    private PrintWriter header;  //PrintWriter-implementation of Writer class. It is used to print the formatted representation of objects to the text-output stream.
    private DataOutputStream content; //DataOutputStream- represents an output stream and is intended for writing data

    /**
     * creating constructor
     * @param header using for http response
     * @param content file's content
     */
    public ClientManager(PrintWriter header, DataOutputStream content) {
        this.header = header;
        this.content = content;
    }

    /**
     * @param file reading file, reading bytes
     * @return bytes
     * @throws FileNotFoundException
     */

    public byte[] readFile(File file) throws FileNotFoundException {
        byte[] bytes = new byte[(int) file.length()];
        // obtains input bytes from a file, reading streams of raw bytes(for ex image data)
        try(FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        } catch (IOException e) {
            throw new ErrorReadFile();
        }
        return bytes;
    }

    /**
     * checking type files
     * @param requestedFile requested to the path
     * @return contentType
     */

    public String getContentType(String requestedFile) {
        String contentType;
        if (requestedFile.endsWith(".html") || requestedFile.endsWith(".htm"))
            contentType = "text/html";
        else if (requestedFile.endsWith(".jpeg") || requestedFile.endsWith(".jpg"))
            contentType = "image/jpeg";
        else if (requestedFile.endsWith(".png"))
            contentType = "image/png";
        else
            throw new IllegalArgumentException("Unknown content type");
        return contentType;
    }

    /**
     *creating response to the request
     * @param path, it is path
     * @param status status code (http)
     */

    public void outToClient(String path, String status) {
        File file = new File(path);
        byte[] filedata = null;
        try {
            filedata = readFile(file);
        } catch (FileNotFoundException exc) {
            throw new ErrorFileNotFound();
        }
        String contentType = getContentType(path);
        //response of HTTP header
        header.println("HTTP/1.1 " + status);
        header.println("Server:WebServer: 1.0");
        header.println("Date: " + new Date());
        header.println("Content-type: " + contentType);
        header.println("Content-length: " + file.length());
        header.println();
        header.flush();
        try {
            content.write(filedata, 0, filedata.length);
            content.flush();
        } catch (IOException e) {
           throw new ErrorWriteFile();
        }
    }
}
