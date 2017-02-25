package com.cvent.couchbase.session;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A session that allows us to control read/write access to the session. Defaulting to readonly so that it helps protect
 * developers from making stupid mistakes. If a session does NOT exist and write == false, create == true then the
 * session will still be created and saved.
 *
 * @author bryan
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface CventSession {

    /**
     * @return Whether or not to create the session when queried. Defaults to true.
     */
    boolean create() default true;

    /**
     * @return Whether or not to allow write operations back to the session. If false then any write operation results
     * in a UnsupportedOperationException that the developer should see and can fix.
     *
     * Note: If a session does NOT exist and write == false, create == true then the session will still be created and
     * saved.
     */
    boolean write() default false;
}
