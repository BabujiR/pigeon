package com.pigeon.support;

/** Helpers for keeping personal data out of logs. */
public final class Pii {

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
}
