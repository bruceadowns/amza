package com.jivesoftware.os.amza.sync.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Created by jonathan.colt on 12/22/16.
 */
public class AmzaSyncSenderConfig {


    public final String name;
    public final boolean enabled;
    public final String senderScheme;
    public final String senderHost;
    public final int senderPort;
    public final long syncIntervalMillis;
    public final int batchSize;
    public final String oAuthConsumerKey;
    public final String oAuthConsumerSecret;
    public final String oAuthConsumerMethod;
    public final boolean allowSelfSignedCerts;

    @JsonCreator
    public AmzaSyncSenderConfig(@JsonProperty("name") String name,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("senderScheme") String senderScheme,
        @JsonProperty("senderHost")  String senderHost,
        @JsonProperty("senderPort") int senderPort,
        @JsonProperty("syncIntervalMillis") long syncIntervalMillis,
        @JsonProperty("batchSize") int batchSize,
        @JsonProperty("oAuthConsumerKey") String oAuthConsumerKey,
        @JsonProperty("oAuthConsumerSecret") String oAuthConsumerSecret,
        @JsonProperty("oAuthConsumerMethod") String oAuthConsumerMethod,
        @JsonProperty("allowSelfSignedCerts") boolean allowSelfSignedCerts) {

        this.name = name;
        this.enabled = enabled;
        this.senderScheme = senderScheme;
        this.senderHost = senderHost;
        this.senderPort = senderPort;
        this.syncIntervalMillis = syncIntervalMillis;
        this.batchSize = batchSize;
        this.oAuthConsumerKey = oAuthConsumerKey;
        this.oAuthConsumerSecret = oAuthConsumerSecret;
        this.oAuthConsumerMethod = oAuthConsumerMethod;
        this.allowSelfSignedCerts = allowSelfSignedCerts;
    }
}
