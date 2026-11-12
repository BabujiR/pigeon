package com.pigeon.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled, used by the outbox relay. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
