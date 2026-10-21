package cc.coopersoft.keycloak.phone.providers.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/** Retry sending in a new request after the current token transaction completes. */
public final class SmsSendDeferredException extends WebApplicationException {
    public SmsSendDeferredException() {
        super(Response.status(Response.Status.CONFLICT).build());
    }
}
