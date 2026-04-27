/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.monitoring;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectionPoolMonitorTest {

    ConnectionPoolMonitor monitor;

    @Mock AgroalDataSource dataSource;
    @Mock Logger logger;

    @BeforeEach
    void setup() {
        monitor = new ConnectionPoolMonitor();
        monitor.dataSource = dataSource;
        monitor.log = logger;
    }

    @Test
    void testLogPoolStatsWithNoIssues() {
        AgroalDataSourceMetrics metrics = mock(AgroalDataSourceMetrics.class);
        when(dataSource.getMetrics()).thenReturn(metrics);
        when(metrics.activeCount()).thenReturn(5L);
        when(metrics.availableCount()).thenReturn(15L);
        when(metrics.awaitingCount()).thenReturn(0L);
        when(metrics.maxUsedCount()).thenReturn(8L);
        when(metrics.leakDetectionCount()).thenReturn(0L);

        monitor.logPoolStats();

        verify(logger)
                .debugf(
                        "Connection pool: active=%d, available=%d, waiting=%d, maxUsed=%d,"
                                + " leakDetection=%d",
                        5L, 15L, 0L, 8L, 0L);
    }

    @Test
    void testLogPoolStatsWithWaiting() {
        AgroalDataSourceMetrics metrics = mock(AgroalDataSourceMetrics.class);
        when(dataSource.getMetrics()).thenReturn(metrics);
        when(metrics.activeCount()).thenReturn(10L);
        when(metrics.availableCount()).thenReturn(0L);
        when(metrics.awaitingCount()).thenReturn(3L);
        when(metrics.maxUsedCount()).thenReturn(10L);
        when(metrics.leakDetectionCount()).thenReturn(0L);

        monitor.logPoolStats();

        verify(logger)
                .warnf(
                        "Connection pool issue detected: active=%d, available=%d, waiting=%d,"
                                + " maxUsed=%d, leakDetection=%d",
                        10L, 0L, 3L, 10L, 0L);
    }

    @Test
    void testLogPoolStatsWithLeakDetection() {
        AgroalDataSourceMetrics metrics = mock(AgroalDataSourceMetrics.class);
        when(dataSource.getMetrics()).thenReturn(metrics);
        when(metrics.activeCount()).thenReturn(8L);
        when(metrics.availableCount()).thenReturn(12L);
        when(metrics.awaitingCount()).thenReturn(0L);
        when(metrics.maxUsedCount()).thenReturn(10L);
        when(metrics.leakDetectionCount()).thenReturn(2L);

        monitor.logPoolStats();

        verify(logger)
                .warnf(
                        "Connection pool issue detected: active=%d, available=%d, waiting=%d,"
                                + " maxUsed=%d, leakDetection=%d",
                        8L, 12L, 0L, 10L, 2L);
    }

    @Test
    void testLogPoolStatsWithBothIssues() {
        AgroalDataSourceMetrics metrics = mock(AgroalDataSourceMetrics.class);
        when(dataSource.getMetrics()).thenReturn(metrics);
        when(metrics.activeCount()).thenReturn(10L);
        when(metrics.availableCount()).thenReturn(0L);
        when(metrics.awaitingCount()).thenReturn(5L);
        when(metrics.maxUsedCount()).thenReturn(10L);
        when(metrics.leakDetectionCount()).thenReturn(3L);

        monitor.logPoolStats();

        verify(logger)
                .warnf(
                        "Connection pool issue detected: active=%d, available=%d, waiting=%d,"
                                + " maxUsed=%d, leakDetection=%d",
                        10L, 0L, 5L, 10L, 3L);
    }

    @Test
    void testLogPoolStatsHandlesException() {
        when(dataSource.getMetrics()).thenThrow(new RuntimeException("Test exception"));

        monitor.logPoolStats();

        verify(logger)
                .errorf(
                        org.mockito.ArgumentMatchers.any(Exception.class),
                        org.mockito.ArgumentMatchers.eq(
                                "Failed to retrieve connection pool metrics"));
    }

    @Test
    void testGetActiveCount() {
        AgroalDataSourceMetrics metrics = mock(AgroalDataSourceMetrics.class);
        when(dataSource.getMetrics()).thenReturn(metrics);
        when(metrics.activeCount()).thenReturn(7L);

        long result = monitor.getActiveCount();

        Assertions.assertEquals(7L, result);
    }

    @Test
    void testGetAwaitingCount() {
        AgroalDataSourceMetrics metrics = mock(AgroalDataSourceMetrics.class);
        when(dataSource.getMetrics()).thenReturn(metrics);
        when(metrics.awaitingCount()).thenReturn(2L);

        long result = monitor.getAwaitingCount();

        Assertions.assertEquals(2L, result);
    }
}
