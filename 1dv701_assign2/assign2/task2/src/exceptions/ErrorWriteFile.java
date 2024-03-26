package exceptions;

public class ErrorWriteFile extends WebException {
    public ErrorWriteFile() {
        super("Error write to file");
    }
}
