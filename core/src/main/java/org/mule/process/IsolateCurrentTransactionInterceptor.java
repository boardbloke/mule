/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.process;

import org.mule.api.transaction.Transaction;
import org.mule.api.transaction.TransactionConfig;
import org.mule.transaction.TransactionCoordination;

public class IsolateCurrentTransactionInterceptor<T> implements ProcessingInterceptor<T>
{
    private ProcessingInterceptor<T> next;
    private TransactionConfig transactionConfig;

    public IsolateCurrentTransactionInterceptor(ProcessingInterceptor<T> nextProcessingInterceptor, TransactionConfig transactionConfig)
    {
        this.next = nextProcessingInterceptor;
        this.transactionConfig = transactionConfig;
    }


    @Override
    public T execute(ProcessingCallback<T> muleEventProcessingCallback) throws Exception
    {
        boolean transactionIsolated = false;
        try
        {
            if (transactionConfig.getAction() == TransactionConfig.ACTION_NOT_SUPPORTED)
            {
                Transaction transaction = TransactionCoordination.getInstance().getTransaction();
                if (transaction != null)
                {
                    TransactionCoordination.getInstance().isolateTransaction();
                    transactionIsolated = true;
                }
            }
            return next.execute(muleEventProcessingCallback);
        }
        finally 
        {
            if (transactionIsolated)
            {
                TransactionCoordination.getInstance().restoreIsolatedTransaction();
            }
        }
    }
}