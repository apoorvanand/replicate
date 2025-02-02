package replicate.quorumconsensus;

import org.junit.Assert;
import org.junit.Test;
import replicate.common.ClusterTest;
import replicate.common.MonotonicId;
import replicate.common.TestUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class QuorumConsensusTest extends ClusterTest<QuorumConsensus> {

    @Test
    public void readRepair() throws IOException {
        this.nodes = TestUtils.startCluster(Arrays.asList("athens", "byzantium", "cyrene"),
                (name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses) -> new QuorumConsensus(name, config, clock, clientConnectionAddress, peerConnectionAddress,true, peerAddresses));
        QuorumConsensus athens = nodes.get("athens");
        QuorumConsensus byzantium = nodes.get("byzantium");
        QuorumConsensus cyrene = nodes.get("cyrene");


        athens.dropMessagesTo(byzantium);

        KVClient kvClient = new KVClient();
        String response = kvClient.setValue(athens.getClientConnectionAddress(), "title", "Microservices");
        assertEquals("Success", response);


        MonotonicId id1 = athens.getVersion("title");
        MonotonicId id2 = byzantium.getVersion("title");
        MonotonicId id3 = cyrene.getVersion("title");

        assertEquals(id1, new MonotonicId(1, 1));
        assertEquals(MonotonicId.empty(), id2);
        assertEquals(id3, new MonotonicId(1, 1));

        String title = kvClient.getValue(cyrene.getClientConnectionAddress(), "title");
        assertEquals("Microservices", title);

        Assert.assertEquals(new MonotonicId(1, 1),  byzantium.getVersion("title"));
    }

    @Test
    public void compareAndSwapIsSuccessfulForTwoConcurrentClients() throws IOException {
        Map<String, QuorumConsensus> kvStores = TestUtils.startCluster(Arrays.asList("athens", "byzantium", "cyrene"),
                (name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses) -> new QuorumConsensus(name, config, clock, clientConnectionAddress, peerConnectionAddress,true, peerAddresses));
        QuorumConsensus athens = kvStores.get("athens");
        QuorumConsensus byzantium = kvStores.get("byzantium");
        QuorumConsensus cyrene = kvStores.get("cyrene");

        athens.dropAfterNMessagesTo(byzantium, 1);
        athens.dropMessagesTo(cyrene);

        KVClient kvClient = new KVClient();
        String response = kvClient.setValue(athens.getClientConnectionAddress(), "title", "Nitroservices");
        assertEquals("Error", response);
        //quorum responses not received as messages to byzantium and cyrene fail.

        Assert.assertEquals(new MonotonicId(1, 1), athens.getVersion("title"));
        Assert.assertEquals(new MonotonicId(-1, -1), byzantium.getVersion("title"));
        Assert.assertEquals(new MonotonicId(-1, -1), cyrene.getVersion("title"));

        KVClient alice = new KVClient();

        //cyrene should be able to connect with itself and byzantium.
        //both cyrene and byzantium have empty value.
        //Alice starts the compareAndSwap
        //Alice reads the value.
        String aliceValue = alice.getValue(cyrene.getClientConnectionAddress(), "title");

        //meanwhile bob starts compareAndSwap as well
        //Bob connects to athens, which is now able to connect to cyrene and byzantium
        KVClient bob = new KVClient();
        athens.reconnectTo(cyrene);
        athens.reconnectTo(byzantium);
        String bobValue = bob.getValue(athens.getClientConnectionAddress(), "title");
        if (bobValue.equals("Microservices")) {
            kvClient.setValue(athens.getClientConnectionAddress(), "title", "Distributed Systems");
        }
        //Bob successfully completes compareAndSwap

        //Alice checks the value to be empty.
        if (aliceValue.equals("")) {
            alice.setValue(cyrene.getClientConnectionAddress(), "title", "Nitroservices");
        }
        //Alice successfully completes compareAndSwap

        //Bob is surprised to read the different value after his compareAndSwap was successful.
        response = bob.getValue(cyrene.getClientConnectionAddress(), "title");
        assertEquals("Nitroservices", response);
    }
}