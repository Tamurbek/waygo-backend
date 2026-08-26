package com.waygo.backend.config;

import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time-per-startup backfill for users created before the `active` column existed.
 * Safe to run every startup: it's a no-op once every row has a non-null value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveFlagBackfillRunner implements CommandLineRunner {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(String... args) {
        int updated = userRepository.backfillNullActiveFlags();
        if (updated > 0) {
            log.info("Backfilled active=true for {} pre-existing user(s)", updated);
        }
    }
}
