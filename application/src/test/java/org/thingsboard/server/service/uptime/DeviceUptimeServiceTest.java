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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeviceUptimeServiceTest {

    private DeviceUptimeService service;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(5);
    private UUID deviceId;
    private Instant baseTime;

    @BeforeEach
    void setUp() {
        service = new DeviceUptimeService(HEARTBEAT_INTERVAL);
        deviceId = UUID.randomUUID();
        baseTime = Instant.parse("2025-01-15T10:00:00Z");
    }

    @Test
    @DisplayName("Constructor rejects null heartbeat interval")
    void constructor_nullInterval_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceUptimeService(null));
    }

    @Test
    @DisplayName("Constructor rejects zero heartbeat interval")
    void constructor_zeroInterval_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceUptimeService(Duration.ZERO));
    }

    @Test
    @DisplayName("Constructor rejects negative heartbeat interval")
    void constructor_negativeInterval_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceUptimeService(Duration.ofMinutes(-1)));
    }

    @Test
    @DisplayName("recordHeartbeat rejects null device ID")
    void recordHeartbeat_nullDeviceId_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordHeartbeat(null, baseTime));
    }

    @Test
    @DisplayName("recordHeartbeat rejects null timestamp")
    void recordHeartbeat_nullTimestamp_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordHeartbeat(deviceId, null));
    }

    @Test
    @DisplayName("Single heartbeat is recorded successfully")
    void recordHeartbeat_single_recordsDevice() {
        service.recordHeartbeat(deviceId, baseTime);
        assertTrue(service.getLastHeartbeat(deviceId).isPresent());
        assertEquals(baseTime, service.getLastHeartbeat(deviceId).get());
    }

    @Test
    @DisplayName("Multiple heartbeats update the last timestamp")
    void recordHeartbeat_multiple_updatesTimestamp() {
        Instant t1 = baseTime;
        Instant t2 = baseTime.plus(Duration.ofMinutes(3));
        service.recordHeartbeat(deviceId, t1);
        service.recordHeartbeat(deviceId, t2);
        assertEquals(t2, service.getLastHeartbeat(deviceId).get());
    }

    @Test
    @DisplayName("Device is online within heartbeat interval")
    void isDeviceOnline_withinInterval_returnsTrue() {
        service.recordHeartbeat(deviceId, baseTime);
        Instant check = baseTime.plus(Duration.ofMinutes(3));
        assertTrue(service.isDeviceOnline(deviceId, check));
    }

    @Test
    @DisplayName("Device is offline after heartbeat interval expires")
    void isDeviceOnline_afterInterval_returnsFalse() {
        service.recordHeartbeat(deviceId, baseTime);
        Instant check = baseTime.plus(Duration.ofMinutes(6));
        assertFalse(service.isDeviceOnline(deviceId, check));
    }

    @Test
    @DisplayName("Unknown device is considered offline")
    void isDeviceOnline_unknownDevice_returnsFalse() {
        assertFalse(service.isDeviceOnline(UUID.randomUUID(), baseTime));
    }

    @Test
    @DisplayName("isDeviceOnline returns false for null device ID")
    void isDeviceOnline_nullDeviceId_returnsFalse() {
        assertFalse(service.isDeviceOnline(null, baseTime));
    }

    @Test
    @DisplayName("isDeviceOnline returns false for null timestamp")
    void isDeviceOnline_nullTimestamp_returnsFalse() {
        service.recordHeartbeat(deviceId, baseTime);
        assertFalse(service.isDeviceOnline(deviceId, null));
    }

    @Test
    @DisplayName("Uptime is empty for unknown device")
    void getUptimePercentage_unknownDevice_isEmpty() {
        assertTrue(service.getUptimePercentage(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("Uptime is empty for null device")
    void getUptimePercentage_nullDevice_isEmpty() {
        assertTrue(service.getUptimePercentage(null).isEmpty());
    }

    @Test
    @DisplayName("Single heartbeat yields 100% uptime")
    void getUptimePercentage_singleHeartbeat_returns100() {
        service.recordHeartbeat(deviceId, baseTime);
        Optional<Double> pct = service.getUptimePercentage(deviceId);
        assertTrue(pct.isPresent());
        assertEquals(100.0, pct.get(), 0.01);
    }

    @Test
    @DisplayName("Continuous heartbeats yield ~100% uptime")
    void getUptimePercentage_continuousHeartbeats_near100() {
        // Send heartbeats every 2 minutes for 10 minutes (within 5-min interval)
        for (int i = 0; i <= 5; i++) {
            service.recordHeartbeat(deviceId, baseTime.plus(Duration.ofMinutes(i * 2)));
        }
        Optional<Double> pct = service.getUptimePercentage(deviceId);
        assertTrue(pct.isPresent());
        assertEquals(100.0, pct.get(), 0.01);
    }

    @Test
    @DisplayName("Gap longer than interval reduces uptime percentage")
    void getUptimePercentage_withGap_reducedUptime() {
        // t=0: heartbeat
        service.recordHeartbeat(deviceId, baseTime);
        // t=3min: heartbeat (gap 3 ≤ 5 → counted as alive)
        service.recordHeartbeat(deviceId, baseTime.plus(Duration.ofMinutes(3)));
        // t=20min: heartbeat (gap 17 > 5 → NOT counted)
        service.recordHeartbeat(deviceId, baseTime.plus(Duration.ofMinutes(20)));

        Optional<Double> pct = service.getUptimePercentage(deviceId);
        assertTrue(pct.isPresent());
        // cumulative uptime = 3 min, total window = 20 min → 15%
        assertEquals(15.0, pct.get(), 0.5);
    }

    @Test
    @DisplayName("removeDevice clears all tracking data")
    void removeDevice_clearsData() {
        service.recordHeartbeat(deviceId, baseTime);
        assertEquals(1, service.getTrackedDeviceCount());

        service.removeDevice(deviceId);

        assertEquals(0, service.getTrackedDeviceCount());
        assertTrue(service.getLastHeartbeat(deviceId).isEmpty());
        assertTrue(service.getUptimePercentage(deviceId).isEmpty());
    }

    @Test
    @DisplayName("removeDevice with null does not throw")
    void removeDevice_null_noException() {
        assertDoesNotThrow(() -> service.removeDevice(null));
    }

    @Test
    @DisplayName("Tracked count reflects number of unique devices")
    void getTrackedDeviceCount_multipleDevices() {
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();

        service.recordHeartbeat(d1, baseTime);
        service.recordHeartbeat(d2, baseTime);
        service.recordHeartbeat(d3, baseTime);

        assertEquals(3, service.getTrackedDeviceCount());
    }

    @Test
    @DisplayName("Empty service has zero tracked devices")
    void getTrackedDeviceCount_empty_returnsZero() {
        assertEquals(0, service.getTrackedDeviceCount());
    }
}
