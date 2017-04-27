/*******************************************************************************
 * Copyright (c) 2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.telemetry;

/**
 * A factory for creating {@link TelemetryEvent}s
 *
 * @author Fred Bricon
 */
public class TelemetryEventFactory {

	private TelemetryEventFactory() {
		//No public instanciation
	}

	public static TelemetryEvent createEvent(String name, Object key, Object value) {
		return new TelemetryEvent(name).setProperty(key, value);
	}

	public static TelemetryEvent createEvent(String name, Object key, Number value) {
		return new TelemetryEvent(name).setMeasure(key, value);
	}

	public static TelemetryEvent createStartupEvent() {
		TelemetryEvent event = createEvent("jdt.ls.startup", "start", "1");
		addSysProp(event, "java.version");
		addSysProp(event, "java.vm.name");
		addSysProp(event, "java.vm.vendor");
		addSysProp(event, "java.runtime.version");
		event.setProperty("java.memory.max", Runtime.getRuntime().maxMemory());
		return event;
	}

	private static void addSysProp(TelemetryEvent event, String name) {
		event.setProperty(name, System.getProperty(name, "unknown"));
	}

}
