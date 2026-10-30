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
package io.cryostat.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cryostat.diagnostic.Diagnostics.UnifiedLog;
import io.cryostat.graphql.UnifiedLogsGraphQL.UnifiedLogsFilter;
import io.cryostat.recordings.ActiveRecordings.Metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class UnifiedLogsFilterTest {

    private static final long BOUND = 100L;

    private static UnifiedLog log(long lastModified, long size) {
        return new UnifiedLog(
                "jvm", "http://example/log", "log", lastModified, size, Metadata.empty());
    }

    @ParameterizedTest
    @CsvSource({"99,false", "100,true", "101,true"})
    void sizeLowerBound(long size, boolean expected) {
        var filter = new UnifiedLogsFilter();
        filter.sizeBytesGreaterThanEqual = BOUND;
        assertEquals(expected, filter.test(log(0, size)));
    }

    @ParameterizedTest
    @CsvSource({"99,true", "100,true", "101,false"})
    void sizeUpperBound(long size, boolean expected) {
        var filter = new UnifiedLogsFilter();
        filter.sizeBytesLessThanEqual = BOUND;
        assertEquals(expected, filter.test(log(0, size)));
    }

    @ParameterizedTest
    @CsvSource({"99,false", "100,true", "101,true"})
    void archivedTimeLowerBound(long lastModified, boolean expected) {
        var filter = new UnifiedLogsFilter();
        filter.archivedTimeAfterEqual = BOUND;
        assertEquals(expected, filter.test(log(lastModified, 0)));
    }

    @ParameterizedTest
    @CsvSource({"99,true", "100,true", "101,false"})
    void archivedTimeUpperBound(long lastModified, boolean expected) {
        var filter = new UnifiedLogsFilter();
        filter.archivedTimeBeforeEqual = BOUND;
        assertEquals(expected, filter.test(log(lastModified, 0)));
    }

    @Test
    void nullBoundsDoNotRestrict() {
        var filter = new UnifiedLogsFilter();
        assertEquals(true, filter.test(log(0, 0)));
        assertEquals(true, filter.test(log(Long.MAX_VALUE, Long.MAX_VALUE)));
    }

    @ParameterizedTest
    @CsvSource({
        "49,100,false",
        "50,100,true",
        "75,100,true",
        "100,100,true",
        "101,100,false",
        "75,49,false",
        "75,50,true",
        "75,101,false"
    })
    void combinedBounds(long size, long lastModified, boolean expected) {
        var filter = new UnifiedLogsFilter();
        filter.sizeBytesGreaterThanEqual = 50L;
        filter.sizeBytesLessThanEqual = 100L;
        filter.archivedTimeAfterEqual = 50L;
        filter.archivedTimeBeforeEqual = 100L;
        assertEquals(expected, filter.test(log(lastModified, size)));
    }
}
