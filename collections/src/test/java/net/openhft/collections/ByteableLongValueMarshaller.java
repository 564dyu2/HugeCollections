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

import net.openhft.lang.io.Bytes;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.io.serialization.BytesMarshaller;
import net.openhft.lang.io.serialization.ObjectFactory;
import net.openhft.lang.model.Byteable;
import net.openhft.lang.model.DataValueClasses;
import net.openhft.lang.model.constraints.Nullable;
import net.openhft.lang.values.LongValue;

enum ByteableLongValueMarshaller implements BytesMarshaller<LongValue> {
    INSTANCE;

    static final Class DIRECT = DataValueClasses.directClassFor(LongValue.class);
    @Override
    public void write(Bytes bytes, LongValue longValue) {
        Byteable biv = (Byteable) longValue;
        bytes.write(biv.bytes(), biv.offset(), biv.maxSize());
    }

    @Nullable
    @Override
    public LongValue read(Bytes bytes) {
        return read(bytes, null);
    }

    @Nullable
    @Override
    public LongValue read(Bytes bytes, @Nullable LongValue longValue) {
        try {
            if (longValue == null)
                longValue = (LongValue) NativeBytes.UNSAFE.allocateInstance(DIRECT);
            Byteable biv = (Byteable) longValue;
            biv.bytes(bytes, bytes.position());
            bytes.skip(biv.maxSize());
            return longValue;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

    }
}

enum DirectLongValueFactory implements ObjectFactory<LongValue> {
    INSTANCE;

    @Override
    public LongValue create() throws Exception {
        return (LongValue) NativeBytes.UNSAFE.allocateInstance(ByteableLongValueMarshaller.DIRECT);
    }
}

