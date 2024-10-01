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
package io.cryostat.recordings;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.IFlightRecorderService;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;

import io.cryostat.core.RecordingOptionsCustomizer;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v4/targets/{targetId}/recordingOptions")
public class RecordingOptions {

    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject RecordingOptionsCustomizerFactory recordingOptionsCustomizerFactory;
    @Inject Logger logger;

    @GET
    @Blocking
    @RolesAllowed("read")
    public Map<String, Object> getRecordingOptions(@RestPath long targetId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                connection -> {
                    RecordingOptionsBuilder builder = recordingOptionsBuilderFactory.create(target);
                    return getRecordingOptions(connection.getService(), builder);
                });
    }

    @PATCH
    @Blocking
    @RolesAllowed("read")
    @SuppressFBWarnings(
            value = "UC_USELESS_OBJECT",
            justification = "SpotBugs thinks the options map is unused, but it is used")
    public Map<String, Object> patchRecordingOptions(
            @RestPath long targetId,
            @RestForm String toDisk,
            @RestForm String maxAge,
            @RestForm String maxSize)
            throws Exception {
        final String unsetKeyword = "unset";

        Map<String, String> options = new HashMap<>();
        Pattern bool = Pattern.compile("true|false|" + unsetKeyword);
        if (toDisk != null) {
            Matcher m = bool.matcher(toDisk);
            if (!m.matches()) {
                throw new BadRequestException("Invalid options");
            }
            options.put("toDisk", toDisk);
        }
        if (maxAge != null) {
            if (unsetKeyword.equals(maxAge)) {
                options.put("maxAge", unsetKeyword);
            } else {
                try {
                    Long.parseLong(maxAge);
                    options.put("maxAge", maxAge);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("Invalid options");
                }
            }
        }
        if (maxSize != null) {
            if (unsetKeyword.equals(maxSize)) {
                options.put("maxSize", unsetKeyword);
            } else {
                try {
                    Long.parseLong(maxSize);
                    options.put("maxSize", maxSize);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("Invalid options");
                }
            }
        }
        Target target = Target.find("id", targetId).singleResult();
        for (var entry : options.entrySet()) {
            RecordingOptionsCustomizer.OptionKey optionKey =
                    RecordingOptionsCustomizer.OptionKey.fromOptionName(entry.getKey()).get();
            var recordingOptionsCustomizer = recordingOptionsCustomizerFactory.create(target);
            if (unsetKeyword.equals(entry.getValue())) {
                recordingOptionsCustomizer.unset(optionKey);
            } else {
                recordingOptionsCustomizer.set(optionKey, entry.getValue());
            }
        }

        return connectionManager.executeConnectedTask(
                target,
                connection -> {
                    var builder = recordingOptionsBuilderFactory.create(target);
                    return getRecordingOptions(connection.getService(), builder);
                });
    }

    private static Map<String, Object> getRecordingOptions(
            IFlightRecorderService service, RecordingOptionsBuilder builder) throws Exception {
        IConstrainedMap<String> recordingOptions = builder.build();

        Map<String, IOptionDescriptor<?>> targetRecordingOptions =
                service.getAvailableRecordingOptions();

        Map<String, Object> map = new HashMap<String, Object>();

        if (recordingOptions.get("toDisk") != null) {
            map.put("toDisk", recordingOptions.get("toDisk"));
        } else {
            map.put("toDisk", targetRecordingOptions.get("disk").getDefault());
        }

        map.put("maxAge", getNumericOption("maxAge", recordingOptions, targetRecordingOptions));
        map.put("maxSize", getNumericOption("maxSize", recordingOptions, targetRecordingOptions));

        return map;
    }

    private static Long getNumericOption(
            String name,
            IConstrainedMap<String> defaultOptions,
            Map<String, IOptionDescriptor<?>> targetOptions) {
        Object value;

        if (defaultOptions.get(name) != null) {
            value = defaultOptions.get(name);
        } else {
            value = targetOptions.get(name).getDefault();
        }

        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        return null;
    }
}
