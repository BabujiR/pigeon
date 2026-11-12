package com.pigeon.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers for keeping personal data out of logs. */
public final class Pii {

    /** Good enough to find addresses inside free text (error messages from SES, for example). */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private Pii() {
    }

    /** dana.smith@example.com -> d***@example.com */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * Masks every email address inside a piece of text. Use on anything that comes from outside
     * before logging it: SES errors such as "Email address is not verified: dana@example.com"
     * would otherwise put a full address in the logs.
     */
    public static String maskEmailsIn(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = EMAIL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(maskEmail(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }
}
