/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.nio.http.filters;

import org.mule.api.MuleMessage;
import org.mule.routing.filters.WildcardFilter;
import org.mule.transport.nio.http.HttpConnector;

/**
 * <code>HttpRequestWildcardFilter</code> filters out wildcard URL expressions. You
 * can use a comma-separated list of URL patterns such as "*.gif, *blah*".
 */
public class HttpRequestWildcardFilter extends WildcardFilter
{
    public HttpRequestWildcardFilter()
    {
        super();
    }

    public HttpRequestWildcardFilter(final String pattern)
    {
        super(pattern);
    }

    @Override
    public boolean accept(final MuleMessage message)
    {
        final Object requestProperty = message.getInboundProperty(HttpConnector.HTTP_REQUEST_PROPERTY);
        return super.accept(requestProperty);
    }
}
