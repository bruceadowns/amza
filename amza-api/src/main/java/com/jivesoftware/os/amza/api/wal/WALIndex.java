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
package com.jivesoftware.os.amza.api.wal;

import com.jivesoftware.os.amza.api.scan.CompactableWALIndex;
import com.jivesoftware.os.amza.api.scan.RangeScannablePointers;
import com.jivesoftware.os.amza.api.stream.KeyContainedStream;
import com.jivesoftware.os.amza.api.stream.KeyValuePointerStream;
import com.jivesoftware.os.amza.api.stream.KeyValues;
import com.jivesoftware.os.amza.api.stream.TxFpStream;
import com.jivesoftware.os.amza.api.stream.UnprefixedWALKeys;
import com.jivesoftware.os.amza.api.stream.WALKeyPointerStream;
import com.jivesoftware.os.amza.api.stream.WALKeyPointers;
import java.util.Map;

public interface WALIndex extends RangeScannablePointers, CompactableWALIndex {

    String getProviderName();

    boolean getPointers(byte[] prefix, UnprefixedWALKeys keys, WALKeyPointerStream stream) throws Exception;

    boolean getPointers(KeyValues keyValues, KeyValuePointerStream stream) throws Exception;

    boolean takePrefixUpdatesSince(byte[] prefix, long sinceTransactionId, TxFpStream txFpStream) throws Exception;

    boolean containsKeys(byte[] prefix, UnprefixedWALKeys keys, KeyContainedStream stream) throws Exception;

    long deltaCount(WALKeyPointers keyPointers) throws Exception;

    void close() throws Exception;

    void delete() throws Exception;

    void updatedProperties(Map<String, String> properties);

}
