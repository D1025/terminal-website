package com.fonline.newdawn.update;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyCrc32Test {
    @Test
    void matchesTheLegacyLauncherHashForKnownInput() {
        LegacyCrc32 crc = new LegacyCrc32();
        byte[] bytes = "abc".getBytes(StandardCharsets.US_ASCII);

        crc.update(bytes, 0, bytes.length);

        assertThat(crc.value()).isEqualTo(0x352441C2);
    }

    @Test
    void streamingChunksProduceTheSameHash() {
        byte[] bytes = "data/patch010.zip".getBytes(StandardCharsets.UTF_8);
        LegacyCrc32 whole = new LegacyCrc32();
        LegacyCrc32 chunks = new LegacyCrc32();

        whole.update(bytes, 0, bytes.length);
        chunks.update(bytes, 0, 5);
        chunks.update(bytes, 5, bytes.length - 5);

        assertThat(chunks.value()).isEqualTo(whole.value());
    }
}
