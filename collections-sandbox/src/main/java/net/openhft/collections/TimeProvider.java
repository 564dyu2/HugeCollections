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

/**
 * TimeProvider was created initially  to support testing of VanillaSharedReplicatedHashMap, with the aim of possibly later providing and optimization to System.currentTimeMillis() on every call to put(), get() and remove()
 *
 * @author Rob Austin.
 */
public class TimeProvider {

    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

}
