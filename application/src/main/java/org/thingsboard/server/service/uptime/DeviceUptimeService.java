/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.uptime;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceUptimeService {

    public static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final Map<UUID, Instant> lastHeartbeat = new ConcurrentHashMap<>();

    private final Map<UUID, Instant> firstSeen = new ConcurrentHashMap<>();

    private final Map<UUID, Duration> cumulativeUptime = new ConcurrentHashMap<>();

    private final Duration heartbeatInterval;

    public DeviceUptimeService(Duration heartbeatInterval) {
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeatInterval must be a positive duration");
        }
        this.heartbeatInterval = heartbeatInterval;
    }

    public void recordHeartbeat(UUID deviceId, Instant timestamp) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId must not be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }

        firstSeen.putIfAbsent(deviceId, timestamp);

        Instant previous = lastHeartbeat.put(deviceId, timestamp);
        if (previous != null) {
            Duration gap = Duration.between(previous, timestamp);
            // Only count as "alive" time if the gap ≤ heartbeatInterval
            if (!gap.isNegative() && gap.compareTo(heartbeatInterval) <= 0) {
                cumulativeUptime.merge(deviceId, gap, Duration::plus);
            }
        }
    }

    public boolean isDeviceOnline(UUID deviceId, Instant now) {
        if (deviceId == null || now == null) {
            return false;
        }
        Instant last = lastHeartbeat.get(deviceId);
        if (last == null) {
            return false;
        }
        return Duration.between(last, now).compareTo(heartbeatInterval) <= 0;
    }

    public Optional<Double> getUptimePercentage(UUID deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        Instant first = firstSeen.get(deviceId);
        Instant last = lastHeartbeat.get(deviceId);
        if (first == null || last == null) {
            return Optional.empty();
        }
        Duration totalWindow = Duration.between(first, last);
        if (totalWindow.isZero() || totalWindow.isNegative()) {
            return Optional.of(100.0); // single heartbeat → 100 %
        }
        Duration uptime = cumulativeUptime.getOrDefault(deviceId, Duration.ZERO);
        double pct = 100.0 * uptime.toMillis() / totalWindow.toMillis();
        return Optional.of(Math.min(pct, 100.0));
    }

    public Optional<Instant> getLastHeartbeat(UUID deviceId) {
        return Optional.ofNullable(lastHeartbeat.get(deviceId));
    }

    public void removeDevice(UUID deviceId) {
        if (deviceId != null) {
            lastHeartbeat.remove(deviceId);
            firstSeen.remove(deviceId);
            cumulativeUptime.remove(deviceId);
        }
    }

    public int getTrackedDeviceCount() {
        return lastHeartbeat.size();
    }
}
