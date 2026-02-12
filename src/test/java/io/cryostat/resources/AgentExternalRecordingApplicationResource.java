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
package io.cryostat.resources;

import java.util.HashMap;
import java.util.Map;

/**
 * Test resource that starts an agent container with a pre-started JFR recording. This allows
 * testing of external recording detection - recordings that were not created by Cryostat but are
 * discovered on the target JVM.
 */
public class AgentExternalRecordingApplicationResource extends AgentApplicationResource {

    public static final String RECORDING_NAME = "external-test-recording";
    public static final int RECORDING_DURATION_SECONDS = 120;

    @Override
    protected Map<String, String> getEnvMap() {
        Map<String, String> envMap = new HashMap<>(super.getEnvMap());

        String baseJavaOpts = envMap.get("JAVA_OPTS_APPEND");
        String recordingOpts =
                String.format(
                        """
                        -XX:StartFlightRecording=name=%s,settings=profile,duration=%ds
                        """,
                        RECORDING_NAME, RECORDING_DURATION_SECONDS);

        envMap.put(
                "JAVA_OPTS_APPEND",
                (baseJavaOpts + " " + recordingOpts).replace("\n", " ").strip());

        return envMap;
    }
}
