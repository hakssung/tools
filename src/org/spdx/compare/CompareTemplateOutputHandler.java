/**
 * Copyright (c) 2013 Source Auditor Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
*/
package org.spdx.compare;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spdx.licenseTemplate.ILicenseTemplateOutputHandler;
import org.spdx.licenseTemplate.LicenseTemplateRule;
import org.spdx.licenseTemplate.LicenseTemplateRule.RuleType;
import org.spdx.rdfparser.license.LicenseParserException;

import com.google.common.collect.Lists;

/**
 * Compares the output of a parsed license template to text.  The method matches is called after
 * the document is parsed to determine if the text matches.
 * @author Gary O'Neall
 *
 */
public class CompareTemplateOutputHandler implements
		ILicenseTemplateOutputHandler {
	
	class ParseInstruction {
		LicenseTemplateRule rule;
		String text;
		List<ParseInstruction> subInstructions;
		ParseInstruction parent;
		
		ParseInstruction(LicenseTemplateRule rule, String text, ParseInstruction parent) {
			this.rule = rule;
			this.text = text;
			this.subInstructions = Lists.newArrayList();
			this.parent = parent;
		}

		/**
		 * @return the rule
		 */
		public LicenseTemplateRule getRule() {
			return rule;
		}

		/**
		 * @param rule the rule to set
		 */
		public void setRule(LicenseTemplateRule rule) {
			this.rule = rule;
		}

		/**
		 * @return the text
		 */
		public String getText() {
			return text;
		}

		/**
		 * @param optionalText the text to set
		 */
		public void setText(String text) {
			this.text = text;
		}

		/**
		 * Add the instruction to the list of sub-instructions
		 * @param instruction
		 */
		public void addSubInstruction(ParseInstruction instruction) {
			this.subInstructions.add(instruction);
		}

		/**
		 * @return the parent
		 */
		public ParseInstruction getParent() {
			return parent;
		}

		/**
		 * @param parent the parent to set
		 */
		public void setParent(ParseInstruction parent) {
			this.parent = parent;
		}

		/**
		 * @return the subInstructions
		 */
		public List<ParseInstruction> getSubInstructions() {
			return subInstructions;
		}

		/**
		 * @return true iff there are only text instructions as sub instructions
		 */
		public boolean onlyText() {
			if (this.subInstructions.size() < 1) {
				return false;
			}
			for (ParseInstruction subInstr:this.subInstructions) {
				if (subInstr.getText() == null) {
					return false;
				}
			}
			return true;
		}

		public String toText() {
			StringBuilder sb = new StringBuilder();
			for (ParseInstruction subInstr:this.subInstructions) {
				if (subInstr.getText() != null) {
					sb.append(subInstr.getText());
				}
			}
			return sb.toString();
		}
	}
	
	public class DifferenceDescription {
		private boolean differenceFound;
		private String differenceMessage;
		private List<LineColumn> differences;
		
		public DifferenceDescription(boolean differenceFound, String differenceMessage, List<LineColumn> differences) {
			this.differenceFound = differenceFound;
			this.differenceMessage = differenceMessage;
			this.differences = differences;
		}

		public boolean isDifferenceFound() {
			return differenceFound;
		}

		public void setDifferenceFound(boolean differenceFound) {
			this.differenceFound = differenceFound;
		}

		public String getDifferenceMessage() {
			return differenceMessage;
		}

		public void setDifferenceMessage(String differenceMessage) {
			this.differenceMessage = differenceMessage;
		}

		public List<LineColumn> getDifferences() {
			return differences;
		}

		public void setDifferences(List<LineColumn> differences) {
			this.differences = differences;
		}		
	}

	String[] compareTokens = new String[0];
	String compareText = "";
	Map<Integer, LineColumn> tokenToLocation = new HashMap<Integer, LineColumn>();
	List<ParseInstruction> instructionList = new ArrayList<ParseInstruction>();
	boolean differenceFound = false;
	int compareTokenCounter = 0;
	String differenceExplanation = "No difference found";
	List<LineColumn> differences = new ArrayList<LineColumn>();
	String nextCompareToken = null;
	int currentInstIndex = 0;
	ParseInstruction currentOptionalInstruction = null;	// if we are inside a beingOptional / endOptional bracket, this will hold the optional instruction
	
	/**
	 * @param compareText Text to compare the parsed SPDX license template to
	 * @throws IOException This is not to be expected since we are using StringReaders
	 */
	public CompareTemplateOutputHandler(String compareText) throws IOException {
		this.compareText = LicenseCompareHelper.normalizeText(compareText);
		this.compareTokens = LicenseCompareHelper.tokenizeLicenseText(this.compareText, tokenToLocation);
		this.currentInstIndex = 0;
		this.compareTokenCounter = 0;
		nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, compareTokenCounter++);
	}

	/* (non-Javadoc)
	 * @see org.spdx.licenseTemplate.ILicenseTemplateOutputHandler#text(java.lang.String)
	 */
	@Override
	public void text(String text) {
		if (currentOptionalInstruction != null) {
			currentOptionalInstruction.addSubInstruction(new ParseInstruction(null, text, currentOptionalInstruction));
		} else {
			this.instructionList.add(new ParseInstruction(null, text, null));
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.licenseTemplate.ILicenseTemplateOutputHandler#variableRule(org.spdx.licenseTemplate.LicenseTemplateRule)
	 */
	@Override
	public void variableRule(LicenseTemplateRule rule) {
		if (currentOptionalInstruction != null) {
			currentOptionalInstruction.addSubInstruction(new ParseInstruction(rule, null, currentOptionalInstruction));
		} else {
			this.instructionList.add(new ParseInstruction(rule, null, null));
		}
	}


	/* (non-Javadoc)
	 * @see org.spdx.licenseTemplate.ILicenseTemplateOutputHandler#beginOptional(org.spdx.licenseTemplate.LicenseTemplateRule)
	 */
	@Override
	public void beginOptional(LicenseTemplateRule rule) {
		ParseInstruction optionalInstruction = new ParseInstruction(rule, null, currentOptionalInstruction);
		if (currentOptionalInstruction != null) {
			currentOptionalInstruction.addSubInstruction(optionalInstruction);
		} else {
			this.instructionList.add(optionalInstruction);
		}
		this.currentOptionalInstruction = optionalInstruction;
	}

	/* (non-Javadoc)
	 * @see org.spdx.licenseTemplate.ILicenseTemplateOutputHandler#endOptional(org.spdx.licenseTemplate.LicenseTemplateRule)
	 */
	@Override
	public void endOptional(LicenseTemplateRule rule) {
		if (currentOptionalInstruction != null) {
			currentOptionalInstruction = currentOptionalInstruction.getParent();
		}
	}

	/**
	 * compare the text to the compare text at the current location
	 * @param textTokens tokenized text to check for equivalence
	 * @return true if the text is equivalent
	 */
	protected boolean textEquivalent(String[] textTokens) {			
		int textTokenCounter = 0;
		String nextTextToken = LicenseCompareHelper.getTokenAt(textTokens, textTokenCounter++);
		while (nextTextToken != null) {
			if (this.nextCompareToken == null) {
				// end of compare text stream
				while (nextTextToken != null && LicenseCompareHelper.canSkip(nextTextToken)) {
					nextTextToken = LicenseCompareHelper.getTokenAt(textTokens, textTokenCounter++);
				}
				if (nextTextToken != null) {
					return false;	// there is more stuff in the compare license text, so not equiv.
				}
			} else if (LicenseCompareHelper.tokensEquivalent(nextTextToken, this.nextCompareToken)) { 
				// just move onto the next set of tokens
				nextTextToken = LicenseCompareHelper.getTokenAt(textTokens, textTokenCounter++);
				this.nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, this.compareTokenCounter++);
			} else {
				// see if we can skip through some compare tokens to find a match
				while (this.nextCompareToken != null && LicenseCompareHelper.canSkip(this.nextCompareToken)) {
					this.nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, this.compareTokenCounter++);
				}
				// just to be sure, skip forward on the text
				while (nextTextToken != null && LicenseCompareHelper.canSkip(nextTextToken)) {
					nextTextToken = LicenseCompareHelper.getTokenAt(textTokens, textTokenCounter++);
				}
				if (!LicenseCompareHelper.tokensEquivalent(this.nextCompareToken, nextTextToken)) {
					return false;
				} else {
					nextTextToken = LicenseCompareHelper.getTokenAt(textTokens, textTokenCounter++);
					this.nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, this.compareTokenCounter++);
				}
			}
		}
		return true;
	}
	
	/**
	 * Add a difference to the difference list
	 * @param msg Difference message
	 * @param location Location where the difference was found
	 */
	private void addDifference(String msg, LineColumn location) {
		if (location != null) {
			this.differenceExplanation = msg + " starting at line #"+
					String.valueOf(location.getLine())+ " column #" +
					String.valueOf(location.getColumn())+"\""+
					this.nextCompareToken+"\".";
			this.differences.add(location);
		} else {
			this.differenceExplanation = msg + " at end of text";
		}
	}
	
	/**
	 * Process a rule, looking for proper matches
	 * @param rule
	 */
	private void processVariableRule(LicenseTemplateRule rule) {
		if (differenceFound) {
			return;
		}
		List<Integer> matchingStartTokens = findNextMatchingStartTokens();
		if (differenceFound) {
			return;
		}
		boolean matchFound = false;
		for (int matchingStartToken:matchingStartTokens) {
			String compareText = buildCompareText(this.compareTokenCounter-1, matchingStartToken-1);
			Pattern matchPattern = Pattern.compile(rule.getMatch(), Pattern.CASE_INSENSITIVE);
			Matcher matcher = matchPattern.matcher(compareText);
			if (!matcher.find() || matcher.start() > 0) {
				continue;
			} else {
				matchFound = true;
				int numMatched = numTokensMatched(compareText, matcher.end());
				this.compareTokenCounter = this.compareTokenCounter + numMatched - 1;
				this.nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, compareTokenCounter++);
				break;
			}
		}
		if (!matchFound) {
			this.differenceFound = true;
			addDifference("Variable text rule "+rule.getName()+" did not match the compare text",
					tokenToLocation.get(this.compareTokenCounter));
		}
	}

	/**
	 * @return Token indexes for the starting tokens which will match the remaining rules
	 */
	private List<Integer> findNextMatchingStartTokens() {
		List<Integer> retval = new ArrayList<Integer>();
		if (currentInstIndex >= instructionList.size()) {
			retval.add(compareTokens.length);
			return retval;
		}
		
		String firstNormalText = null;
		int i = this.currentInstIndex;
		while (i < instructionList.size() && firstNormalText == null) {
			firstNormalText = instructionList.get(i++).getText();
		}
		
		if (firstNormalText == null) {
			retval.add(compareTokens.length-1);
			return retval;
		}
		
		Map<Integer, LineColumn> normalTextLocations = new HashMap<Integer, LineColumn>();
		String[] textTokens = LicenseCompareHelper.tokenizeLicenseText(LicenseCompareHelper.normalizeText(firstNormalText), normalTextLocations);
		// Save state
		String saveNextComparisonToken = nextCompareToken;
		int saveCompareTokenCounter = compareTokenCounter;
		String saveDifferenceExplanation = this.differenceExplanation;
		List<LineColumn> saveDifferences = new ArrayList<LineColumn>();
		Collections.copy(saveDifferences, this.differences);
		
		int nextMatchingStart = compareTokenCounter-1;
		boolean found = textEquivalent(textTokens);
		while (!found && compareTokenCounter < compareTokens.length) {			
			nextMatchingStart = nextMatchingStart + 1;
			compareTokenCounter = nextMatchingStart;
			nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, compareTokenCounter++);
			found = textEquivalent(textTokens);
		}
		
		if (found) {
			retval.add(nextMatchingStart);		// restore state
			this.nextCompareToken = saveNextComparisonToken;
			this.compareTokenCounter = saveCompareTokenCounter;
			this.differenceExplanation = saveDifferenceExplanation;
			Collections.copy(this.differences, saveDifferences);
		} else {
			this.differenceFound = true;
			this.differenceExplanation = "Unable to find the text following a variable template rule '"+firstNormalText + "'";
			this.addDifference(this.differenceExplanation, this.tokenToLocation.get(saveCompareTokenCounter));
		}
		return retval;
	}

	/**
	 * Builds a string from the tokens
	 * @param startToken starting token index
	 * @param endToken ending token index
	 * @return
	 */
	private String buildCompareText(int startToken, int endToken) {
		return LicenseCompareHelper.locateOriginalText(compareText, startToken, endToken, tokenToLocation, this.compareTokens);
	}
	
	
	/**
	 * Determine the number of tokens matched from the compare text
	 * @param text
	 * @param end End of matching text
	 * @return
	 */
	private int numTokensMatched(String text, int end) {
		if (text.trim().isEmpty()) {
			return 0;
		}
		if (end == 0) {
			return 0;
		}
		Map<Integer, LineColumn> temp = new HashMap<Integer, LineColumn>();
		return LicenseCompareHelper.tokenizeLicenseText(text.substring(0, end), temp).length;
	}

	/**
	 * Process optional text moving the counter as appropriate if the optional text is found
	 * @param text
	 */
	private void processOptionalText(ParseInstruction optionalInstruction) {
		if (differenceFound) {
			return;
		}
		String saveNextComparisonToken = nextCompareToken;
		int saveCompareTokenCounter = compareTokenCounter;
		String saveDifferenceExplanation = this.differenceExplanation;
		List<LineColumn> saveDifferences = new ArrayList<LineColumn>();
		Collections.copy(saveDifferences, this.differences);
		boolean foundOptional = false;
		if (optionalInstruction.onlyText()) {
			// simple case, we'll just text the text
			foundOptional = textEquivalent(optionalInstruction.toText());
		} else {
			// We need to treat this like a variable block, find the end and process the subinstructions
			//TODO: Implement
			foundOptional = true;
			this.differenceExplanation = "Unsupported nested optional and var rules within an optional block";
			this.differenceFound = true;
		}
		if (!foundOptional) {
			// reset counters
			this.nextCompareToken = saveNextComparisonToken;
			this.compareTokenCounter = saveCompareTokenCounter;
			this.differenceExplanation = saveDifferenceExplanation;
			Collections.copy(this.differences, saveDifferences);
		}	
	}

	/**
	 * Process normal text making sure the normal text can be found and advancing the token pointers
	 * @param text
	 */
	private void processNormalText(String text) {
		if (differenceFound) {
			return;
		}
		Map<Integer, LineColumn> textLocations = new HashMap<Integer, LineColumn>();
		String[] textTokens = LicenseCompareHelper.tokenizeLicenseText(LicenseCompareHelper.normalizeText(text), textLocations);
		
		if (!textEquivalent(textTokens)) {
			// Check for optional text which is less than a token in length - fix issue #114
			// We need to check if the last token matches considering optional or the last 
			// two tokens match a single compare token considering optional
			if (textTokens.length > 1 && nextCompareToken != null && 
					instructionList.size() > currentInstIndex && 
					instructionList.get(currentInstIndex).getRule() != null &&
					instructionList.get(currentInstIndex).getRule().getType().equals(RuleType.BEGIN_OPTIONAL) &&
					instructionList.get(currentInstIndex).getSubInstructions().size() == 1 &&
					instructionList.get(currentInstIndex).getSubInstructions().get(0).getText() != null) {
				String optionalText = instructionList.get(currentInstIndex).getSubInstructions().get(0).getText().trim();
				String tokenWithOption = textTokens[textTokens.length-1] + optionalText;
				if (nextCompareToken.equals(tokenWithOption)) {
					this.currentInstIndex++;
					this.nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, compareTokenCounter++);
					this.differenceFound = false;
				} else {
					// Check one more scenario where there is a normal text following that is also part of the same token
					if (instructionList.size() > currentInstIndex+1 && 
							instructionList.get(currentInstIndex+1).getText() != null) {
						String normalText = instructionList.get(currentInstIndex+1).getText();
						Map<Integer, LineColumn> normalTextLocations = new HashMap<Integer, LineColumn>();
						String[] normalTextTokens = LicenseCompareHelper.tokenizeLicenseText(LicenseCompareHelper.normalizeText(normalText), normalTextLocations);
						String firstNormalText = LicenseCompareHelper.getTokenAt(normalTextTokens, 0);
						String twoTokensWithOption = tokenWithOption;
						String twoTokensWithoutOption = textTokens[textTokens.length-1];
						if (firstNormalText != null) {
							twoTokensWithOption = twoTokensWithOption + firstNormalText.trim();
							twoTokensWithoutOption = twoTokensWithoutOption + firstNormalText.trim();
						}
						if (nextCompareToken.equals(twoTokensWithoutOption) || nextCompareToken.equals(twoTokensWithOption)) {
							int startCol = normalTextLocations.get(1).getColumn();
							if (normalTextLocations.get(1).getLine() > 1) {
								startCol = normalText.indexOf("\"");
							}
							String normalMinusFirstToken = normalText.substring(startCol);
							instructionList.get(currentInstIndex+1).setText(normalMinusFirstToken);
							this.currentInstIndex++;
							this.nextCompareToken = LicenseCompareHelper.getTokenAt(compareTokens, compareTokenCounter++);
							this.differenceFound = false;
						}
					} else {
						//TODO: Handle the situation where variable text follows the optional text
						this.differenceFound = true;
					}
				}
			} else {
				this.differenceFound = true;
			}
			if (this.differenceFound) {
				if (this.nextCompareToken == null) {
					LineColumn lastLineColumn = tokenToLocation.get(compareTokens.length-1);
					// create a zero length location at the end of the file
					addDifference("End of compare text encountered before the end of the license template",
							new LineColumn(lastLineColumn.getLine(), lastLineColumn.getColumn()+lastLineColumn.getLen(),0));
				} else {
					addDifference("Difference found in normal text",tokenToLocation.get(this.compareTokenCounter));
				}
			}
		}
	}
	
	/**
	 * compare the text to the compare text at the current location
	 * @param text
	 * @return true if the text is equivalent
	 */
	protected boolean textEquivalent(String text) {
		Map<Integer, LineColumn> textLocations = new HashMap<Integer, LineColumn>();
		String[] textTokens = LicenseCompareHelper.tokenizeLicenseText(LicenseCompareHelper.normalizeText(text), textLocations);
		return textEquivalent(textTokens);
	}

	/**
	 * Performs the actual parsing if it has not been completed.  NOTE: This should only be called after all text has been added.
	 * @return true if no differences were found
	 * @throws LicenseParserException 
	 */
	public boolean matches() throws LicenseParserException {
		if (currentInstIndex < instructionList.size() && !differenceFound) {
			throw new LicenseParserException("Matches was called prior to completing the parsing.  The method <code>competeParsing()</code> most be called prior to calling <code>matches()</code>");
		}
		return !differenceFound;
	}
	
	/**
	 * @return details on the differences found
	 */
	public DifferenceDescription getDifferences() {
		return new DifferenceDescription(differenceFound,
				differenceExplanation, differences);
	}

	/* (non-Javadoc)
	 * @see org.spdx.licenseTemplate.ILicenseTemplateOutputHandler#completeParsing()
	 */
	@Override
	public void completeParsing() {
		if (currentInstIndex == 0) {
			while (currentInstIndex < instructionList.size() && !differenceFound) {
				ParseInstruction currentInstruction = instructionList.get(currentInstIndex++);
				if (currentInstruction.getText() != null) {
					processNormalText(currentInstruction.getText());
				} else if (currentInstruction.getRule() != null && 
						currentInstruction.getRule().getType().equals(RuleType.BEGIN_OPTIONAL)) {
					processOptionalText(currentInstruction);
				} else if (currentInstruction.getRule() != null) {
					processVariableRule(currentInstruction.getRule());
				}
			}
		}
	}

}
