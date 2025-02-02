package replicate.paxos;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import replicate.common.ClusterTest;
import replicate.common.MonotonicId;
import replicate.common.NetworkClient;
import replicate.common.TestUtils;
import replicate.net.InetAddressAndPort;
import replicate.paxos.messages.GetValueResponse;
import replicate.quorum.messages.GetValueRequest;
import replicate.quorum.messages.SetValueRequest;
import replicate.quorum.messages.SetValueResponse;
import replicate.wal.SetValueCommand;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class SingleValuePaxosTest extends ClusterTest<SingleValuePaxos> {
    SingleValuePaxos athens;
    SingleValuePaxos byzantium;
    SingleValuePaxos cyrene;

    @Before
    public void startCluster() throws IOException {
        super.nodes = TestUtils.startCluster(Arrays.asList("athens", "byzantium", "cyrene"),
                (name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses) -> {
                    return new SingleValuePaxos(name, clock, config, clientConnectionAddress, peerConnectionAddress, peerAddresses);
                });
        athens = nodes.get("athens");
        byzantium = nodes.get("byzantium");
        cyrene = nodes.get("cyrene");
    }

    @Test
    public void singleValuePaxosTest() throws IOException {
        var response = setValue(new SetValueRequest("title", "Microservices"), athens.getClientConnectionAddress());

        Assert.assertEquals("Microservices", response.result);
    }

    @Test
    public void singleValueNullPaxosGetTest() throws IOException {
        var client = new NetworkClient();
        var response = client.sendAndReceive(new GetValueRequest("title"), athens.getClientConnectionAddress(), GetValueResponse.class);
        assertEquals(Optional.empty(), response.value);
    }

    @Test
    public void allNodesChooseOneValueEvenWithIncompleteWrites() throws IOException {
        //only athens has value Microservices
        //byzantium is empty, cyrene is empty
        athens.dropAfterNMessagesTo(byzantium, 1);
        athens.dropAfterNMessagesTo(cyrene, 1);
        //prepare succeeds on athens, byzantium and cyrene.
        //propose succeeds only on athens, as messages will be dropped to byzantium and cyrene
        var response = setValue(new SetValueRequest("title", "Microservices"), athens.getClientConnectionAddress());
        Assert.assertEquals("Error", response.result);

        assertEquals(athens.paxosState.promisedBallot(), new MonotonicId(2, 0)); //prepare from second attempt
        assertEquals(athens.paxosState.acceptedBallot(), Optional.of(new MonotonicId(1, 0)));
        assertEquals(byzantium.paxosState.promisedBallot(), new MonotonicId(1, 0));
        assertEquals(byzantium.paxosState.acceptedBallot(), Optional.empty());
        assertEquals(cyrene.paxosState.promisedBallot(), new MonotonicId(1, 0));
        assertEquals(cyrene.paxosState.acceptedBallot(), Optional.empty());
        assertEquals(athens.getAcceptedCommand().getValue(), "Microservices");

        //only byzantium will have value Distributed Systems
        //athens has Microservices
        //cyrene is empty.
        byzantium.dropAfterNMessagesTo(cyrene, 1);
        response = setValue(new SetValueRequest("title", "Distributed Systems"), byzantium.getClientConnectionAddress());

        Assert.assertEquals("Error", response.result);
        assertEquals(byzantium.paxosState.promisedBallot(), new MonotonicId(1, 1)); //prepare from second attempt
        assertEquals(byzantium.paxosState.acceptedBallot(), Optional.of(new MonotonicId(1, 1)));
        assertEquals(byzantium.getAcceptedCommand().getValue(), "Distributed Systems");

        assertEquals(cyrene.paxosState.promisedBallot(), new MonotonicId(1, 1));
        assertEquals(cyrene.paxosState.acceptedBallot(), Optional.empty());

        assertEquals(athens.paxosState.promisedBallot(), new MonotonicId(2, 0));
        assertEquals(athens.paxosState.acceptedBallot(), Optional.of(new MonotonicId(1, 0)));
        assertEquals(athens.getAcceptedCommand().getValue(), "Microservices");
        assertEquals(byzantium.getAcceptedCommand().getValue(), "Distributed Systems");

        byzantium.reconnectTo(cyrene);
        byzantium.dropAfterNMessagesTo(cyrene, 1);

        //Cyrene will try distributed systems, but will propose Distributed Systems returned from byzantium
        //athens has Microservices at (1,0)
        //byzantium has Distributed Systems. (1,1)
        response = setValue(new SetValueRequest("title", "Event Driven Microservices"), cyrene.getClientConnectionAddress());

        Assert.assertEquals("Error", response.result);
        assertEquals(cyrene.paxosState.promisedBallot(), new MonotonicId(1, 2)); //prepare from second attempt
        assertEquals(cyrene.paxosState.acceptedBallot(), Optional.of(new MonotonicId(1, 2)));
        assertEquals(cyrene.getAcceptedCommand().getValue(), "Distributed Systems");
        assertEquals(athens.getAcceptedCommand().getValue(), "Microservices");
        assertEquals(byzantium.getAcceptedCommand().getValue(), "Distributed Systems");

        athens.reconnectTo(byzantium);
        athens.reconnectTo(cyrene);

        var getValueResponse = new NetworkClient().sendAndReceive(new GetValueRequest("title"), athens.getClientConnectionAddress(), GetValueResponse.class);
        assertEquals(Optional.of("Distributed Systems"), getValueResponse.value);


    }

    private SetValueResponse setValue(SetValueRequest request, InetAddressAndPort clientConnectionAddress) {
        try {
            NetworkClient client = new NetworkClient();
            return client.sendAndReceive(request, clientConnectionAddress, SetValueResponse.class);
        } catch (Exception e) {
            return new SetValueResponse("Error");
        }
    }

}