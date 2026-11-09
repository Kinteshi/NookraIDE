/**
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Written for MaterialCode, in place of the sora-editor logger this copy of tm4e used:
 * java.util.logging, which Android sends to logcat, keeps the module a plain Java library.
 */
package org.eclipse.tm4e.core.internal.utils;

import java.text.MessageFormat;
import java.util.logging.Level;

public final class Logger {

	private final java.util.logging.Logger delegate;

	private Logger(final String name) {
		delegate = java.util.logging.Logger.getLogger(name);
	}

	public static Logger instance(final String name) {
		return new Logger(name);
	}

	public void i(final String message, final Object... args) {
		log(Level.INFO, message, args);
	}

	public void w(final String message, final Object... args) {
		log(Level.WARNING, message, args);
	}

	public void e(final String message, final Object... args) {
		log(Level.SEVERE, message, args);
	}

	/** tm4e's messages use both `{0}` and `%s` placeholders. */
	private void log(final Level level, final String message, final Object... args) {
		if (!delegate.isLoggable(level)) return;
		String text = message;
		if (args.length > 0) {
			text = message.contains("{0}") ? MessageFormat.format(message, args) : String.format(message, args);
		}
		delegate.log(level, text);
	}
}
