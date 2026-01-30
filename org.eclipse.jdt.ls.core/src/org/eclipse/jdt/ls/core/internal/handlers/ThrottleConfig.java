/*******************************************************************************
 * Copyright (c) 2026 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

/**
 * Configuration for throttling partial results streaming.
 * Controls how frequently partial results are sent to the client.
 */
public class ThrottleConfig {

	/**
	 * Number of results to send immediately before switching to batched mode.
	 * Default: 5
	 */
	private final int immediateCount;

	/**
	 * Number of results to batch before sending in batched mode.
	 * Default: 10
	 */
	private final int batchSize;

	/**
	 * Maximum time interval (in milliseconds) between sends in batched mode.
	 * Default: 100ms
	 */
	private final long batchIntervalMs;

	/**
	 * Creates a ThrottleConfig with default values:
	 * - immediateCount: 5
	 * - batchSize: 10
	 * - batchIntervalMs: 100
	 */
	public ThrottleConfig() {
		this(5, 10, 100);
	}

	/**
	 * Creates a ThrottleConfig with specified values.
	 *
	 * @param immediateCount number of results to send immediately
	 * @param batchSize number of results to batch before sending
	 * @param batchIntervalMs maximum time interval between sends (milliseconds)
	 */
	public ThrottleConfig(int immediateCount, int batchSize, long batchIntervalMs) {
		this.immediateCount = immediateCount;
		this.batchSize = batchSize;
		this.batchIntervalMs = batchIntervalMs;
	}

	public int getImmediateCount() {
		return immediateCount;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public long getBatchIntervalMs() {
		return batchIntervalMs;
	}
}
