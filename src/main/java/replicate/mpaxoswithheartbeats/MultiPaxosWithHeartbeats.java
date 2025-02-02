package replicate.mpaxoswithheartbeats;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import replicate.common.*;
import replicate.multipaxos.PaxosState;
import replicate.multipaxos.messages.FullLogPrepareResponse;
import replicate.net.InetAddressAndPort;
import replicate.net.requestwaitinglist.RequestWaitingList;
import replicate.paxos.messages.CommitResponse;
import replicate.paxos.messages.GetValueResponse;
import replicate.paxos.messages.ProposalResponse;
import replicate.paxoslog.PaxosResult;
import replicate.paxoslog.messages.CommitRequest;
import replicate.paxoslog.messages.PrepareRequest;
import replicate.paxoslog.messages.ProposalRequest;
import replicate.quorum.messages.GetValueRequest;
import replicate.twophaseexecution.messages.ExecuteCommandRequest;
import replicate.twophaseexecution.messages.ExecuteCommandResponse;
import replicate.vsr.CompletionCallback;
import replicate.wal.Command;
import replicate.wal.SetValueCommand;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

enum ServerRole {
    Leader, Follower, LookingForLeader
}

public class MultiPaxosWithHeartbeats extends Replica {
    private static Logger logger = LogManager.getLogger(MultiPaxosWithHeartbeats.class);
    private final SetValueCommand NO_OP_COMMAND = new SetValueCommand("", "");
    Duration randomElectionTimeout;
    //Paxos State
    Map<Integer, PaxosState> paxosLog = new HashMap<>();
    Map<String, String> kv = new HashMap<>();
    final int serverId;
    ServerRole role;

    public MultiPaxosWithHeartbeats(String name, SystemClock clock, Config config, InetAddressAndPort clientAddress, InetAddressAndPort peerConnectionAddress, List<InetAddressAndPort> peers) throws IOException {
        super(name, config, clock, clientAddress, peerConnectionAddress, peers);
        this.serverId = config.getServerId();
        requestWaitingList = new RequestWaitingList(clock);
        this.role = ServerRole.Follower;
        super.markHeartbeatReceived(); //
        setRandomElectionTimeout();
    }

    private void setRandomElectionTimeout() {
        this.randomElectionTimeout = heartbeatTimeout.plus(Duration.ofMillis(new Random().nextInt(1, 1000)));
        logger.info(getName() + " set randomElectionTimeout=" + randomElectionTimeout);
    }

    @Override
    protected void registerHandlers() {
        //client rpc
        handlesRequestAsync(RequestId.ExcuteCommandRequest, this::handleClientExecuteCommand, ExecuteCommandRequest.class);
        handlesRequestAsync(RequestId.GetValueRequest, this::handleClientGetValueRequest, GetValueRequest.class);

        //peer to peer message passing
        handlesMessage(RequestId.Prepare, this::handleFullLogPrepare, PrepareRequest.class);
        handlesMessage(RequestId.Promise, this::handleFullLogPromise, FullLogPrepareResponse.class);

        handlesMessage(RequestId.ProposeRequest, this::handlePaxosProposal, ProposalRequest.class);
        handlesMessage(RequestId.ProposeResponse, this::handlePaxosProposalResponse, ProposalResponse.class);

        handlesMessage(RequestId.Commit, this::handlePaxosCommit, CommitRequest.class);
        handlesMessage(RequestId.CommitResponse, this::handlePaxosCommitResponse, CommitResponse.class);

        handlesMessage(RequestId.HeartBeatRequest, this::handleHeartbeatRequest, HeartbeatRequest.class);
        handlesMessage(RequestId.HeartBeatResponse, this::handleHeartbeatResponse, HeartbeatResponse.class);
    }

    private void handlePaxosCommitResponse(Message<CommitResponse> commitResponseMessage) {
        handleResponse(commitResponseMessage);
    }

    private void handlePaxosProposalResponse(Message<ProposalResponse> proposalResponseMessage) {
        handleResponse(proposalResponseMessage);
    }

    private void handleFullLogPromise(Message<FullLogPrepareResponse> fullLogPrepareResponseMessage) {
        handleResponse(fullLogPrepareResponseMessage);
    }

    private void handleHeartbeatResponse(Message<HeartbeatResponse> heartbeatResponseMessage) {
        if (!heartbeatResponseMessage.getRequest().success) {
            becomeFollower(heartbeatResponseMessage.getRequest().fullLogBallot);
        }
    }

    private void handleHeartbeatRequest(Message<HeartbeatRequest> message) {
        markHeartbeatReceived();
        if (message.getRequest().ballot.isAfter(this.fullLogBallot)) {
            becomeFollower(message.getRequest().ballot);
            HeartbeatResponse request = new HeartbeatResponse(true, this.fullLogBallot);
            sendOneway(message.getFromAddress(), request, message.getCorrelationId());
        } else if (message.getRequest().ballot.equals(this.fullLogBallot)) {
            HeartbeatResponse request = new HeartbeatResponse(true, this.fullLogBallot);
            sendOneway(message.getFromAddress(), request, message.getCorrelationId());
        } else if (this.fullLogBallot.isAfter(message.getRequest().ballot)) {
            HeartbeatResponse request = new HeartbeatResponse(false, this.fullLogBallot);
            sendOneway(message.getFromAddress(), request, message.getCorrelationId());
        }
    }

//Following heartbeat implementation can be used for two purposes
// 1. Auto trigger election by checking if existing leader fails.
// 2. For leader to notify followers about its availability.

    @Override
    public void sendHeartbeats() {
        super.sendOnewayMessageToOtherReplicas(new HeartbeatRequest(fullLogBallot));
    }

    @Override
    public void checkLeader() {
        Duration timeSinceLastHeartbeat = elapsedTimeSinceLastHeartbeat();
        if (timeSinceLastHeartbeat.compareTo(randomElectionTimeout) > 0) {
            logger.info(getName() + " heartbeat timedOut after " + timeSinceLastHeartbeat.toMillis() + "ms");
            this.role = ServerRole.LookingForLeader;
            heartbeatChecker.stop();
            heartBeatScheduler.stop();
            leaderElection();
        }
    }

    private CompletableFuture<ExecuteCommandResponse> handleClientExecuteCommand(ExecuteCommandRequest t) {
        if (role != ServerRole.Leader) {
            return CompletableFuture.failedFuture(new RuntimeException("Can not process requests as the node is not the leader"));
        }
        var commitCallback = new CompletionCallback<ExecuteCommandResponse>();
        CompletableFuture<PaxosResult> appendFuture = append(t.command, commitCallback);
        return appendFuture.thenCompose(r -> commitCallback.getFuture());
    }

    private CompletableFuture<GetValueResponse> handleClientGetValueRequest(GetValueRequest request) {
        var commitCallback = new CompletionCallback<ExecuteCommandResponse>();
        CompletableFuture<PaxosResult> appendFuture = append(NO_OP_COMMAND.serialize(), commitCallback);
        return appendFuture.thenCompose(r -> commitCallback.getFuture())
                .thenApply(r -> {
                    return new GetValueResponse(Optional.ofNullable(kv.get(request.getKey())));
                });
    }

    RequestWaitingList requestWaitingList;

    AtomicInteger maxKnownPaxosRoundId = new AtomicInteger(1);
    AtomicInteger logIndex = new AtomicInteger(0);

    public CompletableFuture<PaxosResult> append(byte[] initialValue, CompletionCallback<ExecuteCommandResponse> callback) {
        CompletableFuture<PaxosResult> appendFuture = doPaxos(initialValue, callback);
        return appendFuture.thenCompose((result) -> {
            if (result.value.stream().allMatch(v -> v != initialValue)) {
                logger.info("Could not append proposed value to " + logIndex + ". Trying next index");
                return append(initialValue, callback);
            }
            return CompletableFuture.completedFuture(result);
        });
    }


    private CompletableFuture<PaxosResult> doPaxos(byte[] value, CompletionCallback<ExecuteCommandResponse> callback) {
        return doPaxos(fullLogBallot, logIndex.getAndIncrement(), value, callback);
    }

    private CompletableFuture<PaxosResult> doPaxos(MonotonicId monotonicId, int index, byte[] initialValue, CompletionCallback<ExecuteCommandResponse> callback) {
        return sendProposeRequest(index, initialValue, monotonicId)
                .thenCompose(proposedValue -> {
                    //Once the index at which the command is committed reaches 'high-watermark', return the result.
                    if (proposedValue == initialValue) {
                        requestWaitingList.add(index, callback);
                    }
                    return sendCommitRequest(index, proposedValue, monotonicId)
                            .thenApply(r -> new PaxosResult(Optional.of(proposedValue), true));
                });
    }


    private CompletableFuture<Boolean> sendCommitRequest(int index, byte[] value, MonotonicId monotonicId) {
        AsyncQuorumCallback<CommitResponse> commitCallback = new AsyncQuorumCallback<CommitResponse>(getNoOfReplicas(), c -> c.success);
        sendMessageToReplicas(commitCallback, RequestId.Commit, new CommitRequest(index, value, monotonicId));
        return commitCallback.getQuorumFuture().thenApply(result -> true);
    }


    private CompletableFuture<byte[]> sendProposeRequest(int index, byte[] proposedValue, MonotonicId monotonicId) {
        var proposalCallback = new AsyncQuorumCallback<ProposalResponse>(getNoOfReplicas(), p -> p.success);
        logger.debug(getName() + " proposing " + proposedValue + " for index " + index);
        sendMessageToReplicas(proposalCallback, RequestId.ProposeRequest, new ProposalRequest(monotonicId, index, proposedValue));
        return proposalCallback.getQuorumFuture().thenApply(r -> proposedValue);
    }

    public void leaderElection() {
        logger.info(getName() + " triggering election");
        heartbeatChecker.stop();
        //if future completes successfully, phase1 is complete and this node can be the leader.
        runElection().whenComplete((result, throwable) -> {
            if (throwable != null) {
                logger.error(getName() + " could not complete election for " + fullLogBallot);
                logger.error(throwable);
                return;

            }
            this.isLeader = true;
            this.fullLogBallot = result;
            this.role = ServerRole.Leader;
            heartbeatChecker.stop();
            heartBeatScheduler.start();
            logger.info(getName() + " is leader for " + result);
        });
    }

    public CompletableFuture<MonotonicId> runElection() {
        MonotonicId newBallot = new MonotonicId(maxKnownPaxosRoundId.incrementAndGet(), serverId);
        logger.info(getName() + " triggering election with ballot " + newBallot);
        return sendFullLogPrepare(newBallot).thenCompose(prepareResponse -> {
            List<FullLogPrepareResponse> promises = prepareResponse.values().stream().toList();
            for (FullLogPrepareResponse promise : promises) {
                mergeLog(promise);
            }
            return sendProposalRequestsForUnCommittedEntries(newBallot);
        });
    }

    boolean isLeader = false;

    private CompletableFuture<MonotonicId> sendProposalRequestsForUnCommittedEntries(MonotonicId newBallot) {
        List<CompletableFuture> commitFutures = new ArrayList<>();
        Map<Integer, PaxosState> uncommitedValues = getUncommitedValues();
        for (Integer index : uncommitedValues.keySet()) {
            PaxosState logEntry = uncommitedValues.get(index);
            byte[] proposedValue = logEntry.acceptedValue().get();
            var completeFuture = sendProposeRequest(index, proposedValue, newBallot)
                    .thenCompose(value -> {
                        return sendCommitRequest(index, proposedValue, newBallot);
                    });
            commitFutures.add(completeFuture);
        }
        return CompletableFuture.allOf(commitFutures.toArray(new CompletableFuture[0])).thenApply(e -> newBallot);
    }

    private void mergeLog(FullLogPrepareResponse promise) {
        var indexes = promise.uncommittedValues.keySet();
        for (Integer index : indexes) {
            PaxosState peerEntry = promise.uncommittedValues.get(index);
            PaxosState selfEntry = paxosLog.get(index);
            if (selfEntry == null || isAfter(peerEntry.acceptedBallot(), selfEntry.acceptedBallot())) {
                paxosLog.put(index, peerEntry);
            }
        }
    }

    private boolean isAfter(Optional<MonotonicId> m1, Optional<MonotonicId> m2) {
        if (m1.isPresent() && m2.isPresent()) {
            return m1.get().isAfter(m2.get());
        }
        if (m1.isPresent()) {
            return true;
        }

        if (m2.isPresent()) {
            return false;
        }

        return false;
    }

    private CompletableFuture<Map<InetAddressAndPort, FullLogPrepareResponse>> sendFullLogPrepare(MonotonicId fullLogPromisedGeneration) {
        var prepareCallback = new AsyncQuorumCallback<FullLogPrepareResponse>(getNoOfReplicas(), r -> r.promised);
        logger.info(getName() + " sending prepare request for " + fullLogPromisedGeneration);
        sendMessageToReplicas(prepareCallback, RequestId.Prepare, new PrepareRequest(-1, fullLogPromisedGeneration));
        return prepareCallback.getQuorumFuture();
    }

    private void handlePaxosCommit(Message<CommitRequest> message) {
        CommitRequest request = message.getRequest();
        var paxosState = getOrCreatePaxosState(request.index);
        //Accept commit, because commit is invoked only after successful prepare and propose.
        PaxosState committedPaxosState = paxosState.commit(request.generation, Optional.ofNullable(request.committedValue));
        paxosLog.put(request.index, committedPaxosState);
        addAndApplyIfAllThePreviousEntriesAreCommitted(request);
        sendOneway(message.getFromAddress(), new CommitResponse(true), message.getCorrelationId());
    }

    private void addAndApplyIfAllThePreviousEntriesAreCommitted(CommitRequest commitRequest) {
        //if all entries upto logIndex - 1 are committed, apply this entry.
        List<Integer> previousIndexes = this.paxosLog.keySet().stream().filter(index -> index < commitRequest.index).collect(Collectors.toList());
        boolean allPreviousCommitted = true;
        for (Integer previousIndex : previousIndexes) {
            if (paxosLog.get(previousIndex).committedValue().isEmpty()) {
                allPreviousCommitted = false;
                break;
            }
        }
        if (allPreviousCommitted) {
            addAndApply(commitRequest.index, commitRequest.committedValue);
        }

        //see if there are entries above this logIndex which are committed, apply those entries.
        for (int startIndex = commitRequest.index + 1; ; startIndex++) {
            PaxosState paxosState = paxosLog.get(startIndex);
            if (paxosState == null || paxosState.committedValue().isEmpty()) {
                break;
            }
            byte[] committed = paxosState.committedValue().get();
            addAndApply(startIndex, committed);
        } //convert to streaming..
    }

    private void addAndApply(int index, byte[] walEnty) {
        Command command = Command.deserialize(walEnty);
        if (command instanceof SetValueCommand) {
            SetValueCommand setValueCommand = (SetValueCommand) command;
            kv.put(setValueCommand.getKey(), setValueCommand.getValue());
            requestWaitingList.handleResponse(index, new ExecuteCommandResponse(Optional.of(setValueCommand.getValue()), true));
        }
    }

    private void handlePaxosProposal(Message<ProposalRequest> message) {
        ProposalRequest request = message.getRequest();
        var generation = request.generation;
        var paxosState = getOrCreatePaxosState(request.index);
        if (generation.equals(fullLogBallot) || generation.isAfter(fullLogBallot)) {
            fullLogBallot = generation; //if its after the promisedBallot, update promisedBallot
            PaxosState acceptedPaxosState = paxosState.accept(request.generation, Optional.ofNullable(request.proposedValue));
            paxosLog.put(request.index, acceptedPaxosState);
            sendOneway(message.getFromAddress(), new ProposalResponse(true), message.getCorrelationId());
            return;
        }
        sendOneway(message.getFromAddress(), new ProposalResponse(false), message.getCorrelationId());
    }

    MonotonicId fullLogBallot = MonotonicId.empty();

    private void handleFullLogPrepare(Message<PrepareRequest> message) {
        MonotonicId ballot = message.getRequest().monotonicId;
        if (fullLogBallot.isAfter(ballot)) {
            logger.info(getName() + " rejecting ballot " + ballot + " promisedBallot=" + fullLogBallot);
            sendOneway(message.getFromAddress(), new FullLogPrepareResponse(false, Collections.EMPTY_MAP), message.getCorrelationId());
            return;
        }
        becomeFollower(ballot);
        sendOneway(message.getFromAddress(), new FullLogPrepareResponse(true, getUncommitedValues()), message.getCorrelationId());
    }

    private void becomeFollower(MonotonicId ballot) {
        logger.info(getName() + " accepting ballot " + ballot + ". Becoming follower.");
        fullLogBallot = ballot;
        this.role = ServerRole.Follower;
        heartBeatScheduler.stop();
        heartbeatChecker.start();
        markHeartbeatReceived();
        setRandomElectionTimeout();
    }


    private Map<Integer, PaxosState> getUncommitedValues() {
        Map<Integer, PaxosState> uncommittedEntries = new HashMap<>();
        Set<Integer> indexes = paxosLog.keySet();
        for (Integer index : indexes) {
            PaxosState paxosState = paxosLog.get(index);
            if (paxosState.committedValue().isEmpty()) {
                uncommittedEntries.put(index, paxosState);
            }
        }
        return uncommittedEntries;
    }

    private PaxosState getOrCreatePaxosState(int index) {
        PaxosState paxosState = paxosLog.get(index);
        if (paxosState == null) {
            paxosState = new PaxosState();
            paxosLog.put(index, paxosState);
        }
        return paxosState;
    }

    public String getValue(String title) {
        return kv.get(title);
    }

    public boolean isLeader() {
        return role == ServerRole.Leader;
    }

    @Override
    public void onStart() {
        heartbeatChecker.start();
    }
}
