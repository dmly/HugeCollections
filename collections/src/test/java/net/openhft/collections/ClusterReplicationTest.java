/*
 * Copyright 2014 Higher Frequency Trading
 * <p/>
 * http://www.higherfrequencytrading.com
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.collections;


import org.junit.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.openhft.collections.Builder.getPersistenceFile;
import static org.junit.Assert.assertTrue;

/**
 * Test VanillaSharedReplicatedHashMap where the Replicated is over a TCP Socket, but with 4 nodes
 *
 * @author Rob Austin.
 */
public class ClusterReplicationTest {

    private SharedHashMap<Integer, CharSequence> map1a;
    private SharedHashMap<Integer, CharSequence> map2a;

    private SharedHashMap<Integer, CharSequence> map1b;
    private SharedHashMap<Integer, CharSequence> map2b;

    private ClusterReplicator clusterB;
    private ClusterReplicator clusterA;


    @Before

    public void setup() throws IOException {
        {
            TcpReplicationConfig tcpConfig = TcpReplicationConfig
                    .of(8086, new InetSocketAddress("localhost", 8087))
                    .heartBeatInterval(1, SECONDS);

            clusterA = new ClusterReplicatorBuilder((byte) 1, 1024).tcpReplication(tcpConfig)
                    .create();

            map1a = new SharedHashMapBuilder().entries(1000)
                    .addReplicator(clusterA.chronicleChannel((short) 1))
                    .create(getPersistenceFile(), Integer.class, CharSequence.class);
        }

        {
            TcpReplicationConfig tcpConfig =
                    TcpReplicationConfig.of(8087).heartBeatInterval(1, SECONDS);

            clusterB = new ClusterReplicatorBuilder((byte) 2, 1024).tcpReplication(tcpConfig)
                    .create();

            map1b = new SharedHashMapBuilder().entries(1000)
                    .addReplicator(clusterB.chronicleChannel((short) 1))
                    .create(getPersistenceFile(), Integer.class, CharSequence.class);
        }
    }

    @After
    public void tearDown() throws InterruptedException {

        for (final Closeable closeable : new Closeable[]{clusterA, clusterB}) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    @Test
    @Ignore
    public void test() throws IOException, InterruptedException {

        // todo remove this sleep
        Thread.sleep(100);

        map2b = new SharedHashMapBuilder().entries(1000)
                .addReplicator(clusterB.chronicleChannel((short) 2))
                .create(getPersistenceFile(), Integer.class, CharSequence.class);


        map2a = new SharedHashMapBuilder().entries(1000)
                .addReplicator(clusterA.chronicleChannel((short) 2))
                .create(getPersistenceFile(), Integer.class, CharSequence.class);

        map2a.put(1, "EXAMPLE-2");
        map1a.put(1, "EXAMPLE-1");

        // allow time for the recompilation to resolve
        waitTillEqual(2500);

        Assert.assertEquals("map1a=map1b", map1a, map1b);
        Assert.assertEquals("map2a=map2b", map2a, map2b);

        assertTrue("map1a.empty", !map1a.isEmpty());
        assertTrue("map2a.empty", !map2a.isEmpty());

    }


    /**
     * waits until map1 and map2 show the same value
     *
     * @param timeOutMs timeout in milliseconds
     * @throws InterruptedException
     */

    private void waitTillEqual(final int timeOutMs) throws InterruptedException {
        for (int t = 0; t < timeOutMs; t++) {
            if (map1a.equals(map1b) &&
                    map2a.equals(map2b))
                break;
            Thread.sleep(1);
        }

    }

}


