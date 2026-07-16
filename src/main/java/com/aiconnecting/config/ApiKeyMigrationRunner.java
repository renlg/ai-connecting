package com.aiconnecting.config;

import com.aiconnecting.entity.Channel;
import com.aiconnecting.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyMigrationRunner implements ApplicationRunner {

    private static final String ENCRYPTED_PREFIX = "enc:v1:";
    private final ChannelRepository channelRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Channel> channels = channelRepository.findAll();
        int migrated = 0;
        for (Channel channel : channels) {
            String key = channel.getApiKey();
            if (key != null && !key.isEmpty() && !key.startsWith(ENCRYPTED_PREFIX)) {
                // Entity will auto-encrypt on save via the AttributeConverter
                channelRepository.save(channel);
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("Migrated {} channel(s) with legacy plaintext apiKey to encrypted storage", migrated);
        } else {
            log.info("No legacy plaintext apiKey found, migration skipped");
        }
    }
}
