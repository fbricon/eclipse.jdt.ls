/*******************************************************************************
 * Copyright (c) 2016-2023 Red Hat Inc. and others.
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
package org.eclipse.jdt.ls.core.internal.contentassist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.corext.template.java.SignatureUtil;
import org.eclipse.jdt.ls.core.contentassist.CompletionRanking;
import org.eclipse.jdt.ls.core.contentassist.ICompletionRankingProvider;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionContributionService;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionMatchCaseMode;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionRankingAggregation;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionResolveHandler;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionResponse;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionResponses;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemDefaults;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.InsertReplaceRange;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.google.common.collect.ImmutableSet;

public final class CompletionProposalRequestor extends CompletionRequestor {

	private List<CompletionProposal> proposals = new ArrayList<>();
	private final ICompilationUnit unit;
	private final String uri; // URI of this.unit, used in future "resolve" requests
	private CompletionProposalDescriptionProvider descriptionProvider;
	private CompletionResponse response;
	private boolean fIsTestCodeExcluded;
	private CompletionContext context;
	private boolean isComplete = true;
	private PreferenceManager preferenceManager;
	private CompletionProposalReplacementProvider proposalProvider;
	private CompletionItemDefaults itemDefaults;

	static class ProposalComparator implements Comparator<CompletionProposal> {

		private Map<CompletionProposal, char[]> completionCache;

		ProposalComparator(int cacheSize) {
			completionCache = new HashMap<>(cacheSize + 1, 1f);//avoid resizing the cache
		}

		@Override
		public int compare(CompletionProposal p1, CompletionProposal p2) {
			int res = p2.getRelevance() - p1.getRelevance();
			if (res == 0) {
				res = p1.getKind() - p2.getKind();
			}
			if (res == 0) {
				char[] completion1 = getCompletion(p1);
				char[] completion2 = getCompletion(p2);

				int p1Length = completion1.length;
				int p2Length = completion2.length;
				for (int i = 0; i < p1Length; i++) {
					if (i >= p2Length) {
						return -1;
					}
					res = Character.compare(completion1[i], completion2[i]);
					if (res != 0) {
						return res;
					}
				}
				res = p2Length - p1Length;
			}
			return res;
		}

		private char[] getCompletion(CompletionProposal cp) {
			// Implementation of CompletionProposal#getCompletion() can be non-trivial,
			// so we cache the results to speed things up
			return completionCache.computeIfAbsent(cp, p -> p.getCompletion());
		}

	};

	public boolean isComplete() {
		return isComplete;
	}

	public CompletionItemDefaults getCompletionItemDefaults() {
		if (itemDefaults == null) {
			itemDefaults = new CompletionItemDefaults();
		}
		return itemDefaults;
	}
	// Update SUPPORTED_KINDS when mapKind changes
	// @formatter:off
	public static final Set<CompletionItemKind> SUPPORTED_KINDS = ImmutableSet.of(CompletionItemKind.Constructor,
																				CompletionItemKind.Class,
																				CompletionItemKind.Constant,
																				CompletionItemKind.Interface,
																				CompletionItemKind.Enum,
																				CompletionItemKind.EnumMember,
																				CompletionItemKind.Module,
																				CompletionItemKind.Field,
																				CompletionItemKind.Keyword,
																				CompletionItemKind.Reference,
																				CompletionItemKind.Variable,
																				CompletionItemKind.Method,
																				CompletionItemKind.Text,
																				CompletionItemKind.Snippet);
	// @formatter:on

	/**
	 * @deprecated use
	 *             {@link CompletionProposalRequestor#CompletionProposalRequestor(ICompilationUnit, int, PreferenceManager)}
	 */
	@Deprecated
	public CompletionProposalRequestor(ICompilationUnit aUnit, int offset) {
		this(aUnit, offset, JavaLanguageServerPlugin.getPreferencesManager());
	}

	public CompletionProposalRequestor(ICompilationUnit aUnit, int offset, PreferenceManager preferenceManager) {
		this.unit = aUnit;
		this.uri = JDTUtils.toURI(aUnit);
		this.preferenceManager = preferenceManager;
		response = new CompletionResponse();
		response.setOffset(offset);
		fIsTestCodeExcluded = !isTestSource(unit.getJavaProject(), unit);
		setRequireExtendedContext(true);
		try {
			List<String> importedElements = Arrays.stream(this.unit.getImports())
					.map(t -> t.getElementName())
					.toList();
			TypeFilter.getDefault().removeFilterIfMatched(importedElements);
		} catch (JavaModelException e) {
			JavaLanguageServerPlugin.logException("Failed to get imports during completion", e);
		}
	}

	private boolean isTestSource(IJavaProject project, ICompilationUnit cu) {
		if (project == null) {
			return true;
		}
		try {
			IClasspathEntry[] resolvedClasspath = project.getResolvedClasspath(true);
			final IPath resourcePath = cu.getResource().getFullPath();
			for (IClasspathEntry e : resolvedClasspath) {
				if (e.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					if (e.isTest()) {
						if (e.getPath().isPrefixOf(resourcePath)) {
							return true;
						}
					}
				}
			}
		} catch (JavaModelException e) {
		}
		return false;
	}

	@Override
	public void accept(CompletionProposal proposal) {
		if (isFiltered(proposal)) {
			return;
		}

		if (isIgnored(proposal.getKind())) {
			return;
		}

		if (!matchCase(proposal)) {
			return;
		}

		if (proposal.getKind() == CompletionProposal.POTENTIAL_METHOD_DECLARATION) {
			acceptPotentialMethodDeclaration(proposal);
		} else {
			if (proposal.getKind() == CompletionProposal.PACKAGE_REF && unit.getParent() != null && String.valueOf(proposal.getCompletion()).equals(unit.getParent().getElementName())) {
				// Hacky way to boost relevance of current package, for package completions, until
				// https://bugs.eclipse.org/518140 is fixed
				proposal.setRelevance(proposal.getRelevance() + 1);
			}
			proposals.add(proposal);
		}
	}

	public List<CompletionItem> getCompletionItems() {
		return getCompletionItems(new NullProgressMonitor());
	}

	public List<CompletionItem> getCompletionItems(IProgressMonitor monitor) {
		CompletionRankingAggregation[] aggregatedRanks = getAggregatedRankingResult(monitor);
		for (int i = 0; i < proposals.size(); i++) {
			CompletionProposal proposal = proposals.get(i);
			if (aggregatedRanks[i] != null) {
				// we assume there won't be overflow for now since the the score from
				// each provider can only be 100 at most.
				proposal.setRelevance(proposal.getRelevance() + aggregatedRanks[i].getScore());
			}
		}
		Map<CompletionProposal, CompletionRankingAggregation> proposalToRankingResult = new HashMap<>();
		for (int i = 0; i < proposals.size(); i++) {
			proposalToRankingResult.put(proposals.get(i), aggregatedRanks[i]);
		}

		proposals.sort(new ProposalComparator(proposals.size()));
		int maxCompletions = preferenceManager.getPreferences().getMaxCompletionResults();
		int limit = Math.min(proposals.size(), maxCompletions);
		List<CompletionItem> completionItems = new ArrayList<>(limit);

		if (!proposals.isEmpty()){
			initializeCompletionListItemDefaults(proposals.get(0));
		}
		//Let's compute replacement texts for the most relevant results only
		for (int i = 0; i < limit; i++) {
			CompletionProposal proposal = proposals.get(i);
			try {
				CompletionItem item = toCompletionItem(proposal, i);
				CompletionRankingAggregation rankingResult = proposalToRankingResult.get(proposal);
				if (rankingResult != null) {
					String decorators = rankingResult.getDecorators();
					if (!decorators.isEmpty()) {
						item.setLabel(decorators + " " + item.getLabel());
						// when the item has decorators, set the filter text to avoid client takes the
						// decorators into consideration when filtering.
						if (StringUtils.isEmpty(item.getFilterText())) {
							item.setFilterText(item.getInsertText());
						}
					}
					Map<String, String> itemData = (Map<String, String>) item.getData();
					Map<String, String> rankingData = rankingResult.getData();
					for (String key : rankingData.keySet()) {
						itemData.put(key, rankingData.get(key));
					}
				}
				completionItems.add(item);
			} catch (Exception e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
		}

		if (proposals.size() > maxCompletions) {
			//we keep receiving completions past our capacity so that makes the whole result incomplete
			isComplete = false;
			response.setProposals(proposals.subList(0, limit));
		} else {
			response.setProposals(proposals);
		}
		response.setItems(completionItems);
		response.setCommonData(CompletionResolveHandler.DATA_FIELD_URI, uri);
		CompletionResponses.store(response);

		return completionItems;
	}

	private CompletionRankingAggregation[] getAggregatedRankingResult(IProgressMonitor monitor) {
		List<ICompletionRankingProvider> providers =
				((CompletionContributionService) JavaLanguageServerPlugin.getCompletionContributionService()).getRankingProviders();
		CompletionRankingAggregation[] resultCombination = new CompletionRankingAggregation[this.proposals.size()];
		if (providers != null && !providers.isEmpty()) {
			for (ICompletionRankingProvider provider : providers) {
				CompletionRanking[] results = provider.rank(proposals, context, unit, monitor);
				if (results == null || results.length != proposals.size()) {
					continue;
				}

				for (int i = 0; i < results.length; i++) {
					if (results[i] == null) {
						continue;
					}
					if (resultCombination[i] == null) {
						resultCombination[i] = new CompletionRankingAggregation();
					}
					resultCombination[i].addScore(results[i].getScore());
					resultCombination[i].addDecorator(results[i].getDecorator());
					resultCombination[i].addData(results[i].getData());
				}
			}
		}
		return resultCombination;
	}

	private void initializeCompletionListItemDefaults(CompletionProposal proposal) {
		CompletionItem completionItem = new CompletionItem();
		CompletionItemDefaults itemDefaults = getCompletionItemDefaults();
		proposalProvider.updateReplacement(proposal, completionItem, '\0');
		if (completionItem.getInsertTextFormat() != null && preferenceManager.getClientPreferences().isCompletionListItemDefaultsInsertTextFormatSupport()) {
			itemDefaults.setInsertTextFormat(completionItem.getInsertTextFormat());
		}
		if (completionItem.getTextEdit() != null && preferenceManager.getClientPreferences().isCompletionListItemDefaultsEditRangeSupport()) {
			itemDefaults.setEditRange(getEditRange(completionItem, preferenceManager));
		}
	}


	public CompletionItem toCompletionItem(CompletionProposal proposal, int index) {
		final CompletionItem $ = new CompletionItem();
		CompletionItemDefaults itemDefaults = getCompletionItemDefaults();
		$.setKind(mapKind(proposal));
		if (Flags.isDeprecated(proposal.getFlags())) {
			if (preferenceManager.getClientPreferences().isCompletionItemTagSupported()) {
				$.setTags(List.of(CompletionItemTag.Deprecated));
			}
			else {
				$.setDeprecated(true);
			}
		}
		Map<String, String> data = new HashMap<>();
		// append data field so that resolve request can use it.
		data.put(CompletionResolveHandler.DATA_FIELD_REQUEST_ID, String.valueOf(response.getId()));
		data.put(CompletionResolveHandler.DATA_FIELD_PROPOSAL_ID, String.valueOf(index));
		$.setData(data);
		this.descriptionProvider.updateDescription(proposal, $);
		$.setSortText(SortTextHelper.computeSortText(proposal));
		proposalProvider.updateReplacement(proposal, $, '\0');
		// Make sure `filterText` matches `textEdit`
		// See https://github.com/eclipse/eclipse.jdt.ls/issues/1348
		if ($.getTextEdit() != null) {
			String newText = $.getTextEdit().isLeft() ? $.getTextEdit().getLeft().getNewText() : $.getTextEdit().getRight().getNewText();
			Range range = $.getTextEdit().isLeft() ? $.getTextEdit().getLeft().getRange() : ($.getTextEdit().getRight().getInsert() != null ? $.getTextEdit().getRight().getInsert() : $.getTextEdit().getRight().getReplace());
			if (proposal.getKind() == CompletionProposal.TYPE_REF && range != null && newText != null) {
				$.setFilterText(newText);
			}
			// See https://github.com/eclipse/eclipse.jdt.ls/issues/2387
			Range replace;
			if (preferenceManager.getClientPreferences().isCompletionInsertReplaceSupport()) {
				replace = $.getTextEdit().isRight() ? $.getTextEdit().getRight().getReplace() : null;
			} else {
				replace = range;
			}
			if (replace != null && replace.getEnd().getLine() != replace.getStart().getLine()) {
				replace.setEnd(replace.getStart());
			}
			if (itemDefaults.getEditRange() != null && itemDefaults.getEditRange().equals(getEditRange($, preferenceManager))) {
				$.setTextEditText(newText);
				$.setTextEdit(null);
			}
		}
		if (itemDefaults.getInsertTextFormat() != null && itemDefaults.getInsertTextFormat() == $.getInsertTextFormat()) {
			$.setInsertTextFormat(null);
		}
		return $;
	}

	private static Either<Range, InsertReplaceRange> getEditRange(CompletionItem completionItem, PreferenceManager preferenceManager) {
		if (preferenceManager.getClientPreferences().isCompletionInsertReplaceSupport()) {
			return Either.forRight(new InsertReplaceRange(completionItem.getTextEdit().getRight().getInsert(), completionItem.getTextEdit().getRight().getReplace()));
		} else {
			Range range = completionItem.getTextEdit().isLeft() ? completionItem.getTextEdit().getLeft().getRange()
					: (completionItem.getTextEdit().getRight().getInsert() != null ? completionItem.getTextEdit().getRight().getInsert() : completionItem.getTextEdit().getRight().getReplace());
			return Either.forLeft(range);
		}
	}

	@Override
	public void acceptContext(CompletionContext context) {
		super.acceptContext(context);
		this.context = context;
		response.setContext(context);
		this.descriptionProvider = new CompletionProposalDescriptionProvider(unit, context);
		this.proposalProvider = new CompletionProposalReplacementProvider(
			unit,
			context,
			response.getOffset(),
			preferenceManager.getPreferences(),
			preferenceManager.getClientPreferences(),
			false
		);
	}

	private CompletionItemKind mapKind(final CompletionProposal proposal) {
		//When a new CompletionItemKind is added, don't forget to update SUPPORTED_KINDS
		int kind = proposal.getKind();
		int flags = proposal.getFlags();
		switch (kind) {
		case CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION:
		case CompletionProposal.CONSTRUCTOR_INVOCATION:
			return CompletionItemKind.Constructor;
		case CompletionProposal.ANONYMOUS_CLASS_DECLARATION:
		case CompletionProposal.TYPE_REF:
			if (Flags.isInterface(flags)) {
				return CompletionItemKind.Interface;
			} else if (Flags.isEnum(flags)) {
				return CompletionItemKind.Enum;
			}
			return CompletionItemKind.Class;
		case CompletionProposal.FIELD_IMPORT:
		case CompletionProposal.METHOD_IMPORT:
		case CompletionProposal.PACKAGE_REF:
		case CompletionProposal.TYPE_IMPORT:
		case CompletionProposal.MODULE_DECLARATION:
		case CompletionProposal.MODULE_REF:
			return CompletionItemKind.Module;
		case CompletionProposal.FIELD_REF:
			if (Flags.isEnum(flags)) {
				return CompletionItemKind.EnumMember;
			}
			if (Flags.isStatic(flags) && Flags.isFinal(flags)) {
				return CompletionItemKind.Constant;
			}
			return CompletionItemKind.Field;
		case CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER:
			return CompletionItemKind.Field;
		case CompletionProposal.KEYWORD:
			return CompletionItemKind.Keyword;
		case CompletionProposal.LABEL_REF:
			return CompletionItemKind.Reference;
		case CompletionProposal.LOCAL_VARIABLE_REF:
		case CompletionProposal.VARIABLE_DECLARATION:
			return CompletionItemKind.Variable;
		case CompletionProposal.METHOD_DECLARATION:
		case CompletionProposal.METHOD_REF:
		case CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER:
		case CompletionProposal.METHOD_NAME_REFERENCE:
		case CompletionProposal.POTENTIAL_METHOD_DECLARATION:
		case CompletionProposal.LAMBDA_EXPRESSION:
			return CompletionItemKind.Method;
			//text
		case CompletionProposal.ANNOTATION_ATTRIBUTE_REF:
		case CompletionProposal.JAVADOC_BLOCK_TAG:
		case CompletionProposal.JAVADOC_FIELD_REF:
		case CompletionProposal.JAVADOC_INLINE_TAG:
		case CompletionProposal.JAVADOC_METHOD_REF:
		case CompletionProposal.JAVADOC_PARAM_REF:
		case CompletionProposal.JAVADOC_TYPE_REF:
		case CompletionProposal.JAVADOC_VALUE_REF:
		default:
			return CompletionItemKind.Text;
		}
	}

	@Override
	public void setIgnored(int completionProposalKind, boolean ignore) {
		super.setIgnored(completionProposalKind, ignore);
		if (completionProposalKind == CompletionProposal.METHOD_DECLARATION && !ignore) {
			setRequireExtendedContext(true);
		}
	}

	private void acceptPotentialMethodDeclaration(CompletionProposal proposal) {
		try {
			IJavaElement enclosingElement = null;
			if (response.getContext().isExtended()) {
				enclosingElement = response.getContext().getEnclosingElement();
			} else if (unit != null) {
				// kept for backward compatibility: CU is not reconciled at this moment, information is missing (bug 70005)
				enclosingElement = unit.getElementAt(proposal.getCompletionLocation() + 1);
			}
			if (enclosingElement == null) {
				return;
			}
			IType type = (IType) enclosingElement.getAncestor(IJavaElement.TYPE);
			if (type != null) {
				String prefix = String.valueOf(proposal.getName());
				int completionStart = proposal.getReplaceStart();
				int completionEnd = proposal.getReplaceEnd();
				int relevance = proposal.getRelevance() + 6;

				GetterSetterCompletionProposal.evaluateProposals(type, prefix, completionStart, completionEnd - completionStart, relevance, proposals);
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException("Accept potential method declaration failed for completion ", e);
		}
	}

	@Override
	public boolean isTestCodeExcluded() {
		return fIsTestCodeExcluded;
	}

	public CompletionContext getContext() {
		return context;
	}

	public List<CompletionProposal> getProposals() {
		return proposals;
	}

	/**
	 * copied from
	 * org.eclipse.jdt.ui.text.java.CompletionProposalCollector.isFiltered(CompletionProposal)
	 */
	protected boolean isFiltered(CompletionProposal proposal) {
		if (isIgnored(proposal.getKind())) {
			return true;
		}
		// Only filter types and constructors from completion.
		switch (proposal.getKind()) {
			case CompletionProposal.CONSTRUCTOR_INVOCATION:
			case CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION:
			case CompletionProposal.JAVADOC_TYPE_REF:
			case CompletionProposal.TYPE_REF:
				return isTypeFiltered(proposal);
			case CompletionProposal.METHOD_REF:
				// Methods from already imported types and packages can still be proposed.
				// Whether the expected type is resolved or not can be told from the required proposal.
				// When the type is missing, an additional proposal could be found.
				if (proposal.getRequiredProposals() != null) {
					return isTypeFiltered(proposal);
				}
		}
		return false;
	}

	protected boolean isTypeFiltered(CompletionProposal proposal) {
		// always includes type completions for import declarations.
		if (CompletionProposalUtils.isImportCompletion(proposal)) {
			return false;
		}
		char[] declaringType = getDeclaringType(proposal);
		return declaringType != null && TypeFilter.isFiltered(declaringType);
	}

	/**
	 * copied from
	 * org.eclipse.jdt.ui.text.java.CompletionProposalCollector.getDeclaringType(CompletionProposal)
	 */
	protected final char[] getDeclaringType(CompletionProposal proposal) {
		switch (proposal.getKind()) {
			case CompletionProposal.METHOD_DECLARATION:
			case CompletionProposal.METHOD_NAME_REFERENCE:
			case CompletionProposal.JAVADOC_METHOD_REF:
			case CompletionProposal.METHOD_REF:
			case CompletionProposal.CONSTRUCTOR_INVOCATION:
			case CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION:
			case CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER:
			case CompletionProposal.ANNOTATION_ATTRIBUTE_REF:
			case CompletionProposal.POTENTIAL_METHOD_DECLARATION:
			case CompletionProposal.ANONYMOUS_CLASS_DECLARATION:
			case CompletionProposal.FIELD_REF:
			case CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER:
			case CompletionProposal.JAVADOC_FIELD_REF:
			case CompletionProposal.JAVADOC_VALUE_REF:
				char[] declaration = proposal.getDeclarationSignature();
				// special methods may not have a declaring type: methods defined on arrays etc.
				// Currently known: class literals don't have a declaring type - use Object
				if (declaration == null) {
					return "java.lang.Object".toCharArray(); //$NON-NLS-1$
				}
				return Signature.toCharArray(declaration);
			case CompletionProposal.PACKAGE_REF:
			case CompletionProposal.MODULE_REF:
			case CompletionProposal.MODULE_DECLARATION:
				return proposal.getDeclarationSignature();
			case CompletionProposal.JAVADOC_TYPE_REF:
			case CompletionProposal.TYPE_REF:
				return Signature.toCharArray(proposal.getSignature());
			case CompletionProposal.LOCAL_VARIABLE_REF:
			case CompletionProposal.VARIABLE_DECLARATION:
			case CompletionProposal.KEYWORD:
			case CompletionProposal.LABEL_REF:
			case CompletionProposal.JAVADOC_BLOCK_TAG:
			case CompletionProposal.JAVADOC_INLINE_TAG:
			case CompletionProposal.JAVADOC_PARAM_REF:
				return null;
			default:
				Assert.isTrue(false);
				return null;
		}
	}

	@Override
	public boolean isIgnored(char[] fullTypeName) {
		return fullTypeName != null && TypeFilter.isFiltered(fullTypeName);
	}

	/**
	 * Check if the case is match between the proposal and the token according to the preference.
	 * @param proposal completion proposal.
	 */
	private boolean matchCase(CompletionProposal proposal) {
		if (CompletionMatchCaseMode.FIRSTLETTER != preferenceManager.getPreferences().getCompletionMatchCaseMode()) {
			return true;
		}

		if (this.context.getToken().length == 0 || proposal.getCompletion().length == 0) {
			return true;
		}

		char firstCharOfCompletion;
		if (proposal.getKind() == CompletionProposal.TYPE_REF) {
			String simpleTypeName = SignatureUtil.getSimpleTypeName(proposal);
			firstCharOfCompletion = simpleTypeName.charAt(0);
		} else {
			firstCharOfCompletion = proposal.getCompletion()[0];
		}

		if (this.context.getToken()[0] != firstCharOfCompletion) {
			return false;
		}

		return true;
	}
}
