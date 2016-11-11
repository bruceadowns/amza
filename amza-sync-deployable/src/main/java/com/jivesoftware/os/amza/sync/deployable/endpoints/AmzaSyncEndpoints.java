/*
 * Copyright 2014 Jive Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jivesoftware.os.amza.sync.deployable.endpoints;

import com.jivesoftware.os.amza.api.BAInterner;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.sync.deployable.AmzaSyncSender;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.routing.bird.shared.ResponseHelper;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

/**
 * @author jonathan
 */
@Singleton
@Path("/amza/sync")
public class AmzaSyncEndpoints {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final AmzaSyncSender syncSender;
    private final BAInterner interner;

    private final ResponseHelper responseHelper = ResponseHelper.INSTANCE;

    public AmzaSyncEndpoints(@Context AmzaSyncSender syncSender, @Context BAInterner interner) {
        this.syncSender = syncSender;
        this.interner = interner;
    }

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        try {
            return responseHelper.jsonResponse("Success");
        } catch (Exception e) {
            LOG.error("Failed to get.", e);
            return Response.serverError().build();
        }
    }

    @POST
    @Path("/reset/{partitionNameBase64}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response postReset(@PathParam("partitionNameBase64") String partitionNameBase64) {
        try {
            if (syncSender != null) {
                PartitionName partitionName = PartitionName.fromBase64(partitionNameBase64, interner);
                boolean result = syncSender.resetCursors(partitionName);
                return Response.ok(result).build();
            } else {
                return Response.status(Status.SERVICE_UNAVAILABLE).entity("Sender is not enabled").build();
            }
        } catch (Exception e) {
            LOG.error("Failed to reset.", e);
            return Response.serverError().build();
        }
    }

}
