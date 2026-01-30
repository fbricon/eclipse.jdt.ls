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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection.JavaLanguageClient;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Generic utility class for streaming partial results to LSP clients.
 * Supports throttling to avoid overwhelming the client with too many notifications.
 *
 * @param <T> the type of result being streamed (e.g., Location, SymbolInformation)
 */
public class PartialResultsReporter<T> {

	private final Either<String, Integer> partialResultToken;
	private final JavaLanguageClient client;
	private final IProgressMonitor monitor;
	private final ThrottleConfig throttleConfig;
	private final List<T> results = new ArrayList<>();

	private int immediateCountSent = 0;
	private int batchedCount = 0;
	private long lastSendTime = 0;

	/**
	 * Creates a PartialResultsReporter.
	 *
	 * @param partialResultToken the token provided by the client for partial results, or null to disable streaming
	 * @param client the language client to send notifications to
	 * @param monitor the progress monitor for cancellation checking
	 * @param throttleConfig the throttling configuration
	 */
	public PartialResultsReporter(Either<String, Integer> partialResultToken, JavaLanguageClient client, IProgressMonitor monitor, ThrottleConfig throttleConfig) {
		this.partialResultToken = partialResultToken;
		this.client = client;
		this.monitor = monitor;
		this.throttleConfig = throttleConfig != null ? throttleConfig : new ThrottleConfig();
	}

	/**
	 * Creates a PartialResultsReporter with default throttling configuration.
	 *
	 * @param partialResultToken the token provided by the client for partial results, or null to disable streaming
	 * @param client the language client to send notifications to
	 * @param monitor the progress monitor for cancellation checking
	 */
	public PartialResultsReporter(Either<String, Integer> partialResultToken, JavaLanguageClient client, IProgressMonitor monitor) {
		this(partialResultToken, client, monitor, new ThrottleConfig());
	}

	/**
	 * Adds a result to the collection and sends partial results if throttling conditions are met.
	 * If partialResultToken is null, this acts as a simple collector without streaming.
	 *
	 * @param result the result to add
	 */
	public void addResult(T result) {
		if (monitor != null && monitor.isCanceled()) {
			return;
		}

		results.add(result);

		// If no token provided, just collect results without streaming
		if (partialResultToken == null || client == null) {
			return;
		}

		// Immediate mode: send first N results right away
		if (immediateCountSent < throttleConfig.getImmediateCount()) {
			sendProgress();
			immediateCountSent++;
			return;
		}

		// Batched mode: check if we should send
		batchedCount++;
		long now = System.currentTimeMillis();
		boolean shouldSend = batchedCount >= throttleConfig.getBatchSize()
				|| (lastSendTime > 0 && (now - lastSendTime) >= throttleConfig.getBatchIntervalMs());

		if (shouldSend) {
			sendProgress();
			batchedCount = 0;
			lastSendTime = now;
		}
	}

	/**
	 * Forces sending the current accumulated results immediately.
	 * Useful to ensure final results are sent even if throttling would delay them.
	 */
	public void flush() {
		if (partialResultToken != null && client != null) {
			sendProgress();
		}
	}

	/**
	 * Sends an empty progress notification to signal that streaming is complete.
	 * This should be called after flush() when results were not empty, per LSP spec.
	 * The empty notification signals completion and ensures the client knows no more results are coming.
	 */
	public void sendEmptyCompletion() {
		if (partialResultToken == null || client == null) {
			return;
		}
		
		if (monitor != null && monitor.isCanceled()) {
			return;
		}

		try {
			// Send empty list to signal completion
			ProgressParams params = new ProgressParams(partialResultToken, Either.forRight((Object) new ArrayList<>()));
			client.notifyProgress(params);
		} catch (Exception e) {
			// Log but don't fail - partial results are optional
			JavaLanguageServerPlugin.logException("Failed to send completion signal", e);
		}
	}

	/**
	 * Gets all accumulated results.
	 *
	 * @return a copy of the accumulated results list
	 */
	public List<T> getResults() {
		return new ArrayList<>(results);
	}

	/**
	 * Sends the current results as a progress notification to the client.
	 * 
	 * Per LSP spec, each partial result notification contains the FULL accumulated results
	 * so far (not just new items). The client replaces/merges the previous state with
	 * each notification. This ensures the client always has a complete, consistent view
	 * of the results.
	 */
	private void sendProgress() {
		if (monitor != null && monitor.isCanceled()) {
			return;
		}

		try {
			// For partial results, use Either.forRight() with the result list as Object
			// ProgressParams expects Either<WorkDoneProgressNotification, Object>
			// Note: We send the full accumulated results list each time, per LSP spec
			List<T> resultsCopy = new ArrayList<>(results);
			ProgressParams params = new ProgressParams(partialResultToken, Either.forRight((Object) resultsCopy));
			client.notifyProgress(params);
		} catch (Exception e) {
			// Log but don't fail - partial results are optional
			JavaLanguageServerPlugin.logException("Failed to send partial results", e);
		}
	}
}
