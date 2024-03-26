package exceptions;

public class ErrorFileNotFound extends WebException {
    public ErrorFileNotFound() {
        super("File not found");
    }
}
