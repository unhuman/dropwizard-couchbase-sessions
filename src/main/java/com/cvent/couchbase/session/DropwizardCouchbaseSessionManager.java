package com.cvent.couchbase.session;

import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Environment;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.SessionCookieConfig;
import org.eclipse.jetty.server.session.SessionHandler;
import com.couchbase.client.java.Bucket;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A manager that allows us to initialize session state provider within dropwizard.  It's expected that couchbase gets
 * initialized prior to session state since it relies on couchbase client.
 *
 * @author bryan
 */
public class DropwizardCouchbaseSessionManager implements Managed {

    private final Environment environment;
    private final int sessionTimeout;
    private final boolean secureCookies;
    private final ObjectMapper objectMapper;
    private final Bucket bucket;
    private final String environmentName;
    private final String applicationName;

    /**
     * Create a new manager
     *
     * @param environment               The dropwizard environment object
     * @param sessionTimeout            The timeout in seconds of the session
     * @param secureCookies             true if using secure cookies, false otherwise
     * @param applicationName           The name of your application
     * @param environmentName           The name of the environment
     * @param ObjectMapper              The ObjectMapper to use for serialization/deserialization
     */
    public DropwizardCouchbaseSessionManager(Environment environment, int sessionTimeout, boolean secureCookies,
            String applicationName, String environmentName, ObjectMapper objectMapper, Bucket bucket) {
        this.environment = environment;
        this.sessionTimeout = sessionTimeout;
        this.secureCookies = secureCookies;
        this.applicationName = applicationName;
        this.environmentName = environmentName;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
    }

    @Override
    public void start() throws Exception {
        final FilterRegistration.Dynamic cookieFilter
                = environment.servlets().addFilter("HttpSessionCookieFilter", HttpSessionCookieFilter.class);
        cookieFilter.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");

        environment.jersey().register(CventHttpSessionProvider.class);

        String keyPrefix = environmentName + "::"
                + applicationName + "::session::";

        SessionHandler sessionHandler
                = new SessionHandler(new CouchbaseSessionManager(keyPrefix, bucket,
                        objectMapper, sessionTimeout));
        SessionCookieConfig sessionCookieConfig = sessionHandler.getSessionManager().getSessionCookieConfig();
        sessionCookieConfig.setHttpOnly(true);
        sessionCookieConfig.setSecure(secureCookies);
        sessionCookieConfig.setName(applicationName + "-session");
        environment.servlets().setSessionHandler(sessionHandler);
    }

    @Override
    public void stop() throws Exception {
        //do nothing since session handler is managed by jetty and couchbase client gets managed separately
    }

}
