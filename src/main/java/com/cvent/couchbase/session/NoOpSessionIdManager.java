package com.cvent.couchbase.session;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.slf4j.LoggerFactory;

/**
 * NoOpSessionIdManager
 * 
 * This is primarily just a hack so that we can reuse most of the functionality in
 * AbstractSessionManager from jetty but most of these features are not needed when using Couchbase or other nosql
 * solutions that provide these features natively and with high performance.
 */
public final class NoOpSessionIdManager extends AbstractSessionIdManager {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(NoOpSessionIdManager.class);

    @Override
    public void addSession(HttpSession session) {
        //No-op, everything handled by session manager
        if (LOG.isDebugEnabled()) {
            LOG.debug("addSession()");
        }
    }

    @Override
    public void removeSession(HttpSession session) {
        //No-op, everything handled by session manager
        if (LOG.isDebugEnabled()) {
            LOG.debug("removeSession()");
        }
    }

    @Override
    public boolean idInUse(String id) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("idInUse()");
        }
        return false;
    }

    @Override
    public void invalidateAll(String id) {
        //No-op, everything handled by session manager
        if (LOG.isDebugEnabled()) {
            LOG.debug("invalidateAll()");
        }
    }

    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request) {
        //No-op, everything handled by session manager
        if (LOG.isDebugEnabled()) {
            LOG.debug("renewSessionId()");
        }
    }

}
