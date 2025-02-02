package replicate.vsr.messages;

import replicate.common.Request;
import replicate.common.RequestId;
import replicate.vsr.ViewStampedReplication;

import java.util.Map;

public class DoViewChange extends Request {
    public final int viewNumber;
    public final Map<Integer, ViewStampedReplication.LogEntry> log;
    public final int normalStatusViewNumber;
    public final int opNumber;
    public final int commitNumber;

    public DoViewChange(int viewNumber, Map<Integer, ViewStampedReplication.LogEntry> log, int normalStatusViewNumber, int opNumber, int commitNumber) {
        super(RequestId.DoViewChange);
        this.viewNumber = viewNumber;
        this.log = log;
        this.normalStatusViewNumber = normalStatusViewNumber;
        this.opNumber = opNumber;
        this.commitNumber = commitNumber;
    }
}
