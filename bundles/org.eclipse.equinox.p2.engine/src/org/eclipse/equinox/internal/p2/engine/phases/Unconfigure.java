/*******************************************************************************
 *  Copyright (c) 2007, 2012 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *     Wind River - ongoing development
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.engine.phases;

import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.internal.p2.engine.*;
import org.eclipse.equinox.internal.provisional.p2.core.eventbus.IProvisioningEventBus;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.PhaseSetFactory;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;
import org.eclipse.equinox.p2.engine.spi.Touchpoint;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;

public class Unconfigure extends InstallableUnitPhase {

	final static class BeforeUnConfigureEventAction extends ProvisioningAction {

		public IStatus execute(Map<String, Object> parameters) {
			IProfile profile = (IProfile) parameters.get(PARM_PROFILE);
			String phaseId = (String) parameters.get(PARM_PHASE_ID);
			IInstallableUnit iu = (IInstallableUnit) parameters.get(PARM_IU);
			IProvisioningAgent agent = (IProvisioningAgent) parameters.get(PARM_AGENT);
			((IProvisioningEventBus) agent.getService(IProvisioningEventBus.SERVICE_NAME)).publishEvent(new InstallableUnitEvent(phaseId, true, profile, iu, InstallableUnitEvent.UNCONFIGURE, getTouchpoint()));
			return null;
		}

		public IStatus undo(Map<String, Object> parameters) {
			Profile profile = (Profile) parameters.get(PARM_PROFILE);
			String phaseId = (String) parameters.get(PARM_PHASE_ID);
			IInstallableUnit iu = (IInstallableUnit) parameters.get(PARM_IU);
			IProvisioningAgent agent = (IProvisioningAgent) parameters.get(PARM_AGENT);
			((IProvisioningEventBus) agent.getService(IProvisioningEventBus.SERVICE_NAME)).publishEvent(new InstallableUnitEvent(phaseId, false, profile, iu, InstallableUnitEvent.CONFIGURE, getTouchpoint()));
			return null;
		}
	}

	final static class AfterUnConfigureEventAction extends ProvisioningAction {

		public IStatus execute(Map<String, Object> parameters) {
			Profile profile = (Profile) parameters.get(PARM_PROFILE);
			String phaseId = (String) parameters.get(PARM_PHASE_ID);
			IInstallableUnit iu = (IInstallableUnit) parameters.get(PARM_IU);
			IProvisioningAgent agent = (IProvisioningAgent) parameters.get(PARM_AGENT);
			((IProvisioningEventBus) agent.getService(IProvisioningEventBus.SERVICE_NAME)).publishEvent(new InstallableUnitEvent(phaseId, false, profile, iu, InstallableUnitEvent.UNCONFIGURE, getTouchpoint()));
			return null;
		}

		public IStatus undo(Map<String, Object> parameters) {
			IProfile profile = (IProfile) parameters.get(PARM_PROFILE);
			String phaseId = (String) parameters.get(PARM_PHASE_ID);
			IInstallableUnit iu = (IInstallableUnit) parameters.get(PARM_IU);
			IProvisioningAgent agent = (IProvisioningAgent) parameters.get(PARM_AGENT);
			((IProvisioningEventBus) agent.getService(IProvisioningEventBus.SERVICE_NAME)).publishEvent(new InstallableUnitEvent(phaseId, true, profile, iu, InstallableUnitEvent.CONFIGURE, getTouchpoint()));
			return null;
		}
	}

	public Unconfigure(int weight, boolean forced) {
		super(PhaseSetFactory.PHASE_UNCONFIGURE, weight, forced);
	}

	public Unconfigure(int weight) {
		this(weight, false);
	}

	protected boolean isApplicable(InstallableUnitOperand op) {
		return (op.first() != null);
	}

	protected List<ProvisioningAction> getActions(InstallableUnitOperand currentOperand) {
		//TODO: monitor.subTask(NLS.bind(Messages.Engine_Unconfiguring_IU, unit.getId()));

		IInstallableUnit unit = currentOperand.first();

		ProvisioningAction beforeAction = new BeforeUnConfigureEventAction();
		ProvisioningAction afterAction = new AfterUnConfigureEventAction();
		Touchpoint touchpoint = getActionManager().getTouchpointPoint(unit.getTouchpointType());
		if (touchpoint != null) {
			beforeAction.setTouchpoint(touchpoint);
			afterAction.setTouchpoint(touchpoint);
		}
		ArrayList<ProvisioningAction> actions = new ArrayList<ProvisioningAction>();
		actions.add(beforeAction);
		if (!QueryUtil.isFragment(unit)) {
			List<ProvisioningAction> parsedActions = getActions(unit, phaseId);
			if (parsedActions != null)
				actions.addAll(parsedActions);
		}
		actions.add(afterAction);
		return actions;
	}

	protected String getProblemMessage() {
		return Messages.Phase_Unconfigure_Error;
	}

	protected IStatus initializeOperand(IProfile profile, InstallableUnitOperand operand, Map<String, Object> parameters, IProgressMonitor monitor) {
		IInstallableUnit iu = operand.first();
		parameters.put(PARM_IU, iu);

		Collection<IArtifactKey> artifacts = iu.getArtifacts();
		if (artifacts != null && artifacts.size() > 0)
			parameters.put(PARM_ARTIFACT, artifacts.iterator().next());

		return Status.OK_STATUS;
	}
}