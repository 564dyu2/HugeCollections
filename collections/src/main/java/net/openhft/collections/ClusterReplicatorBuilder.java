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

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.openhft.collections.SharedHashMapBuilder.UDP_REPLICATION_MODIFICATION_ITERATOR_ID;

/**
 * @author Rob Austin.
 */
public class ClusterReplicatorBuilder<K, V> {

    Set<Closeable> closeables = new HashSet<Closeable>();

    private final byte identifier;
    private UdpReplicatorBuilder udpReplicatorBuilder = null;
    private TcpReplicatorBuilder tcpReplicatorBuilder = null;

    private int maxEntrySize;
    private int maxNumberOfChronicles = 128;


    ClusterReplicatorBuilder(byte identifier, final int maxEntrySize1) {
        this.identifier = identifier;
        this.maxEntrySize = maxEntrySize1;
        if (!(identifier > 0 && identifier < 128))
            throw new IllegalArgumentException("Identifier must be positive and <128, " +
                    "identifier=" + identifier);
    }


    private final Map<Short, ReplicaExternalizable> replicas
            = new ConcurrentHashMap<Short, ReplicaExternalizable>();

    public ClusterReplicatorBuilder udpReplicator(UdpReplicatorBuilder udpReplicatorBuilder) throws IOException {
        this.udpReplicatorBuilder = udpReplicatorBuilder;
        return this;
    }

    public ClusterReplicatorBuilder<K, V> tcpReplicatorBuilder(TcpReplicatorBuilder tcpReplicatorBuilder) {
        this.tcpReplicatorBuilder = tcpReplicatorBuilder;
        return this;
    }

    public <K, V> SharedHashMap<K, V> create(short canonicalId, SharedHashMapBuilder builder) throws
            IOException {

        final SharedHashMapBuilder builder0 = builder.toBuilder();

        builder0.identifier(identifier);

        if (builder0 == null || !builder0.file().exists())
            throw new FileNotFoundException("Unable to create file");

        final VanillaSharedReplicatedHashMap<K, V> result =
                new VanillaSharedReplicatedHashMap<K, V>(builder0, builder0.file(), builder0.<K>kClass(),
                        builder0.<V>vClass());

        replicas.put(canonicalId, (ReplicaExternalizable) result);

        return result;
    }


    public ClusterReplicator create() throws IOException {

        final ClusterReplicator<K, V> clusterReplicator = new ClusterReplicator<K, V>(identifier,
                maxNumberOfChronicles);

        for (final Map.Entry<Short, ReplicaExternalizable> entry : replicas.entrySet()) {
            clusterReplicator.add(entry.getKey(), entry.getValue());
        }


        if (tcpReplicatorBuilder != null) {
            final TcpReplicator tcpReplicator = new TcpReplicator(clusterReplicator, clusterReplicator, tcpReplicatorBuilder,
                    maxEntrySize);
            closeables.add(tcpReplicator);
            clusterReplicator.add(tcpReplicator);
        }

        if (udpReplicatorBuilder != null) {
            final InetAddress address = udpReplicatorBuilder.address();

            if (address == null)
                throw new IllegalArgumentException("address can not be null");

            if (address.isMulticastAddress() && udpReplicatorBuilder.networkInterface() == null) {
                throw new IllegalArgumentException("MISSING: NetworkInterface, " +
                        "When using a multicast addresses, please provided a  networkInterface");
            }

            final UdpReplicator udpReplicator =
                    new UdpReplicator(clusterReplicator,
                            udpReplicatorBuilder.clone(),
                            maxEntrySize,
                            identifier,
                            UDP_REPLICATION_MODIFICATION_ITERATOR_ID);

            closeables.add(udpReplicator);
            clusterReplicator.add(udpReplicator);
        }


        return clusterReplicator;

    }

}
