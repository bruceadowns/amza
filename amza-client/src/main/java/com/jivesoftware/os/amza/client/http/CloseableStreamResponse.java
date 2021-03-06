package com.jivesoftware.os.amza.client.http;

import java.io.InputStream;

/**
 *
 * @author jonathan.colt
 */
public interface CloseableStreamResponse extends Abortable {

    InputStream getInputStream();

    long getActiveCount();
}
