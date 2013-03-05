/*
 * $Id: TypedPlaceholderMule1887TestCase.java 22431 2011-07-18 07:40:35Z dirk.olmes $
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.http.issues;

import org.mule.transport.http.AbstractNamespaceHandlerTestCase;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.tcp.LegacyIoTest;

import org.junit.Test;

@LegacyIoTest
public class TypedPlaceholderMule1887TestCase extends AbstractNamespaceHandlerTestCase
{

    public TypedPlaceholderMule1887TestCase()
    {
        super("http");
    }

    @Override
    protected String getConfigResources()
    {
        return "typed-placeholder-mule-1887-test.xml";
    }

    @Test
    public void testConnectorProperties()
    {
        HttpConnector connector =
                (HttpConnector) muleContext.getRegistry().lookupConnector("httpConnector");
        testBasicProperties(connector);
    }

}
