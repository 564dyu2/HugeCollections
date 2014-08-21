/*
 * Copyright 2014 Higher Frequency Trading
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.collections;

import java.io.Closeable;
import java.io.IOException;

import static net.openhft.collections.Replica.EntryExternalizable;

public final class Replicators {

    public static Replicator tcp(final byte identifier,
                                 final TcpReplicationConfig replicationConfig) {
        return new Replicator() {
            @Override
            public byte identifier() {
                return identifier;
            }

            @Override
            protected Closeable applyTo(SharedHashMapBuilder builder,
                                        Replica map, EntryExternalizable entryExternalizable)
                    throws IOException{
                return new TcpReplicator(map, entryExternalizable, replicationConfig,
                        builder.entrySize());
            }
        };
    }

    public static Replicator udp(final byte identifier,
                                 final UdpReplicationConfig replicationConfig) {
        return new Replicator() {
            @Override
            public byte identifier() {
                return identifier;
            }

            @Override
            protected Closeable applyTo(SharedHashMapBuilder builder,
                                        Replica map, EntryExternalizable entryExternalizable)
                    throws IOException {
                return new UdpReplicator(map, entryExternalizable, replicationConfig,
                        builder.entrySize());
            }
        };
    }

    private Replicators() {}
}
