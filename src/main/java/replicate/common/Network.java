package replicate.common;

import replicate.net.InetAddressAndPort;
import replicate.net.SocketClient;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class  Network {
    public static final int MESSAGE_DELAY = 1000;

    List<InetAddressAndPort> dropRequestsTo = new ArrayList<>();
    Map<InetAddressAndPort, Integer> noOfMessages = new HashMap<>();
    Map<InetAddressAndPort, Integer> dropAfter = new HashMap<>();
    Map<InetAddressAndPort, Integer> delayMessagesAfter = new HashMap<>();

    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    public void sendOneWay(InetAddressAndPort address, RequestOrResponse message) throws IOException {
        if (dropRequestsTo.contains(address) || noOfMessagesReachedLimit(address)) {
            removeExistingConnections(address);
            throw new IOException("Unable to connect to " + address);
        }

        if (shouldDelayMessagesTo(address)) {
            sendAfterDelay(address, message, MESSAGE_DELAY);
            return;
        }

        sendMessage(address, message);
    }

    private void removeExistingConnections(InetAddressAndPort address) {
        SocketClient socketClient = connectionPool.remove(address);
        if (socketClient != null) {
            socketClient.close();
        }
    }

    public RequestOrResponse sendRequestResponse(InetAddressAndPort address, RequestOrResponse message) throws IOException {
        if (dropRequestsTo.contains(address) || noOfMessagesReachedLimit(address)) {
            throw new IOException("Unable to connect to " + address);
        }

        if (shouldDelayMessagesTo(address)) {
            sendAfterDelay(address, message, 1000);
            throw new IOException("Request to " + address + " timed out");
        }

        return sendAndReceive(address, message);
    }

    private void sendAfterDelay(InetAddressAndPort address, RequestOrResponse message, int delay) {
        executor.schedule(()->{
            try {
                System.out.println("Sending delayed message to address = " + address);
                sendMessage(address, message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private boolean shouldDelayMessagesTo(InetAddressAndPort address) {
        Integer delayAfterNRequests = delayMessagesAfter.get(address);
        Integer noOfRequestsSent = noOfMessages.get(address);
        if ((delayAfterNRequests == null) || (noOfRequestsSent == null)) {
            return false;
        }
        return noOfRequestsSent >= delayAfterNRequests;
    }

    Map<InetAddressAndPort, SocketClient> connectionPool = new HashMap<>();

    private void sendMessage(InetAddressAndPort address, RequestOrResponse message) throws IOException {
        SocketClient socketClient = getOrCreateConnection(address);
        socketClient.sendOneway(message);
        Integer integer = noOfMessages.get(address);
        if (integer == null) {
            integer = 0;
        }
        noOfMessages.put(address, integer + 1);
    }

    private RequestOrResponse sendAndReceive(InetAddressAndPort address, RequestOrResponse message) throws IOException {
        SocketClient socketClient = getOrCreateConnection(address);
        RequestOrResponse response = socketClient.blockingSend(message);
        Integer integer = noOfMessages.get(address);
        if (integer == null) {
            integer = 0;
        }
        noOfMessages.put(address, integer + 1);
        return response;
    }

    private synchronized SocketClient getOrCreateConnection(InetAddressAndPort address) throws IOException {
        SocketClient socketClient = connectionPool.get(address);
        if (socketClient == null || !socketClient.isClosed()) {
            socketClient = new SocketClient(address);
            connectionPool.put(address, socketClient);
        }
        return socketClient;
    }

    private boolean noOfMessagesReachedLimit(InetAddressAndPort address) {
        Integer dropAfterMessages = dropAfter.get(address);
        Integer noOfMessages = this.noOfMessages.get(address);
        return dropAfterMessages == null?false:(noOfMessages != null && noOfMessages >= dropAfterMessages);
    }

    public void dropMessagesTo(InetAddressAndPort address) {
        dropRequestsTo.add(address);
    }

    public void reconnectTo(InetAddressAndPort address) {
        dropRequestsTo.remove(address);
        dropAfter.remove(address);
        delayMessagesAfter.remove(address);
        noOfMessages.remove(address); //also reset message counter to specific address.
    }

    public void dropMessagesAfter(InetAddressAndPort address, int dropAfterNoOfMessages) {
        noOfMessages.remove(address); //only count messages here after.
        dropAfter.put(address, dropAfterNoOfMessages);
    }

    public void addDelayForMessagesToAfterNMessages(InetAddressAndPort peerConnectionAddress, int noOfMessages) {
        delayMessagesAfter.put(peerConnectionAddress, noOfMessages);
    }

    public void closeAllConnections() {
        Set<InetAddressAndPort> inetAddressAndPorts = connectionPool.keySet();
        for (InetAddressAndPort inetAddressAndPort : inetAddressAndPorts) {
            connectionPool.get(inetAddressAndPort).close();
        }
        connectionPool.clear();
    }
}
