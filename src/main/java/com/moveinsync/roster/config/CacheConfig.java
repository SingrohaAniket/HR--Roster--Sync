package com.moveinsync.roster.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    public static final String OFFICES = "offices";

    @Bean
    public CacheManager cacheManager(CacheProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(OFFICES);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(properties.officeTtl())
                .maximumSize(properties.officeMaxSize()));
        return manager;
    }
}
