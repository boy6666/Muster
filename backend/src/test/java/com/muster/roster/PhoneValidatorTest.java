package com.muster.roster;

import com.muster.common.PhoneValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneValidatorTest {

    @Test
    void validMobilePasses() {
        assertThat(PhoneValidator.valid("13812345678")).isTrue();
    }

    @Test
    void wrongPrefixFails() {
        assertThat(PhoneValidator.valid("23812345678")).isFalse();
    }

    @Test
    void shortNumberFails() {
        assertThat(PhoneValidator.valid("1381234567")).isFalse();
    }

    @Test
    void nullFails() {
        assertThat(PhoneValidator.valid(null)).isFalse();
    }
}
