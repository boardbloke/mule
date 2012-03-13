/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.file;

import org.mule.DefaultMuleMessage;
import org.mule.api.DefaultMuleException;
import org.mule.api.MessagingException;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.config.MuleProperties;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.endpoint.InboundEndpoint;
import org.mule.api.lifecycle.CreateException;
import org.mule.api.transport.Connector;
import org.mule.api.transport.PropertyScope;
import org.mule.api.execution.ExecutionCallback;
import org.mule.api.execution.ExecutionTemplate;
import org.mule.transport.AbstractPollingMessageReceiver;
import org.mule.transport.ConnectException;
import org.mule.transport.file.i18n.FileMessages;
import org.mule.util.FileUtils;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.collections.comparators.ReverseComparator;

/**
 * <code>FileMessageReceiver</code> is a polling listener that reads files from a
 * directory.
 */

public class FileMessageReceiver extends AbstractPollingMessageReceiver
{
    public static final String COMPARATOR_CLASS_NAME_PROPERTY = "comparator";
    public static final String COMPARATOR_REVERSE_ORDER_PROPERTY = "reverseOrder";

    private static final List<File> NO_FILES = new ArrayList<File>();

    private FileConnector fileConnector = null;
    private String readDir = null;
    private String moveDir = null;
    private String workDir = null;
    private File readDirectory = null;
    private File moveDirectory = null;
    private String moveToPattern = null;
    private String workFileNamePattern = null;
    private FilenameFilter filenameFilter = null;
    private FileFilter fileFilter = null;
    private boolean forceSync;

    public FileMessageReceiver(Connector connector,
                               FlowConstruct flowConstruct,
                               InboundEndpoint endpoint,
                               String readDir,
                               String moveDir,
                               String moveToPattern,
                               long frequency) throws CreateException
    {
        super(connector, flowConstruct, endpoint);
        this.fileConnector = (FileConnector) connector;

        setFrequency(frequency);

        this.readDir = readDir;
        this.moveDir = moveDir;
        this.moveToPattern = moveToPattern;
        this.workDir = fileConnector.getWorkDirectory();
        this.workFileNamePattern = fileConnector.getWorkFileNamePattern();

        if (endpoint.getFilter() instanceof FilenameFilter)
        {
            filenameFilter = (FilenameFilter) endpoint.getFilter();
        }
        else if (endpoint.getFilter() instanceof FileFilter)
        {
            fileFilter = (FileFilter) endpoint.getFilter();
        }
        else if (endpoint.getFilter() != null)
        {
            throw new CreateException(FileMessages.invalidFileFilter(endpoint.getEndpointURI()), this);
        }

        checkMustForceSync();
    }

    /**
     * If we will be autodeleting File objects, events must be processed synchronously to avoid a race
     */
    protected void checkMustForceSync() throws CreateException
    {
        boolean connectorIsAutoDelete = false;
        boolean isStreaming = false;
        if (connector instanceof FileConnector)
        {
            connectorIsAutoDelete = fileConnector.isAutoDelete();
            isStreaming = fileConnector.isStreaming();
        }

        boolean messageFactoryConsumes = (createMuleMessageFactory() instanceof FileContentsMuleMessageFactory);

        forceSync = connectorIsAutoDelete && !messageFactoryConsumes && !isStreaming;
    }

    @Override
    protected void doConnect() throws Exception
    {
        if (readDir != null)
        {
            readDirectory = FileUtils.openDirectory(readDir);
            if (!(readDirectory.canRead()))
            {
                throw new ConnectException(FileMessages.fileDoesNotExist(readDirectory.getAbsolutePath()), this);
            }
            else
            {
                logger.debug("Listening on endpointUri: " + readDirectory.getAbsolutePath());
            }
        }

        if (moveDir != null)
        {
            moveDirectory = FileUtils.openDirectory((moveDir));
            if (!(moveDirectory.canRead()) || !moveDirectory.canWrite())
            {
                throw new ConnectException(FileMessages.moveToDirectoryNotWritable(), this);
            }
        }
    }

    @Override
    protected void doDisconnect() throws Exception
    {
        // template method
    }

    @Override
    protected void doDispose()
    {
        // nothing to do
    }

    @Override
    public void poll()
    {
        try
        {
            List<File> files = this.listFiles();
            if (logger.isDebugEnabled())
            {
                logger.debug("Files: " + files.toString());
            }
            Comparator<File> comparator = getComparator();
            if (comparator != null)
            {
                Collections.sort(files, comparator);
            }
            for (File file : files)
            {
                if (getLifecycleState().isStopping())
                {
                    break;
                }
                // don't process directories
                if (file.isFile())
                {
                    processFile(file);
                }
            }
        }
        catch (Exception e)
        {
            getConnector().getMuleContext().getExceptionListener().handleException(e);
        }
    }

    @Override
    protected boolean pollOnPrimaryInstanceOnly()
    {
        return true;
    }

    public void processFile(File file) throws MuleException
    {
        //TODO RM*: This can be put in a Filter. Also we can add an AndFileFilter/OrFileFilter to allow users to
        //combine file filters (since we can only pass a single filter to File.listFiles, we would need to wrap
        //the current And/Or filters to extend {@link FilenameFilter}
        boolean checkFileAge = fileConnector.getCheckFileAge();
        if (checkFileAge)
        {
            long fileAge = fileConnector.getFileAge();
            long lastMod = file.lastModified();
            long now = System.currentTimeMillis();
            long thisFileAge = now - lastMod;
            if (thisFileAge < fileAge)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("The file has not aged enough yet, will return nothing for: " + file);
                }
                return;
            }
        }

        // Perform some quick checks to make sure file can be processed
        if (!(file.canRead() && file.exists() && file.isFile()))
        {
            throw new DefaultMuleException(FileMessages.fileDoesNotExist(file.getName()));
        }

        // don't process a file that is locked by another process (probably still being written)
        if (!attemptFileLock(file))
        {
            return;
        }
        else if(logger.isInfoEnabled())
        {
            logger.info("Lock obtained on file: " + file.getAbsolutePath());
        }

        // This isn't nice but is needed as MuleMessage is required to resolve
        // destination file name
        DefaultMuleMessage fileParserMessasge = new DefaultMuleMessage(null, connector.getMuleContext());
        fileParserMessasge.setOutboundProperty(FileConnector.PROPERTY_ORIGINAL_FILENAME, file.getName());

        // The file may get moved/renamed here so store the original file info.
        final String originalSourceFile = file.getAbsolutePath();
        final String originalSourceFileName = file.getName();
        final File sourceFile;
        if (workDir != null)
        {
            String workFileName = file.getName();

            workFileName = fileConnector.getFilenameParser().getFilename(fileParserMessasge, workFileNamePattern);
            // don't use new File() directly, see MULE-1112
            File workFile = FileUtils.newFile(workDir, workFileName);

            fileConnector.move(file, workFile);
            // Now the Work File is the Source file
            sourceFile = workFile;
        }
        else
        {
            sourceFile = file;
        }
        // Do not use the original file handle beyond this point since it may have moved.
        file = null;

        // set up destination file
        File destinationFile = null;
        if (moveDir != null)
        {
            String destinationFileName = originalSourceFileName;
            if (moveToPattern != null)
            {
                destinationFileName = fileConnector.getFilenameParser().getFilename(fileParserMessasge,
                    moveToPattern);
            }
            // don't use new File() directly, see MULE-1112
            destinationFile = FileUtils.newFile(moveDir, destinationFileName);
        }

        MuleMessage message = null;
        String encoding = endpoint.getEncoding();
        try
        {
            if (fileConnector.isStreaming())
            {
                ReceiverFileInputStream payload = createReceiverFileInputStream(sourceFile, destinationFile);
                message = createMuleMessage(payload, encoding);
            }
            else
            {
                message = createMuleMessage(sourceFile, encoding);
            }
        }
        catch (FileNotFoundException e)
        {
            // we can ignore since we did manage to acquire a lock, but just in case
            logger.error("File being read disappeared!", e);
            return;
        }

        message.setOutboundProperty(FileConnector.PROPERTY_ORIGINAL_FILENAME, originalSourceFileName);
        if (forceSync)
        {
            message.setProperty(MuleProperties.MULE_FORCE_SYNC_PROPERTY, Boolean.TRUE, PropertyScope.INBOUND);
        }

        final Object originalPayload = message.getPayload();

        ExecutionTemplate<MuleEvent> executionTemplate = createExecutionTemplate();
        final MuleMessage finalMessage = message;

        if (fileConnector.isStreaming())
        {
            processWithStreaming(sourceFile, (ReceiverFileInputStream) originalPayload, executionTemplate, finalMessage);
        }
        else
        {
            processWithoutStreaming(originalSourceFile, originalSourceFileName, sourceFile, destinationFile, executionTemplate, finalMessage);
        }
    }

    private void processWithoutStreaming(String originalSourceFile, final String originalSourceFileName, final File sourceFile,final File destinationFile, ExecutionTemplate<MuleEvent> executionTemplate, final MuleMessage finalMessage) throws DefaultMuleException
    {
        try
        {
            executionTemplate.execute(new ExecutionCallback<MuleEvent>()
            {
                @Override
                public MuleEvent process() throws Exception
                {
                    moveAndDelete(sourceFile, destinationFile, originalSourceFileName, finalMessage);
                    return null;
                }
            });
            deleteFileIfRequired(sourceFile, destinationFile);
        }
        catch (MessagingException e)
        {
            if (e.causedRollback())
            {
                rollbackFileMoveIfRequired(originalSourceFile, sourceFile);
            }
            else
            {
                deleteFileIfRequired(sourceFile, destinationFile);
            }
        }
        catch (Exception e)
        {
            rollbackFileMoveIfRequired(originalSourceFile, sourceFile);
            connector.getMuleContext().getExceptionListener().handleException(e);
        }
    }

    private void processWithStreaming(final File sourceFile, final ReceiverFileInputStream originalPayload, ExecutionTemplate<MuleEvent> executionTemplate, final MuleMessage finalMessage)
    {
        try
        {
            executionTemplate.execute(new ExecutionCallback<MuleEvent>()
            {
                @Override
                public MuleEvent process() throws Exception
                {
                    try
                    {
                        // If we are streaming no need to move/delete now, that will be done when
                        // stream is closed
                        finalMessage.setOutboundProperty(FileConnector.PROPERTY_FILENAME, sourceFile.getName());
                        FileMessageReceiver.this.routeMessage(finalMessage);
                    } catch (Exception e)
                    {
                        //ES will try to close stream but FileMessageReceiver is the one that must close it.
                        originalPayload.setStreamProcessingError(true);
                        throw e;
                    }
                    return null;
                }
            });
        }
        catch (MessagingException e)
        {
            if (!e.causedRollback())
            {
                try
                {
                    originalPayload.setStreamProcessingError(false);
                    originalPayload.close();
                }
                catch (Exception ex)
                {
                    logger.warn(ex);
                }
            }
        }
        catch (Exception e)
        {
            connector.getMuleContext().getExceptionListener().handleException(e);
        }
    }

    protected ReceiverFileInputStream createReceiverFileInputStream(File sourceFile, File destinationFile) throws FileNotFoundException
    {
        return new ReceiverFileInputStream(sourceFile,
            fileConnector.isAutoDelete(), destinationFile);
    }

    private void rollbackFileMoveIfRequired(String originalSourceFile, File sourceFile)
    {
        if (!sourceFile.getAbsolutePath().equals(originalSourceFile))
        {
            try
            {
                rollbackFileMove(sourceFile, originalSourceFile);
            }
            catch (IOException iox)
            {
                logger.warn(iox);
            }
        }
    }

    private void moveAndDelete(final File sourceFile, File destinationFile,
        String sourceFileOriginalName, MuleMessage message) throws MuleException
    {
        // If we are moving the file to a read directory, move it there now and
        // hand over a reference to the
        // File in its moved location
        if (destinationFile != null)
        {
            // move sourceFile to new destination
            try
            {
                FileUtils.moveFile(sourceFile, destinationFile);
            }
            catch (IOException e)
            {
                // move didn't work - bail out (will attempt rollback)
                throw new DefaultMuleException(FileMessages.failedToMoveFile(
                    sourceFile.getAbsolutePath(), destinationFile.getAbsolutePath()));
            }

            // create new Message for destinationFile
            message = createMuleMessage(destinationFile, endpoint.getEncoding());
            message.setOutboundProperty(FileConnector.PROPERTY_FILENAME, destinationFile.getName());
            message.setOutboundProperty(FileConnector.PROPERTY_ORIGINAL_FILENAME, sourceFileOriginalName);
        }

        // finally deliver the file message
        this.routeMessage(message);
    }

    private void deleteFileIfRequired(File sourceFile, File destinationFile) throws DefaultMuleException
    {
        // at this point msgAdapter either points to the old sourceFile
        // or the new destinationFile.
        if (fileConnector.isAutoDelete())
        {
            // no moveTo directory
            if (destinationFile == null)
            {
                // delete source
                if (!sourceFile.delete())
                {
                    throw new DefaultMuleException(FileMessages.failedToDeleteFile(sourceFile));
                }
            }
        }
    }

    /**
     * Try to acquire a lock on a file and release it immediately. Usually used as a
     * quick check to see if another process is still holding onto the file, e.g. a
     * large file (more than 100MB) is still being written to.
     *
     * @param sourceFile file to check
     * @return <code>true</code> if the file can be locked
     */
    protected boolean attemptFileLock(final File sourceFile) throws MuleException
    {
        // check if the file can be processed, be sure that it's not still being
        // written
        // if the file can't be locked don't process it yet, since creating
        // a new FileInputStream() will throw an exception
        FileLock lock = null;
        FileChannel channel = null;
        boolean fileCanBeLocked = false;
        try
        {
            channel = new RandomAccessFile(sourceFile, "rw").getChannel();

            // Try acquiring the lock without blocking. This method returns
            // null or throws an exception if the file is already locked.
            lock = channel.tryLock();
        }
        catch (FileNotFoundException fnfe)
        {
            throw new DefaultMuleException(FileMessages.fileDoesNotExist(sourceFile.getName()));
        }
        catch (IOException e)
        {
            // Unable to create a lock. This exception should only be thrown when
            // the file is already locked. No sense in repeating the message over
            // and over.
        }
        finally
        {
            if (lock != null)
            {
                // if lock is null the file is locked by another process
                fileCanBeLocked = true;
                try
                {
                    // Release the lock
                    lock.release();
                }
                catch (IOException e)
                {
                    // ignore
                }
            }

            if (channel != null)
            {
                try
                {
                    // Close the file
                    channel.close();
                }
                catch (IOException e)
                {
                    // ignore
                }
            }
        }

        return fileCanBeLocked;
    }

    /**
     * Get a list of files to be processed.
     *
     * @return an array of files to be processed.
     * @throws org.mule.api.MuleException which will wrap any other exceptions or
     *             errors.
     */
    List<File> listFiles() throws MuleException
    {
        try
        {
            List<File> files = new ArrayList<File>();
            this.basicListFiles(readDirectory, files);
            return (files.isEmpty() ? NO_FILES : files);
        }
        catch (Exception e)
        {
            throw new DefaultMuleException(FileMessages.errorWhileListingFiles(), e);
        }
    }

    protected void basicListFiles(File currentDirectory, List<File> discoveredFiles)
    {
        File[] files;
        if (fileFilter != null)
        {
            files = currentDirectory.listFiles(fileFilter);
        }
        else
        {
            files = currentDirectory.listFiles(filenameFilter);
        }

        // the listFiles calls above may actually return null (check the JDK code).
        if (files == null)
        {
            return;
        }

        for (File file : files)
        {
            if (!file.isDirectory())
            {
                discoveredFiles.add(file);
            }
            else
            {
                if (fileConnector.isRecursive())
                {
                    this.basicListFiles(file, discoveredFiles);
                }
            }
        }
    }

    /**
     * Exception tolerant roll back method
     *
     * @throws Throwable
     */
    protected void rollbackFileMove(File sourceFile, String destinationFilePath) throws IOException
    {
        try
        {
            FileUtils.moveFile(sourceFile, FileUtils.newFile(destinationFilePath));
        }
        catch (IOException t)
        {
            logger.debug("rollback of file move failed: " + t.getMessage());
            throw t;
        }
    }

    protected Comparator<File> getComparator() throws Exception
    {
        Object comparatorClassName = getEndpoint().getProperty(COMPARATOR_CLASS_NAME_PROPERTY);
        if (comparatorClassName != null)
        {
            Object reverseProperty = this.getEndpoint().getProperty(COMPARATOR_REVERSE_ORDER_PROPERTY);
            boolean reverse = false;
            if (reverseProperty != null)
            {
                reverse = Boolean.valueOf((String) reverseProperty);
            }

            Class<?> clazz = Class.forName(comparatorClassName.toString());
            Comparator<?> comparator = (Comparator<?>)clazz.newInstance();
            return reverse ? new ReverseComparator(comparator) : comparator;
        }
        return null;
    }
}
