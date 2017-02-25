package com.cvent.couchbase.session;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.HttpConnection;

import org.slf4j.LoggerFactory;

/**
 * This filter acts as a bridge between jetty and jersey so that we can extract the cookie that was created by jetty
 * and pass it along through the jersey response.
 */
public class HttpSessionCookieFilter implements Filter {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(HttpSessionCookieFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
            ServletException {

        chain.doFilter(request, response);

        if (response instanceof HttpServletResponse) {
            HttpServletResponse httpServletResponse = (HttpServletResponse) response;

            if (HttpConnection.getCurrentConnection()
                    .getHttpChannel()
                    .getResponse()
                    .getHttpFields().containsKey(HttpHeader.SET_COOKIE.asString())) {

                String cookie = HttpConnection
                        .getCurrentConnection()
                        .getHttpChannel()
                        .getResponse()
                        .getHttpFields().get(HttpHeader.SET_COOKIE.asString());

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Adding session cookie to response for subsequent requests {}", cookie);
                }

                httpServletResponse.addHeader(HttpHeader.SET_COOKIE.asString(), cookie);
            }
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // nop
    }

    @Override
    public void destroy() {
        // nop
    }

}
