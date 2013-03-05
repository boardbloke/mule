/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.http;

import org.mule.api.endpoint.MalformedEndpointException;
import org.mule.transport.tcp.TcpEndpointURIBuilder;
import org.mule.util.StringUtils;

import java.net.URI;
import java.util.Properties;

public class HttpEndpointURIBuilder extends TcpEndpointURIBuilder
{
    @Override
    protected void setEndpoint(URI uri, Properties props) throws MalformedEndpointException
    {
        address = "";
        if (uri.getHost() != null)
        {
            // set the endpointUri to be a proper url if host and port are set
            this.address = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() != -1)
            {
                address += ":" + uri.getPort();
            }
        }
        if (StringUtils.isNotBlank(uri.getRawPath()))
        {
            address += uri.getRawPath();
        }

        if (StringUtils.isNotBlank(uri.getRawQuery()))
        {
            address += "?" + uri.getRawQuery();
        }
    }

    @Override
    protected String getNioSchema(URI uri)
    {
        if ("http".equalsIgnoreCase(uri.getScheme()))
        {
            return "niohttp";
        }
        else
        {
            return super.getNioSchema(uri); 
        }
    }
}


