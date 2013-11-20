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
package com.jivesoftware.os.amza.storage.binary;

import com.jivesoftware.os.amza.shared.TableRowReader;
import com.jivesoftware.os.amza.shared.TableRowReader.Stream;
import com.jivesoftware.os.amza.storage.chunks.IFiler;
import com.jivesoftware.os.amza.storage.chunks.UIO;

public class BinaryRowReader implements TableRowReader<byte[]> {

    private final IFiler filer;

    public BinaryRowReader(IFiler filer) {
        this.filer = filer;
    }

    @Override
    public void read(boolean reverse, Stream<byte[]> stream) throws Exception {

        if (reverse) {
            synchronized (filer.lock()) {
                long seekTo = filer.length() - 4; // last length int
                if (seekTo < 0) {
                    return;
                }
                while (seekTo >= 0) {
                    filer.seek(seekTo);
                    int priorLength = UIO.readInt(filer, "priorLength");
                    seekTo -= (priorLength + 4);
                    filer.seek(seekTo);

                    int length = UIO.readInt(filer, "length");
                    byte[] row = new byte[length];
                    filer.read(row);
                    if (!stream.stream(row)) {
                        break;
                    }

                    seekTo -= 4;
                }
            }
        } else {
            synchronized (filer.lock()) {
                if (filer.length() == 0) {
                    return;
                }
                filer.seek(0);
                while (filer.getFilePointer() < filer.length()) {
                    int length = UIO.readInt(filer, "length");
                    byte[] row = new byte[length];
                    if (length > 0) {
                        filer.read(row);
                    }
                    if (!stream.stream(row)) {
                        break;
                    }
                    UIO.readInt(filer, "length");
                }
            }
        }
    }
}
