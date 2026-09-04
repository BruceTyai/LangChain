package com.localmind.service;

import com.localmind.dao.entity.AppSetting;
import com.localmind.dao.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AnonymousAccessService {

    private static final String SETTING_KEY = "security.allow-anonymous";

    private final AppSettingRepository repository;
    private final boolean defaultAllowed;
    private volatile boolean allowed;

    public AnonymousAccessService(
            AppSettingRepository repository,
            @Value("${app.security.allow-anonymous:false}") boolean defaultAllowed) {
        this.repository = repository;
        this.defaultAllowed = defaultAllowed;
    }

    @PostConstruct
    void initialize() {
        allowed = repository.findById(SETTING_KEY)
                .map(AppSetting::getValue)
                .map(Boolean::parseBoolean)
                .orElse(defaultAllowed);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public synchronized boolean setAllowed(boolean allowed) {
        AppSetting setting = repository.findById(SETTING_KEY)
                .orElseGet(() -> new AppSetting(SETTING_KEY, Boolean.toString(allowed)));
        setting.setValue(Boolean.toString(allowed));
        repository.saveAndFlush(setting);
        this.allowed = allowed;
        return allowed;
    }
}
