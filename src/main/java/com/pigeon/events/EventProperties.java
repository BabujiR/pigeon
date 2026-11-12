package com.pigeon.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Event processing settings, bound from {@code pigeon.events.*}.
 *
 * @param enabled             start the listener on pigeon-events-queue
 * @param softBounceThreshold transient bounces within the window that suppress an address
 * @param softBounceWindow    window for counting transient bounces
 * @param simulator           local stand-in for SES event publishing
 */
@ConfigurationProperties(prefix = "pigeon.events")
public record EventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3") int softBounceThreshold,
        @DefaultValue("7d") Duration softBounceWindow,
        @DefaultValue Simulator simulator) {

    /**
     * @param enabled publish simulated SES events after each send (local profile only)
     * @param delay   pause before the first simulated event, like a real mailbox answering
     */
    public record Simulator(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("300ms") Duration delay) {
    }
}
