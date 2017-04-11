package com.cvent.couchbase.session;

import com.couchbase.client.core.CouchbaseException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.ReplicaMode;
import com.couchbase.client.java.document.RawJsonDocument;
import com.couchbase.client.java.error.DocumentDoesNotExistException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.slf4j.LoggerFactory;

/**
 * An implementation of session manager for Couchbase + Jetty. This session manager stores documents as JSON into a
 * specific couchbase bucket using the format of keyPrefix+sessionId (ie. dev::app::session::8a9df9asdfasfasdf9asdf)
 *
 * It's expected that the lifecycle management of the couchbase Bucket API be managed outside of the session manager.
 * This is primarily because we'd like to reuse components of the initialization process that's built into dropwizard
 * and would prefer not to manage that here as well.
 *
 * A session will remain active if there is activity.
 *
 * If a read fails for an IOException then this will fallback and try to read the session from a replica.
 */
public final class CouchbaseSessionManager extends AbstractSessionManager {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CouchbaseSessionManager.class);

    private final Bucket bucket;
    private final ObjectMapper mapper;
    private final String keyPrefix;

    /**
     * Create a new session manager
     *
     * @param keyPrefix The key prefix to use with couchbase documents
     * @param bucket The couchbase Bucket api instance for communicating with couchbase
     * @param mapper The jackson ObjectMapper to be used for serialization/deserialization of session objects to/from
     * JSON
     * @param maxInactiveInterval The max number of seconds that session can exist for with no activity
     */
    public CouchbaseSessionManager(String keyPrefix, Bucket bucket, ObjectMapper mapper, int maxInactiveInterval) {
        super();
        this.bucket = bucket;
        this.mapper = mapper;
        setMaxInactiveInterval(maxInactiveInterval);
        setSessionIdManager(new NoOpSessionIdManager());
        this.keyPrefix = keyPrefix;
    }

    private String getKey(String id) {
        return keyPrefix + id;
    }

    @Override
    protected void addSession(AbstractSession session) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Add session {}", session);
        }

        if (isRunning()) {
            try {
                RawJsonDocument doc = RawJsonDocument.create(getKey(session.getClusterId()),
                        getMaxInactiveInterval(),
                        serialize((CouchbaseHttpSession) session));

                bucket.insert(doc);
            } catch (JsonProcessingException ex) {
                throw new RuntimeException("Failed serialize session to JSON " + session, ex);
            }
        }
    }

    @Override
    public AbstractSession getSession(String idInCluster) {
        String key = getKey(idInCluster);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Get session {}", key);
        }

        try {
            RawJsonDocument doc = bucket.getAndTouch(key, getMaxInactiveInterval(), RawJsonDocument.class);

            if (doc == null) {
                return null;
            }

            try {
                return deserialize(doc.content(), doc.cas());
            } catch (IOException ex) {
                throw new RuntimeException("Failed to deserialize session " + key, ex);
            }
        } catch (CouchbaseException ex) {
            LOG.warn("Read failed to master, attempting read from replica for {}", key);

            if (LOG.isDebugEnabled()) {
                LOG.debug("Read failed to master, attempting read from replica for " + key, ex);
            }

            //We should only read from a replica if there was a failure reading from the primary master.  This typically
            //should only occur when there's a network issue or during an auto-failover (outage).
            RawJsonDocument replicaDoc
                    = bucket.getFromReplica(key, ReplicaMode.FIRST, RawJsonDocument.class).get(0);

            if (replicaDoc == null) {
                return null;
            }

            try {
                return deserialize(replicaDoc.content(), replicaDoc.cas());
            } catch (IOException replicaEx) {
                throw new RuntimeException("Failed to deserialize replica session " + key, replicaEx);
            }
        }
    }

    @Override
    protected void invalidateSessions() throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("invalidateSessions()");
        }

        //Do nothing on shutdown.  We'll let couchbase TTL them for us.
    }

    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, String newClusterId, String newNodeId) {
        String oldKey = getKey(oldClusterId);
        String newKey = getKey(newClusterId);

        if (LOG.isDebugEnabled()) {
            LOG.debug("renewSessionId() oldKey={}, oldNodeId={}, newKey={}, newNodeId={}", oldKey,
                    oldNodeId, newKey, newNodeId);
        }

        try {
            //We are not using CAS when removing because 1) it's not available and 2) since we're removing the session
            //we don't care about consistency because the update will fail by any other thread anyways because the
            //session won't exist which will create the behavior we want and 3) this renewSessionId api isn't really
            //called in our use.
            RawJsonDocument doc = bucket.get(oldKey, RawJsonDocument.class);
            CouchbaseHttpSession session = deserialize(doc.content(), doc.cas());

            assertWritableSession(session, "renewSessionId");

            session.setClusterId(newClusterId);

            doc = RawJsonDocument.create(newKey,
                    getMaxInactiveInterval(),
                    serialize(session));

            bucket.insert(doc);
            bucket.remove(oldKey);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to process JSON", ex);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to deserialize old session " + oldKey, ex);
        }
    }

    @Override
    protected AbstractSession newSession(HttpServletRequest request) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("newSession() request={}", request.getRequestURL());
        }

        return new CouchbaseHttpSession(request);
    }

    @Override
    protected boolean removeSession(String clusterId) {
        String key = getKey(clusterId);
        if (LOG.isDebugEnabled()) {
            LOG.debug("removeSession() key={}", key);
        }

        try {
            //We are not using CAS when removing because 1) it's not available and 2) since we're removing the session
            //we don't care about consistency because the update will fail by any other thread anyways because the
            //session won't exist which will create the behavior we want and 3) this removeSession api isn't really
            //called in our use.
            bucket.remove(key, RawJsonDocument.class);

            return true;
        } catch (DocumentDoesNotExistException ex) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed to remove key {} because it did not exist", key);
            }
            return false;
        } catch (Exception ex) {
            LOG.warn("Failed to remove session", ex);
            return false;
        }
    }

    /**
     * Update data on an existing persisted session.
     *
     * @param session
     */
    protected void updateSession(CouchbaseHttpSession session) {
        if (session == null) {
            return;
        }

        assertWritableSession(session, "updateSession");
        
        try {
            session.setLastSaved(System.currentTimeMillis());
            RawJsonDocument doc = RawJsonDocument.create(getKey(session.getClusterId()),
                    getMaxInactiveInterval(),
                    serialize(session),
                    session.getCas());

            bucket.upsert(doc);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed serialize session to JSON " + session, ex);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Updated session " + session);
        }
    }

    private String serialize(CouchbaseHttpSession session) throws JsonProcessingException {
        SessionJson json = new SessionJson();
        json.setAttributes(session.getAttributeMap());
        json.setLastSaved(session.getLastSaved());
        json.setCreationTime(session.getCreationTime());
        json.setSessionId(session.getClusterId());
        json.setMaxInactiveInterval(session.getMaxInactiveInterval());

        return mapper.writeValueAsString(json);
    }

    private CouchbaseHttpSession deserialize(String content, long cas) throws IOException {
        SessionJson json = mapper.readValue(content, SessionJson.class);

        CouchbaseHttpSession session = new CouchbaseHttpSession(json.getSessionId(),
                json.creationTime,
                System.currentTimeMillis(),
                json.getMaxInactiveInterval());

        session.setCas(cas);
        session.setLastSaved(json.getLastSaved());
        session.addAttributes(json.getAttributes());

        return session;
    }

    /**
     * Verify that the session is writable according to @CouchbaseSession annotation and if not then throw 
     * UnsupportedOperationException to protect the developer from possibly doing something wrong with concurrent
     * writes of the session state object.
     * 
     * @param session
     * @param methodName    The name of the method calling this method so that it's easy for debugging
     */
    private static void assertWritableSession(CouchbaseHttpSession session, String methodName) {
        if (!session.isWrite()) {
            throw new UnsupportedOperationException(
                    methodName + "() - Write operation not supported. "
                    + "See CouchbaseSession annotation and be mindful of "
                    + "allowing concurrent threads access the same session as it will cause failures");
        }
    }
    
    /**
     * A simple container class that allows us to specify exactly what data type we want to serialize to/from JSON
     * without mucking with the parent class and/or fancy serialization techniques in Jackson
     */
    private static class SessionJson {

        private Map<String, Object> attributes;

        private long creationTime;

        private String sessionId;

        private long lastSaved;

        private int maxInactiveInterval;

        /**
         * Get the value of maxInactiveInterval
         *
         * @return the value of maxInactiveInterval
         */
        public int getMaxInactiveInterval() {
            return maxInactiveInterval;
        }

        /**
         * Set the value of maxInactiveInterval
         *
         * @param maxInactiveInterval new value of maxInactiveInterval
         */
        public void setMaxInactiveInterval(int maxInactiveInterval) {
            this.maxInactiveInterval = maxInactiveInterval;
        }

        /**
         * Get the value of lastSaved
         *
         * @return the value of lastSaved
         */
        public long getLastSaved() {
            return lastSaved;
        }

        /**
         * Set the value of lastSaved
         *
         * @param lastSaved new value of lastSaved
         */
        public void setLastSaved(long lastSaved) {
            this.lastSaved = lastSaved;
        }

        /**
         * Get the value of sessionId
         *
         * @return the value of sessionId
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * Set the value of sessionId
         *
         * @param sessionId new value of sessionId
         */
        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        /**
         * Get the value of creationTime
         *
         * @return the value of creationTime
         */
        public long getCreationTime() {
            return creationTime;
        }

        /**
         * Set the value of creationTime
         *
         * @param createdTime new value of creationTime
         */
        public void setCreationTime(long createdTime) {
            this.creationTime = createdTime;
        }

        /**
         * Get the value of attributes
         *
         * @return the value of attributes
         */
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        /**
         * Set the value of attributes
         *
         * @param attributes new value of attributes
         */
        public void setAttributes(Map<String, Object> attributes) {
            this.attributes = attributes;
        }

    }

    /**
     * CouchbaseHttpSession is the real instance of a session that's managed by Jetty
     */
    public final class CouchbaseHttpSession extends AbstractSession {

        /**
         * If dirty, session needs to be (re)persisted
         */
        private boolean dirty = false;

        /**
         * Time in msec since the epoch that the session was last persisted
         */
        private long lastSaved;

        /**
         * Do NOT serialize this into couchbase as it will become incorrect as soon as it's saved. This should be
         * transient and only used for the life of this in-memory session
         */
        private long cas;

        /**
         * Default to having write mode disabled for sessions to protect developers from doing something they didn't
         * intend since we're forced into using HttpSession interface.
         */
        private boolean write = false;

        /**
         * Get the value of write
         *
         * @return the value of write
         */
        public boolean isWrite() {
            return write;
        }

        /**
         * Set the value of write
         *
         * @param write new value of write
         */
        public void setWrite(boolean write) {
            this.write = write;
        }

        /**
         * Get the value of cas
         *
         * @return the value of cas
         */
        public long getCas() {
            return cas;
        }

        /**
         * Set the value of cas
         *
         * @param cas new value of cas
         */
        public void setCas(long cas) {
            this.cas = cas;
        }

        @Override
        protected void setClusterId(String clusterId) {
            super.setClusterId(clusterId);
        }

        @Override
        protected void addAttributes(Map<String, Object> map) {
            super.addAttributes(map);
        }

        /**
         * Session from a request.
         *
         * @param request
         */
        protected CouchbaseHttpSession(HttpServletRequest request) {
            super(CouchbaseSessionManager.this, request);
        }

        /**
         * Session restored from database
         *
         * @param sessionId
         * @param created
         * @param accessed
         * @param maxInterval
         */
        protected CouchbaseHttpSession(String sessionId, long created, long accessed, int maxInterval) {
            super(CouchbaseSessionManager.this, created, accessed, sessionId);
        }

        public long getLastSaved() {
            return lastSaved;
        }

        public void setLastSaved(long time) {
            lastSaved = time;
        }

        @Override
        public void setAttribute(String name, Object value) {
            assertWritableSession(this, "setAttribute");

            dirty = (updateAttribute(name, value) || dirty);
        }

        @Override
        public void removeAttribute(String name) {
            assertWritableSession(this, "removeAttribute");

            super.removeAttribute(name);
            dirty = true;
        }

        @Override
        public void setMaxInactiveInterval(int secs) {
            super.setMaxInactiveInterval(secs);
        }

        @Override
        public void removeValue(String name) throws IllegalStateException {
            assertWritableSession(this, "removeValue");

            super.removeValue(name);
        }

        @Override
        public void putValue(String name, Object value) throws IllegalStateException {
            assertWritableSession(this, "putValue");

            super.putValue(name, value);
        }

        /**
         * Exit from session
         *
         * @see org.eclipse.jetty.server.session.AbstractSession#complete()
         */
        @Override
        protected void complete() {
            super.complete();
            try {
                if (isValid()) {
                    if (dirty) {
                        //The session attributes have changed, write to the db, ensuring
                        //http passivation/activation listeners called
                        willPassivate();
                        updateSession(this);
                        didActivate();
                    }
                }
            } catch (Exception e) {
                LOG.error("Problem persisting changed session data id=" + getId(), e);
            } finally {
                dirty = false;
            }
        }

        @Override
        protected void timeout() throws IllegalStateException {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Timing out session id=" + getClusterId());
            }
            super.timeout();
        }

        @Override
        public String toString() {
            return "Session id=" + getId() + ",dirty=" + dirty + ",created="
                    + getCreationTime() + ",accessed=" + getAccessed() + ",lastAccessed=" + getLastAccessedTime()
                    + ",maxInterval=" + getMaxInactiveInterval() + ",lastSaved="
                    + lastSaved;
        }
    }

}
