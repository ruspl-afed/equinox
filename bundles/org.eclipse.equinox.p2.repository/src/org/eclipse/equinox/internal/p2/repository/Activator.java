/*******************************************************************************
 * Copyright (c) 2009, Cloudsmith Inc.
 * The code, documentation and other materials contained herein have been
 * licensed under the Eclipse Public License - v 1.0 by the copyright holder
 * listed above, as the Initial Contributor under such license. The text of
 * such license is available at www.eclipse.org.
 ******************************************************************************/

package org.eclipse.equinox.internal.p2.repository;

import org.eclipse.ecf.filetransfer.service.IRetrieveFileTransferFactory;
import org.eclipse.equinox.internal.p2.core.helpers.ServiceHelper;
import org.osgi.framework.*;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The activator class controls the plug-in life cycle.
 * This activator has helper methods to get file transfer service tracker, and
 * for making sure required ECF bundles are started.
 */
public class Activator implements BundleActivator {

	public static final String ID = "org.eclipse.equinox.p2.repository"; //$NON-NLS-1$
	private static BundleContext context;
	// tracker for ECF service
	private ServiceTracker retrievalFactoryTracker;

	// The shared instance
	private static Activator plugin;

	public void start(BundleContext aContext) throws Exception {
		Activator.context = aContext;
		Activator.plugin = this;
	}

	public void stop(BundleContext aContext) throws Exception {
		Activator.context = null;
		Activator.plugin = null;
	}

	public static BundleContext getContext() {
		return Activator.context;
	}

	/**
	 * Get singleton instance.
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}

	/**
	 * Returns a {@link IRetrieveFileTransferFactory} using a {@link ServiceTracker} after having attempted
	 * to start the bundle "org.eclipse.ecf.provider.filetransfer". If something is wrong with the configuration
	 * this method returns null.
	 * @return a factory, or null, if configuration is incorrect
	 */
	public IRetrieveFileTransferFactory getRetrieveFileTransferFactory() {
		return (IRetrieveFileTransferFactory) getFileTransferServiceTracker().getService();
	}

	public synchronized void useJREHttpClient() {
		// TODO: Check of JREHttpClient is already in use - then do nothing, else switch to JRE HTTP Client
		//
		// TODO - Log that the http client was switched.
	}

	/**
	 * Gets the singleton ServiceTracker for the IRetrieveFileTransferFactory and starts the bundles
	 * "org.eclipse.ecf" and
	 * "org.eclipse.ecf.provider.filetransfer" on first call.
	 * @return  ServiceTracker
	 */
	private synchronized ServiceTracker getFileTransferServiceTracker() {
		if (retrievalFactoryTracker == null) {
			retrievalFactoryTracker = new ServiceTracker(Activator.getContext(), IRetrieveFileTransferFactory.class.getName(), null);
			retrievalFactoryTracker.open();
			startBundle("org.eclipse.ecf"); //$NON-NLS-1$
			startBundle("org.eclipse.ecf.provider.filetransfer"); //$NON-NLS-1$
		}
		return retrievalFactoryTracker;
	}

	private boolean startBundle(String bundleId) {
		PackageAdmin packageAdmin = (PackageAdmin) ServiceHelper.getService(Activator.getContext(), PackageAdmin.class.getName());
		if (packageAdmin == null)
			return false;

		Bundle[] bundles = packageAdmin.getBundles(bundleId, null);
		if (bundles != null && bundles.length > 0) {
			for (int i = 0; i < bundles.length; i++) {
				try {
					if ((bundles[0].getState() & Bundle.INSTALLED) == 0) {
						bundles[0].start();
						return true;
					}
				} catch (BundleException e) {
					// failed, try next bundle
				}
			}
		}
		return false;
	}

}
