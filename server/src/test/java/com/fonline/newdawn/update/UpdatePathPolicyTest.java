package com.fonline.newdawn.update;

import com.fonline.newdawn.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdatePathPolicyTest {
    @Test
    void normalizesLegacyWindowsSeparatorsWithoutChangingDisplayCase() {
        UpdatePathPolicy.NormalizedPath path = UpdatePathPolicy.normalize(".\\Data\\Patch010.zip");

        assertThat(path.value()).isEqualTo("Data/Patch010.zip");
        assertThat(path.key()).isEqualTo("data/patch010.zip");
        assertThat(path.fileName()).isEqualTo("Patch010.zip");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../FOnline.exe",
            "data/../../Windows/system.ini",
            "C:\\game\\file.dat",
            "/absolute/file.dat",
            "data//file.dat",
            "data/CON.txt",
            "data/file?.dat",
            "data/trailing."
    })
    void rejectsPathsThatCouldEscapeOrBreakAWindowsInstallation(String value) {
        assertThatThrownBy(() -> UpdatePathPolicy.normalize(value))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("safe relative Windows path");
    }
}
