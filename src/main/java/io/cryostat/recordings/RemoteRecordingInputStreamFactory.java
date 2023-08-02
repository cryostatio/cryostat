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

import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.ProgressInputStream;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RemoteRecordingInputStreamFactory {

    @Inject TargetConnectionManager connectionManager;

    public ProgressInputStream open(Target target, ActiveRecording activeRecording)
            throws Exception {
        InputStream bareStream =
                connectionManager.executeConnectedTask(
                        target,
                        conn -> {
                            IRecordingDescriptor desc =
                                    RecordingHelper.getDescriptor(conn, activeRecording)
                                            .orElseThrow();
                            return conn.getService().openStream(desc, false);
                        });
        return new ProgressInputStream(
                bareStream, n -> connectionManager.markConnectionInUse(target));
    }
}
