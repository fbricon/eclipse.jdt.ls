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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.Assert;

/**
 * Telemetry event emitted towards clients
 *
 * @author Fred Bricon
 *
 */
public class TelemetryEvent {

	private String name;
	private long timestamp = System.currentTimeMillis();
	private Map<Object, Object> properties;
	private Map<Object, Number> measures;

	/**
	 * Creates a new {@link TelemetryEvent} instance with the given name.
	 *
	 * @param name the event name
	 */
	TelemetryEvent(String name) {
		super();
		this.name = name;
	}

	/**
	 * @return the event name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return an unmodifiable copy of the event properties. May be <code>null</code>.
	 */
	public Map<Object, Object> getProperties() {
		return properties == null? null : Collections.unmodifiableMap(properties);
	}

	/**
	 * Sets an event property with the given value
	 */
	public TelemetryEvent setProperty(Object key, Object value) {
		Assert.isNotNull(key, "Key can not be null!");
		if (properties == null) {
			properties = new LinkedHashMap<>();
		}
		properties.put(key, value);
		return this;
	}

	/**
	 * Sets an event measure with the given value
	 */
	public TelemetryEvent setMeasure(Object key, Number value) {
		Assert.isNotNull(key, "Key can not be null!");
		if (measures == null) {
			measures = new LinkedHashMap<>();
		}
		measures.put(key, value);
		return this;
	}

	/**
	 * @return an unmodifiable copy of the event measures. May be <code>null</code>.
	 */
	public Map<Object, Object> getMeasures() {
		return measures == null? null : Collections.unmodifiableMap(measures);
	}

	public long getTimestamp() {
		return timestamp;
	}
}
