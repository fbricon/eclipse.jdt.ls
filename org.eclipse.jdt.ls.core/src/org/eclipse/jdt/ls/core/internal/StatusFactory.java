/*******************************************************************************
 * Copyright (c) 2016 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

public class StatusFactory {

	public static final IStatus UNSUPPORTED_PROJECT = newErrorStatus("Unsupported Java project");
	
	private StatusFactory() {}
	
	public static IStatus newErrorStatus(String message) {
		return newErrorStatus(message, null);
	}
	
	public static IStatus newErrorStatus(String message, Throwable exception) {
		return new Status(IStatus.ERROR, JavaLanguageServerPlugin.PLUGIN_ID, message, exception);
	}
}
