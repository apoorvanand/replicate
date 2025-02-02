package replicate.twophaseexecution.messages;

import replicate.common.Request;
import replicate.common.RequestId;

import java.util.Optional;

public class ExecuteCommandResponse extends Request {
    Optional<String> response;
    boolean isCommitted;
    public ExecuteCommandResponse(Optional<String> response, boolean isCommitted) {
        this();
        this.response = response;
        this.isCommitted = isCommitted;
    }

    public static ExecuteCommandResponse notCommitted() {
        return new ExecuteCommandResponse(Optional.empty(), false);
    }

    public static ExecuteCommandResponse errorResponse(String s) {
        return new ExecuteCommandResponse(Optional.of(s), false);
    }

    public Optional<String> getResponse() {
        return response;
    }

    public boolean isCommitted() {
        return isCommitted;
    }

    private ExecuteCommandResponse() {
        super(RequestId.ExcuteCommandResponse);
    }
}
