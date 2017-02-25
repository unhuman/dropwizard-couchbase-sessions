package com.cvent.couchbase.session;

import com.cvent.couchbase.session.CouchbaseSessionManager.CouchbaseHttpSession;
import com.sun.jersey.api.model.Parameter;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

/**
 * Provides the HttpSession entity for any resource annotated with @CventSession annotated method parameter
 * 
 * @author bryan
 */
@Provider
public class CventHttpSessionProvider implements InjectableProvider<CventSession, Parameter> {

    private final ThreadLocal<HttpServletRequest> request;

    public CventHttpSessionProvider(@Context ThreadLocal<HttpServletRequest> request) {
        this.request = request;
    }

    @Override
    public ComponentScope getScope() {
        return ComponentScope.PerRequest;
    }

    @Override
    public Injectable<?> getInjectable(ComponentContext ic, final CventSession session, Parameter parameter) {
        if (parameter.getParameterClass().isAssignableFrom(CouchbaseHttpSession.class)) {
            return () -> {
                final HttpServletRequest req = request.get();
                if (req != null) {
                    CouchbaseHttpSession cventSession = (CouchbaseHttpSession) req.getSession(session.create());
                    if (session.write()) {
                        cventSession.setWrite(true);
                    }
                    
                    return cventSession;
                }
                return null;
            };
        }
        return null;
    }
}
