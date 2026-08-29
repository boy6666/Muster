package com.muster.common;

import java.util.regex.Pattern;

public final class PhoneValidator {

    private static final Pattern PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private PhoneValidator() {
    }

    public static boolean valid(String phone) {
        return phone != null && PATTERN.matcher(phone.trim()).matches();
    }
}
