/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 ******************************************************************************/
package org.eclipse.equinox.internal.p2.director;

import java.net.URL;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.internal.p2.core.helpers.ServiceHelper;
import org.eclipse.equinox.internal.p2.resolution.ResolutionHelper;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.director.IPlanner;
import org.eclipse.equinox.p2.director.ProvisioningPlan;
import org.eclipse.equinox.p2.engine.Operand;
import org.eclipse.equinox.p2.engine.Profile;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.query.InstallableUnitQuery;
import org.eclipse.equinox.p2.metadata.query.UpdateQuery;
import org.eclipse.equinox.p2.metadata.repository.IMetadataRepository;
import org.eclipse.equinox.p2.metadata.repository.IMetadataRepositoryManager;
import org.eclipse.equinox.p2.query.Collector;
import org.eclipse.equinox.p2.query.Query;
import org.eclipse.osgi.service.resolver.VersionRange;
import org.eclipse.osgi.util.NLS;

public class SimplePlanner implements IPlanner {
	static final int ExpandWork = 12;

	public ProvisioningPlan getInstallPlan(IInstallableUnit[] installRoots, Profile profile, URL[] metadataRepositories, IProgressMonitor monitor) {
		SubMonitor sub = SubMonitor.convert(monitor, ExpandWork);
		sub.setTaskName(Messages.Director_Task_Resolving_Dependencies);
		try {
			MultiStatus result = new MultiStatus(DirectorActivator.PI_DIRECTOR, 1, Messages.Director_Install_Problems, null);
			// Get the list of ius installed in the profile we are installing into
			IInstallableUnit[] alreadyInstalled = getInstallableUnits(profile);

			// If any of these are already installed, return a warning status
			// specifying that they are already installed.
			for (int i = 0; i < installRoots.length; i++)
				for (int j = 0; j < alreadyInstalled.length; j++)
					if (installRoots[i].equals(alreadyInstalled[j]))
						result.merge(new Status(IStatus.WARNING, DirectorActivator.PI_DIRECTOR, NLS.bind(Messages.Director_Already_Installed, installRoots[i].getId())));

			if (!result.isOK()) {
				return new ProvisioningPlan(result);
			}
			//Compute the complete closure of things to install to successfully install the installRoots.
			IInstallableUnit[] allUnits = gatherAvailableInstallableUnits(installRoots, metadataRepositories, sub.newChild(ExpandWork / 2));
			NewDependencyExpander expander = new NewDependencyExpander(installRoots, alreadyInstalled, allUnits, profile, true);
			IStatus expanderResult = expander.expand(sub.newChild(ExpandWork / 2));
			if (!expanderResult.isOK()) {
				result.merge(expanderResult);
				return new ProvisioningPlan(result);
			}

			ResolutionHelper oldStateHelper = new ResolutionHelper(profile.getSelectionContext(), null);
			Collection oldState = oldStateHelper.attachCUs(Arrays.asList(alreadyInstalled));
			List oldStateOrder = oldStateHelper.getSorted();

			ResolutionHelper newStateHelper = new ResolutionHelper(profile.getSelectionContext(), expander.getRecommendations());
			Collection newState = newStateHelper.attachCUs(expander.getAllInstallableUnits());
			List newStateOrder = newStateHelper.getSorted();
			return new ProvisioningPlan(Status.OK_STATUS, generateOperations(oldState, newState, oldStateOrder, newStateOrder));
		} finally {
			sub.done();
		}
	}

	private IInstallableUnit[] getInstallableUnits(Profile profile) {
		return (IInstallableUnit[]) profile.query(InstallableUnitQuery.ANY, new Collector(), null).toArray(IInstallableUnit.class);
	}

	private Operand[] generateOperations(Collection fromState, Collection toState, List fromStateOrder, List newStateOrder) {
		return sortOperations(new OperationGenerator().generateOperation(fromState, toState), newStateOrder, fromStateOrder);
	}

	private Operand[] sortOperations(Operand[] toSort, List installOrder, List uninstallOrder) {
		List updateOp = new ArrayList();
		for (int i = 0; i < toSort.length; i++) {
			Operand op = toSort[i];
			if (op.first() == null && op.second() != null) {
				installOrder.set(installOrder.indexOf(op.second()), op);
				continue;
			}
			if (op.first() != null && op.second() == null) {
				uninstallOrder.set(uninstallOrder.indexOf(op.first()), op);
				continue;
			}
			if (op.first() != null && op.second() != null) {
				updateOp.add(op);
				continue;
			}
		}
		int i = 0;
		for (Iterator iterator = installOrder.iterator(); iterator.hasNext();) {
			Object elt = iterator.next();
			if (elt instanceof Operand) {
				toSort[i++] = (Operand) elt;
			}
		}
		for (Iterator iterator = uninstallOrder.iterator(); iterator.hasNext();) {
			Object elt = iterator.next();
			if (elt instanceof Operand) {
				toSort[i++] = (Operand) elt;
			}
		}
		for (Iterator iterator = updateOp.iterator(); iterator.hasNext();) {
			Object elt = iterator.next();
			if (elt instanceof Operand) {
				toSort[i++] = (Operand) elt;
			}
		}
		return toSort;
	}

	public ProvisioningPlan getBecomePlan(IInstallableUnit target, Profile profile, URL[] metadataRepositories, IProgressMonitor monitor) {
		SubMonitor sub = SubMonitor.convert(monitor, ExpandWork);
		sub.setTaskName(Messages.Director_Task_Resolving_Dependencies);
		try {
			MultiStatus result = new MultiStatus(DirectorActivator.PI_DIRECTOR, 1, Messages.Director_Become_Problems, null);

			if (!Boolean.valueOf(target.getProperty(IInstallableUnit.PROP_PROFILE_IU_KEY)).booleanValue()) {
				result.add(new Status(IStatus.ERROR, DirectorActivator.PI_DIRECTOR, NLS.bind(Messages.Director_Unexpected_IU, target.getId())));
				return new ProvisioningPlan(result);
			}

			//TODO Here we need to deal with the change of properties between the two profiles
			//Also if the profile changes (locations are being modified, etc), should not we do a full uninstall then an install?
			//Maybe it depends on the kind of changes in a profile
			//We need to get all the ius that were part of the profile and give that to be what to become
			NewDependencyExpander toExpander = new NewDependencyExpander(new IInstallableUnit[] {target}, null, gatherAvailableInstallableUnits(new IInstallableUnit[] {target}, metadataRepositories, sub.newChild(ExpandWork / 2)), profile, true);
			toExpander.expand(sub.newChild(ExpandWork / 2));
			ResolutionHelper newStateHelper = new ResolutionHelper(profile.getSelectionContext(), toExpander.getRecommendations());
			Collection newState = newStateHelper.attachCUs(toExpander.getAllInstallableUnits());
			newState.remove(target);

			Iterator it = profile.query(InstallableUnitQuery.ANY, new Collector(), null).iterator();
			Collection oldIUs = new HashSet();
			for (; it.hasNext();) {
				oldIUs.add(it.next());
			}

			ResolutionHelper oldStateHelper = new ResolutionHelper(profile.getSelectionContext(), null);
			Collection oldState = oldStateHelper.attachCUs(oldIUs);
			return new ProvisioningPlan(Status.OK_STATUS, generateOperations(oldState, newState, oldStateHelper.getSorted(), newStateHelper.getSorted()));
		} finally {
			sub.done();
		}
	}

	private IInstallableUnit[] inProfile(IInstallableUnit[] toFind, Profile profile, boolean found, IProgressMonitor monitor) {
		ArrayList result = new ArrayList(toFind.length);
		for (int i = 0; i < toFind.length; i++) {
			Query query = new InstallableUnitQuery(toFind[i].getId(), new VersionRange(toFind[i].getVersion(), true, toFind[i].getVersion(), true));
			if (!profile.query(query, new HasMatchCollector(), monitor).isEmpty()) {
				if (found)
					result.add(toFind[i]);
			} else {
				if (!found)
					result.add(toFind[i]);
			}
		}
		return (IInstallableUnit[]) result.toArray(new IInstallableUnit[result.size()]);
	}

	public ProvisioningPlan getUninstallPlan(IInstallableUnit[] uninstallRoots, Profile profile, URL[] metadataRepositories, IProgressMonitor monitor) {
		SubMonitor sub = SubMonitor.convert(monitor, ExpandWork);
		sub.setTaskName(Messages.Director_Task_Resolving_Dependencies);
		try {
			IInstallableUnit[] toReallyUninstall = inProfile(uninstallRoots, profile, true, sub.newChild(0));
			if (toReallyUninstall.length == 0) {
				return new ProvisioningPlan(new Status(IStatus.OK, DirectorActivator.PI_DIRECTOR, Messages.Director_Nothing_To_Uninstall));
			} else if (toReallyUninstall.length != uninstallRoots.length) {
				uninstallRoots = toReallyUninstall;
			}

			MultiStatus result = new MultiStatus(DirectorActivator.PI_DIRECTOR, 1, Messages.Director_Uninstall_Problems, null);

			IInstallableUnit[] alreadyInstalled = getInstallableUnits(profile);
			ResolutionHelper oldStateHelper = new ResolutionHelper(profile.getSelectionContext(), null);
			Collection oldState = oldStateHelper.attachCUs(Arrays.asList(alreadyInstalled));

			NewDependencyExpander expander = new NewDependencyExpander(uninstallRoots, new IInstallableUnit[0], alreadyInstalled, profile, true);
			expander.expand(sub.newChild(ExpandWork / 3));
			Collection toUninstallClosure = new ResolutionHelper(profile.getSelectionContext(), null).attachCUs(expander.getAllInstallableUnits());

			Collection remainingIUs = new HashSet(oldState);
			remainingIUs.removeAll(toUninstallClosure);
			IInstallableUnit[] allUnits = gatherAvailableInstallableUnits(uninstallRoots, metadataRepositories, sub.newChild(ExpandWork / 3));
			NewDependencyExpander finalExpander = new NewDependencyExpander(null, (IInstallableUnit[]) remainingIUs.toArray(new IInstallableUnit[remainingIUs.size()]), allUnits, profile, true);
			finalExpander.expand(sub.newChild(ExpandWork / 3));
			ResolutionHelper newStateHelper = new ResolutionHelper(profile.getSelectionContext(), null);
			Collection newState = newStateHelper.attachCUs(finalExpander.getAllInstallableUnits());

			for (int i = 0; i < uninstallRoots.length; i++) {
				if (newState.contains(uninstallRoots[i]))
					result.add(new Status(IStatus.ERROR, DirectorActivator.PI_DIRECTOR, NLS.bind(Messages.Director_Cannot_Uninstall, uninstallRoots[i])));
			}
			if (!result.isOK())
				return new ProvisioningPlan(result);

			return new ProvisioningPlan(Status.OK_STATUS, generateOperations(oldState, newState, oldStateHelper.getSorted(), newStateHelper.getSorted()));
		} finally {
			sub.done();
		}
	}

	protected IInstallableUnit[] gatherAvailableInstallableUnits(IInstallableUnit[] additionalSource, URL[] repositories, IProgressMonitor monitor) {
		Map resultsMap = new HashMap();
		if (additionalSource != null) {
			for (int i = 0; i < additionalSource.length; i++) {
				String key = additionalSource[i].getId() + "_" + additionalSource[i].getVersion().toString(); //$NON-NLS-1$
				resultsMap.put(key, additionalSource[i]);
			}
		}

		IMetadataRepositoryManager repoMgr = (IMetadataRepositoryManager) ServiceHelper.getService(DirectorActivator.context, IMetadataRepositoryManager.class.getName());
		if (repositories == null)
			repositories = repoMgr.getKnownRepositories(IMetadataRepositoryManager.REPOSITORIES_ALL);

		SubMonitor sub = SubMonitor.convert(monitor, repositories.length * 200);
		for (int i = 0; i < repositories.length; i++) {
			try {
				IMetadataRepository repository = repoMgr.loadRepository(repositories[i], sub.newChild(100));
				Collector matches = repository.query(new InstallableUnitQuery(null, VersionRange.emptyRange), new Collector(), sub.newChild(100));
				for (Iterator it = matches.iterator(); it.hasNext();) {
					IInstallableUnit iu = (IInstallableUnit) it.next();
					String key = iu.getId() + "_" + iu.getVersion().toString(); //$NON-NLS-1$
					IInstallableUnit currentIU = (IInstallableUnit) resultsMap.get(key);
					if (currentIU == null || hasHigherFidelity(iu, currentIU))
						resultsMap.put(key, iu);
				}
			} catch (ProvisionException e) {
				//skip unreadable repositories
			}
		}
		sub.done();
		Collection results = resultsMap.values();
		return (IInstallableUnit[]) results.toArray(new IInstallableUnit[results.size()]);
	}

	private boolean hasHigherFidelity(IInstallableUnit iu, IInstallableUnit currentIU) {
		if (new Boolean(currentIU.getProperty("iu.mock")).booleanValue() && !new Boolean(iu.getProperty("iu.mock")).booleanValue())
			return true;

		return false;
	}

	public ProvisioningPlan getReplacePlan(IInstallableUnit[] toUninstall, IInstallableUnit[] toInstall, Profile profile, URL[] metadataRepositories, IProgressMonitor monitor) {
		SubMonitor sub = SubMonitor.convert(monitor, ExpandWork);
		sub.setTaskName(Messages.Director_Task_Resolving_Dependencies);
		try {
			//find the things being updated in the profile
			IInstallableUnit[] alreadyInstalled = getInstallableUnits(profile);
			IInstallableUnit[] uninstallRoots = toUninstall;

			//compute the transitive closure and remove them.
			ResolutionHelper oldStateHelper = new ResolutionHelper(profile.getSelectionContext(), null);
			Collection oldState = oldStateHelper.attachCUs(Arrays.asList(alreadyInstalled));

			NewDependencyExpander expander = new NewDependencyExpander(uninstallRoots, new IInstallableUnit[0], alreadyInstalled, profile, true);
			expander.expand(sub.newChild(ExpandWork / 3));
			Collection toUninstallClosure = new ResolutionHelper(profile.getSelectionContext(), null).attachCUs(expander.getAllInstallableUnits());

			//add the new set.
			Collection remainingIUs = new HashSet(oldState);
			remainingIUs.removeAll(toUninstallClosure);
			//		for (int i = 0; i < updateRoots.length; i++) {
			//			remainingIUs.add(updateRoots[i]);
			//		}
			IInstallableUnit[] allUnits = gatherAvailableInstallableUnits(null, metadataRepositories, sub.newChild(ExpandWork / 3));
			NewDependencyExpander finalExpander = new NewDependencyExpander(toInstall, (IInstallableUnit[]) remainingIUs.toArray(new IInstallableUnit[remainingIUs.size()]), allUnits, profile, true);
			finalExpander.expand(sub.newChild(ExpandWork / 3));
			ResolutionHelper newStateHelper = new ResolutionHelper(profile.getSelectionContext(), null);
			Collection newState = newStateHelper.attachCUs(finalExpander.getAllInstallableUnits());

			return new ProvisioningPlan(Status.OK_STATUS, generateOperations(oldState, newState, oldStateHelper.getSorted(), newStateHelper.getSorted()));
		} finally {
			sub.done();
		}
	}

	public IInstallableUnit[] updatesFor(IInstallableUnit toUpdate, URL[] repositories, IProgressMonitor monitor) {
		Map resultsMap = new HashMap();

		IMetadataRepositoryManager repoMgr = (IMetadataRepositoryManager) ServiceHelper.getService(DirectorActivator.context, IMetadataRepositoryManager.class.getName());
		if (repositories == null)
			repositories = repoMgr.getKnownRepositories(IMetadataRepositoryManager.REPOSITORIES_ALL);

		SubMonitor sub = SubMonitor.convert(monitor, repositories.length * 200);
		for (int i = 0; i < repositories.length; i++) {
			try {
				IMetadataRepository repository = repoMgr.loadRepository(repositories[i], sub.newChild(100));
				Collector matches = repository.query(new UpdateQuery(toUpdate), new Collector(), sub.newChild(100));
				for (Iterator it = matches.iterator(); it.hasNext();) {
					IInstallableUnit iu = (IInstallableUnit) it.next();
					String key = iu.getId() + "_" + iu.getVersion().toString(); //$NON-NLS-1$
					IInstallableUnit currentIU = (IInstallableUnit) resultsMap.get(key);
					if (currentIU == null || hasHigherFidelity(iu, currentIU))
						resultsMap.put(key, iu);
				}
			} catch (ProvisionException e) {
				//skip unreadable repositories
			}
		}
		sub.done();
		Collection results = resultsMap.values();
		return (IInstallableUnit[]) results.toArray(new IInstallableUnit[results.size()]);
	}

	public ProvisioningPlan getRevertPlan(IInstallableUnit previous, Profile profile, URL[] metadataRepositories, IProgressMonitor monitor) {
		return getBecomePlan(previous, profile, metadataRepositories, monitor);
	}
}
