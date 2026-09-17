/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A diagnostic report a club can send by email when something fails.
 *
 * The report is meant to leave the server, so nothing personal may be in it:
 * no addresses, no identifiers, no email addresses, no names. Counts and
 * versions are safe; log lines are not, and they go through redact() before
 * they are written. The redaction errs on the side of removing too much: a
 * timestamp mistaken for an identifier costs nothing, an IMEI left in costs
 * the member.
 */
public final class DiagnosticReport {

    private DiagnosticReport() {
    }

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern IPV6 = Pattern.compile(
            "\\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\\b|::[0-9a-fA-F]{1,4}\\b"
                    + "|\\b(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{0,4}\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("(device - )\\S+");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{9,}\\b");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{24,}\\b");
    private static final Pattern PROBLEM = Pattern.compile("\\s(WARN|ERROR|SEVERE):\\s");

    /** A log line with everything that could point at a person replaced. */
    public static String redact(String line) {
        String result = EMAIL.matcher(line).replaceAll("[correo]");
        result = IDENTIFIER.matcher(result).replaceAll("$1[identificador]");
        result = IPV4.matcher(result).replaceAll("[ip]");
        result = IPV6.matcher(result).replaceAll("[ip]");
        result = LONG_HEX.matcher(result).replaceAll("[hex]");
        result = LONG_NUMBER.matcher(result).replaceAll("[numero]");
        return result;
    }

    /** The last warnings and errors among the given log lines, oldest first, redacted. */
    public static List<String> lastProblems(List<String> lines, int max) {
        List<String> problems = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && problems.size() < max; i--) {
            if (PROBLEM.matcher(lines.get(i)).find()) {
                problems.add(0, redact(lines.get(i)));
            }
        }
        return problems;
    }

    /**
     * A configuration value as it may appear in the report. Anything that looks
     * like a credential says only whether it is set.
     */
    public static String configValue(String key, String value) {
        if (value == null) {
            return "(sin poner)";
        }
        String lower = key.toLowerCase();
        if (lower.contains("token") || lower.contains("password") || lower.contains("secret")
                || lower.contains("key") || lower.endsWith(".url") || lower.endsWith(".user")) {
            return "(puesta)";
        }
        return redact(value);
    }

    public static void section(StringBuilder out, String title, List<String> lines) {
        out.append("== ").append(title).append(" ==\n");
        if (lines.isEmpty()) {
            out.append("  (nada)\n");
        }
        for (String line : lines) {
            out.append("  ").append(line).append('\n');
        }
        out.append('\n');
    }

}
