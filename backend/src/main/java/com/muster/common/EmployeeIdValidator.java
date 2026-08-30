package com.muster.common;

import java.util.regex.Pattern;

/** 员工编号：1..32 个非空白字符，不做其他格式限制。 */
public final class EmployeeIdValidator {

    private static final Pattern PATTERN = Pattern.compile("^\\S{1,32}$");

    private EmployeeIdValidator() {
    }

    public static boolean isValid(String s) {
        return s != null && PATTERN.matcher(s).matches();
    }
}
