/*
 * Copyright 2005 SymphonySoft Limited. All rights reserved.
 * http://www.symphonysoft.com
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 * 
 * ------------------------------------------------------------------------------------------------------
 * $Header$
 * $Revision$
 * $Date$
 */
package org.mule.jbi.management;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.MissingResourceException;
import java.util.logging.Logger;

import javax.jbi.JBIException;
import javax.jbi.component.Bootstrap;
import javax.jbi.component.Component;
import javax.jbi.component.ComponentContext;
import javax.jbi.component.ComponentLifeCycle;
import javax.jbi.component.InstallationContext;
import javax.jbi.management.InstallerMBean;
import javax.jbi.management.MBeanNames;
import javax.jbi.messaging.DeliveryChannel;
import javax.jbi.messaging.MessagingException;
import javax.jbi.servicedesc.ServiceEndpoint;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.xml.namespace.QName;

import org.mule.jbi.JbiContainer;
import org.mule.jbi.framework.ComponentInfo;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.sun.java.xml.ns.jbi.JbiDocument.Jbi;

public class Installer implements InstallerMBean, InstallationContext, ComponentContext {

	private JbiContainer container;
	private File installRoot;
	private Jbi jbi;
	private List classPathElements;
	private Bootstrap bootstrap;
	
	public Installer(JbiContainer container, File installRoot, Jbi jbi) {
		this.container = container;
		this.installRoot = installRoot;
		this.jbi = jbi;
		this.classPathElements = Arrays.asList(this.jbi.getComponent().getComponentClassPath().getPathElementArray());
	}
	
	protected ClassLoader createBootstrapClassLoader() throws Exception {
		if (this.jbi.getComponent().isSetBootstrapClassLoaderDelegation()) {
			// TODO: use this
		}
		return createClassLoader(Arrays.asList(this.jbi.getComponent().getBootstrapClassPath().getPathElementArray()));
	}
	
	protected Bootstrap createBootstrap() throws Exception {
		ClassLoader loader = createBootstrapClassLoader();
		String bsCl = this.jbi.getComponent().getBootstrapClassName();
		Class cl = Class.forName(bsCl, true, loader);
		Bootstrap bs = (Bootstrap) cl.newInstance();
		return bs;
	}
	
	protected ClassLoader createComponentClassLoader() throws Exception {
		if (this.jbi.getComponent().isSetComponentClassLoaderDelegation()) {
			// TODO: use this
		}
		return createClassLoader(this.classPathElements);
	}
	
	protected ClassLoader createClassLoader(List classPath) throws Exception {
		List bsCpUrls = new ArrayList();
		for (Iterator it = classPath.iterator(); it.hasNext();) {
			String cpElement = (String) it.next();
			bsCpUrls.add(new File(this.installRoot, cpElement).toURL());
		}
		ClassLoader loader = new URLClassLoader((URL[]) bsCpUrls.toArray(new URL[bsCpUrls.size()]));
		return loader;
	}
	
	protected Component createComponent() throws Exception {
		ClassLoader loader = createComponentClassLoader();
		Class cl = Class.forName(getComponentClassName(), true, loader);
		Component c = (Component) cl.newInstance();
		return c;
	}
	
	public void init() throws Exception {
		boolean success = false;
		this.bootstrap = createBootstrap();
		try {
			this.bootstrap.init(this);
			success = true;
		} finally {
			if (!success) {
				this.bootstrap.cleanUp();
			}
		}
	}
	
	public DocumentFragment getInstallationDescriptorExtension() {
		Node node = Installer.this.jbi.getComponent().getDomNode();
		DocumentFragment d = node.getOwnerDocument().createDocumentFragment();
		NodeList l = node.getChildNodes();
		for (int i = 0; i < l.getLength(); i++) {
			Node c = l.item(i);
			String uri = Installer.this.jbi.getDomNode().getNamespaceURI();
			if (!uri.equals(c.getNamespaceURI())) {
				d.appendChild(c.cloneNode(true));
			}
		}
		return d;
	}

	public synchronized String getInstallRoot() {
		return installRoot.getAbsolutePath();
	}

	public synchronized ObjectName install() throws JBIException {
		try {
			this.bootstrap.onInstall();
			String name = this.jbi.getComponent().getIdentification().getName();
			javax.jbi.component.Component c = createComponent();
			boolean isEngine = (this.jbi.getComponent().getType() == com.sun.java.xml.ns.jbi.ComponentDocument.Component.Type.SERVICE_ENGINE); 
			ComponentLifeCycle lf = c.getLifeCycle();
			ObjectName objName = createComponentLifeCycleName(name);
			ComponentInfo info = this.container.getComponentRegistry().registerComponent(name, isEngine);
			info.setObjectName(objName);
			info.setComponent(c);
			info.setInstallRoot(getInstallRoot());
			lf.init(info.getContext());
			org.mule.jbi.management.ComponentLifeCycle lfmbean = new org.mule.jbi.management.ComponentLifeCycle(lf);
			this.container.getMBeanServer().registerMBean(lfmbean, objName);
			return objName;
		} catch (Exception e) {
			throw new JBIException(e);
		} finally {
			this.bootstrap.cleanUp();
		}
	}

	private ObjectName createComponentLifeCycleName(String name) {
		return this.container.createComponentMBeanName(name, "lifecycle", null);
	}

	public synchronized boolean isInstalled() {
		String name = getComponentName();
		ComponentInfo info = this.container.getComponentRegistry().getComponent(name);
		return info != null;
	}

	public synchronized void uninstall() throws JBIException {
		// TODO Auto-generated method stub

	}

	public synchronized ObjectName getInstallerConfigurationMBean() throws JBIException {
		if (this.bootstrap != null) {
			return this.bootstrap.getExtensionMBeanName();
		}
		return null;
	}

	public String getComponentClassName() {
		return this.jbi.getComponent().getComponentClassName().getDomNode().getFirstChild().getNodeValue();
	}

	public List getClassPathElements() {
		return this.classPathElements;
	}

	public String getComponentName() {
		return this.jbi.getComponent().getIdentification().getName();
	}

	public ComponentContext getContext() {
		return this;
	}

	public boolean isInstall() {
		// TODO Auto-generated method stub
		return false;
	}

	public void setClassPathElements(List classPathElements) {
		this.classPathElements = classPathElements;
	}

	public ServiceEndpoint activateEndpoint(QName serviceName, String endpointName) throws JBIException {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public void deactivateEndpoint(ServiceEndpoint endpoint) throws JBIException {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public void registerExternalEndpoint(ServiceEndpoint externalEndpoint) throws JBIException {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public void deregisterExternalEndpoint(ServiceEndpoint externalEndpoint) throws JBIException {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public ServiceEndpoint resolveEndpointReference(DocumentFragment epr) {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public DeliveryChannel getDeliveryChannel() throws MessagingException {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public ServiceEndpoint getEndpoint(QName service, String name) {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public Document getEndpointDescriptor(ServiceEndpoint endpoint) throws JBIException {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public ServiceEndpoint[] getEndpoints(QName interfaceName) {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public ServiceEndpoint[] getEndpointsForService(QName serviceName) {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public ServiceEndpoint[] getExternalEndpoints(QName interfaceName) {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public ServiceEndpoint[] getExternalEndpointsForService(QName serviceName) {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public Logger getLogger(String suffix, String resourceBundleName) throws MissingResourceException, JBIException {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public MBeanNames getMBeanNames() {
		throw new IllegalStateException("Illegal call in an installation context");
	}

	public MBeanServer getMBeanServer() {
		return this.container.getMBeanServer();
	}

	public InitialContext getNamingContext() {
		return this.container.getNamingContext();
	}

	public Object getTransactionManager() {
		return this.container.getTransactionManager();
	}

	public String getWorkspaceRoot() {
		return this.container.getWorkingDir().getAbsolutePath();
	}
}
