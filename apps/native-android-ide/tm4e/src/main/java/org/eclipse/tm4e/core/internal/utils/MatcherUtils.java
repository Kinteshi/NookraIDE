/**
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Written for QuireCode, in place of the sora-editor helper this copy of tm4e used.
 */
package org.eclipse.tm4e.core.internal.utils;

import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;

public final class MatcherUtils {

	private MatcherUtils() {
	}

	/** Java 9's `Matcher.replaceAll(Function)`, which older Android versions don't have. */
	public static String replaceAll(final CharSequence source, final Matcher matcher,
			final Function<MatchResult, String> replacer) {
		matcher.reset(source);
		final StringBuffer result = new StringBuffer(source.length());
		while (matcher.find()) {
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacer.apply(matcher.toMatchResult())));
		}
		matcher.appendTail(result);
		return result.toString();
	}
}
