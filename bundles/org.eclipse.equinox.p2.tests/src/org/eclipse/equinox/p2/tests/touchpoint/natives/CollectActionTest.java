/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 ******************************************************************************/
package org.eclipse.equinox.p2.tests.touchpoint.natives;

import java.io.File;
import java.util.*;
import org.eclipse.equinox.internal.p2.touchpoint.natives.NativeTouchpoint;
import org.eclipse.equinox.internal.p2.touchpoint.natives.actions.ActionConstants;
import org.eclipse.equinox.internal.p2.touchpoint.natives.actions.CollectAction;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.IArtifactRequest;
import org.eclipse.equinox.internal.provisional.p2.engine.*;
import org.eclipse.equinox.internal.provisional.p2.metadata.*;
import org.eclipse.equinox.internal.provisional.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.tests.AbstractProvisioningTest;
import org.eclipse.equinox.spi.p2.publisher.PublisherHelper;

public class CollectActionTest extends AbstractProvisioningTest {

	public CollectActionTest(String name) {
		super(name);
	}

	public CollectActionTest() {
		super("");
	}

	public void testExecuteUndo() {
		Properties profileProperties = new Properties();
		File installFolder = getTempFolder();
		profileProperties.setProperty(IProfile.PROP_INSTALL_FOLDER, installFolder.toString());
		IProfile profile = createProfile("test", null, profileProperties);

		//		File zipSource = getTestData("1.0", "/testData/nativeTouchpoint/a.zip");
		//		File zipTarget = new File(installFolder, "a.zip");
		//		copy("2.0", zipSource, zipTarget);

		InstallableUnitDescription iuDesc = new MetadataFactory.InstallableUnitDescription();
		iuDesc.setId("test");
		iuDesc.setVersion(DEFAULT_VERSION);
		IArtifactKey key = PublisherHelper.createBinaryArtifactKey("test", DEFAULT_VERSION);
		iuDesc.setArtifacts(new IArtifactKey[] {key});
		iuDesc.setTouchpointType(PublisherHelper.TOUCHPOINT_NATIVE);
		IInstallableUnit iu = MetadataFactory.createInstallableUnit(iuDesc);

		Map parameters = new HashMap();
		parameters.put(ActionConstants.PARM_PROFILE, profile);
		parameters.put(InstallableUnitPhase.PARM_ARTIFACT_REQUESTS, new ArrayList());
		NativeTouchpoint touchpoint = new NativeTouchpoint();
		touchpoint.initializePhase(null, profile, "test", parameters);
		InstallableUnitOperand operand = new InstallableUnitOperand(null, iu);
		parameters.put("iu", operand.second());
		touchpoint.initializeOperand(profile, operand, parameters);
		parameters.put(ActionConstants.PARM_OPERAND, operand);
		parameters = Collections.unmodifiableMap(parameters);

		List requests = (List) parameters.get(InstallableUnitPhase.PARM_ARTIFACT_REQUESTS);
		assertFalse(hasRequest(requests, key));
		CollectAction action = new CollectAction();
		action.execute(parameters);
		assertTrue(hasRequest(requests, key));
		// does nothing so should not alter parameters
		action.undo(parameters);
		assertTrue(hasRequest(requests, key));
	}

	private boolean hasRequest(List requests, IArtifactKey key) {
		for (Iterator iterator = requests.iterator(); iterator.hasNext();) {
			IArtifactRequest[] request = (IArtifactRequest[]) iterator.next();
			for (int i = 0; i < request.length; i++) {
				if (key.equals(request[i].getArtifactKey()))
					return true;
			}
		}
		return false;
	}
}