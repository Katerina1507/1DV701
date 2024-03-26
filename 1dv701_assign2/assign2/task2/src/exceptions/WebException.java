package exceptions;

public class WebException extends RuntimeException {
    public WebException() {
        super("Error on the webserver");
    }

    public WebException(String message){
        super(message);
    }
}
