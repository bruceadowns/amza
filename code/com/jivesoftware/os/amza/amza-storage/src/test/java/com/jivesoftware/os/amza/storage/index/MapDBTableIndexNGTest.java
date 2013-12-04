/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.amza.storage.index;

import com.jivesoftware.os.amza.shared.BasicTimestampedValue;
import com.jivesoftware.os.amza.shared.TableIndexKey;
import com.jivesoftware.os.amza.storage.chunks.UIO;
import org.testng.annotations.Test;

public class MapDBTableIndexNGTest {

    @Test (enabled = false)
    public void loadTest() {
        System.out.println("InitialHeap:" + Runtime.getRuntime().totalMemory());
        MapDBTableIndex instance = new MapDBTableIndex("test");
        for (long i = 0; i < 200000000; i++) {
            instance.put(new TableIndexKey(UIO.longBytes(i)), new BasicTimestampedValue(UIO.longBytes(i), i, false));
            if (i % 1000000 == 0) {
                System.out.println("Size:" + i + " Heap:" + Runtime.getRuntime().totalMemory());
                instance.flush();
            }
        }
        instance.flush();

        System.out.println("Heap:" + Runtime.getRuntime().totalMemory());
        // TODO review the generated test code and remove the default call to fail.
    }
}