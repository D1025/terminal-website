package com.fonline.newdawn.storage;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class BucketBootstrap implements ApplicationRunner {
    private final StorageService storage;

    public BucketBootstrap(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        storage.ensureBucket();
    }
}
