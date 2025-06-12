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

import java.io.InputStream;
import java.time.Duration;

import org.openjdk.jmc.flightrecorder.configuration.IRecordingDescriptor;

import io.cryostat.ProgressInputStream;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Creates {@link java.io.InputStream}s that pipe Flight Recording data from a remote target JVM.
 * This class ensures that the cached target connection is continuously marked as in use, so that
 * the {@link io.cryostat.target.TargetConnectionManager} does not prematurely evict and close the
 * connection while streaming is still underway.
 */
@ApplicationScoped
public class RemoteRecordingInputStreamFactory {

    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingHelper recordingHelper;

    public ProgressInputStream open(ActiveRecording recording, Duration timeout) throws Exception {
        return connectionManager.executeConnectedTask(
                recording.target,
                conn -> {
                    IRecordingDescriptor desc =
                            recordingHelper.getDescriptor(conn, recording).orElseThrow();
                    return openDirect(conn, recording.target, desc);
                },
                timeout);
    }

    public ProgressInputStream openDirect(
            JFRConnection conn, Target target, IRecordingDescriptor desc) throws Exception {
        InputStream bareStream = conn.getService().openStream(desc, false);
        return new ProgressInputStream(
                bareStream, n -> connectionManager.markConnectionInUse(target));
    }
}
