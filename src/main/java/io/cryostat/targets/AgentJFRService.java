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
package io.cryostat.targets;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IDescribedMap;
import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.events.IEventTypeID;
import org.openjdk.jmc.flightrecorder.configuration.internal.DefaultValueMap;
import org.openjdk.jmc.rjmx.ConnectionException;
import org.openjdk.jmc.rjmx.ServiceNotAvailableException;
import org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException;
import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.core.EventOptionsBuilder.EventOptionException;
import io.cryostat.core.EventOptionsBuilder.EventTypeException;
import io.cryostat.core.net.CryostatFlightRecorderService;
import io.cryostat.core.templates.TemplateType;

import io.vertx.mutiny.ext.web.client.WebClient;

class AgentJFRService implements CryostatFlightRecorderService {

    private final WebClient webClient;

    AgentJFRService(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public IDescribedMap<EventOptionID> getDefaultEventOptions() {
        return new DefaultValueMap<>(Map.of());
    }

    @Override
    public IDescribedMap<String> getDefaultRecordingOptions() {
        return new DefaultValueMap<>(Map.of());
    }

    @Override
    public String getVersion() {
        return "agent"; // TODO
    }

    @Override
    public void close(IRecordingDescriptor descriptor) throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public void enable() throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public Collection<? extends IEventTypeInfo> getAvailableEventTypes()
            throws FlightRecorderException {
        return List.of();
    }

    @Override
    public Map<String, IOptionDescriptor<?>> getAvailableRecordingOptions()
            throws FlightRecorderException {
        return Map.of();
    }

    @Override
    public List<IRecordingDescriptor> getAvailableRecordings() throws FlightRecorderException {
        // TODO Auto-generated method stub
        return List.of();
    }

    @Override
    public IConstrainedMap<EventOptionID> getCurrentEventTypeSettings()
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        return new DefaultValueMap<>(Map.of());
    }

    @Override
    public IConstrainedMap<EventOptionID> getEventSettings(IRecordingDescriptor arg0)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        return new DefaultValueMap<>(Map.of());
    }

    @Override
    public Map<? extends IEventTypeID, ? extends IEventTypeInfo> getEventTypeInfoMapByID()
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        return Map.of();
    }

    @Override
    public IConstrainedMap<String> getRecordingOptions(IRecordingDescriptor arg0)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        return new DefaultValueMap<>(Map.of());
    }

    @Override
    public List<String> getServerTemplates() throws FlightRecorderException {
        // TODO Auto-generated method stub
        return List.of();
    }

    @Override
    public IRecordingDescriptor getSnapshotRecording() throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public IRecordingDescriptor getUpdatedRecordingDescription(IRecordingDescriptor arg0)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public boolean isEnabled() {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public InputStream openStream(IRecordingDescriptor arg0, boolean arg1)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public InputStream openStream(IRecordingDescriptor arg0, IQuantity arg1, boolean arg2)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public InputStream openStream(
            IRecordingDescriptor arg0, IQuantity arg1, IQuantity arg2, boolean arg3)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public IRecordingDescriptor start(
            IConstrainedMap<String> arg0, IConstrainedMap<EventOptionID> arg1)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public void stop(IRecordingDescriptor arg0) throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public void updateEventOptions(IRecordingDescriptor arg0, IConstrainedMap<EventOptionID> arg1)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    @Override
    public void updateRecordingOptions(IRecordingDescriptor arg0, IConstrainedMap<String> arg1)
            throws FlightRecorderException {
        // TODO Auto-generated method stub
        throw new UnimplementedException();
    }

    public static class UnimplementedException extends IllegalStateException {}

    @Override
    public IRecordingDescriptor start(IConstrainedMap<String> arg0, String arg1, TemplateType arg2)
            throws io.cryostat.core.FlightRecorderException,
                    FlightRecorderException,
                    ConnectionException,
                    IOException,
                    FlightRecorderException,
                    ServiceNotAvailableException,
                    QuantityConversionException,
                    EventOptionException,
                    EventTypeException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'start'");
    }
}
