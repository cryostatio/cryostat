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

import java.math.BigInteger;
import java.util.*;

import io.cryostat.discovery.DiscoveryNode;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Data model classes for GraphQL test responses */
public class GraphQLTestModels {

    public static class KeyValue {
        private String key;
        private String value;

        public KeyValue() {}

        public KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            KeyValue keyValue = (KeyValue) o;
            return Objects.equals(key, keyValue.key) && Objects.equals(value, keyValue.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }

        @Override
        public String toString() {
            return "KeyValue{" + "key='" + key + '\'' + ", value='" + value + '\'' + '}';
        }
    }

    public static class Annotations {
        private List<KeyValue> cryostat;
        private List<KeyValue> platform;

        public List<KeyValue> getCryostat() {
            return cryostat;
        }

        public void setCryostat(List<KeyValue> cryostat) {
            this.cryostat = cryostat;
        }

        public List<KeyValue> getPlatform() {
            return platform;
        }

        public void setPlatform(List<KeyValue> platform) {
            this.platform = platform;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Annotations that = (Annotations) o;
            return Objects.equals(cryostat, that.cryostat)
                    && Objects.equals(platform, that.platform);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cryostat, platform);
        }

        @Override
        public String toString() {
            return "Annotations{" + "cryostat=" + cryostat + ", platform=" + platform + '}';
        }
    }

    public static class RecordingMetadata {
        List<KeyValue> labels;

        public List<KeyValue> getLabels() {
            return labels;
        }

        public void setLabels(List<KeyValue> labels) {
            this.labels = labels;
        }

        public static RecordingMetadata of(Map<String, String> of) {
            var list = new ArrayList<KeyValue>();
            of.forEach((k, v) -> list.add(new KeyValue(k, v)));
            var rm = new RecordingMetadata();
            rm.labels = list;
            return rm;
        }

        @Override
        public int hashCode() {
            return Objects.hash(labels);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            RecordingMetadata other = (RecordingMetadata) obj;
            if (labels == null && other.labels == null) {
                return true;
            }
            if (labels == null || other.labels == null) {
                return false;
            }
            return labels.size() == other.labels.size()
                    && (Objects.equals(new HashSet<>(labels), new HashSet<>(other.labels)));
        }

        @Override
        public String toString() {
            return "RecordingMetadata [labels=" + labels + "]";
        }
    }

    public static class Label {
        private String key;
        private String value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Label label = (Label) o;
            return Objects.equals(key, label.key) && Objects.equals(value, label.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
    }

    public static class EnvironmentNodesResponse {
        private Data data;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        public static class Data {
            private List<DiscoveryNode> environmentNodes;

            public List<DiscoveryNode> getEnvironmentNodes() {
                return environmentNodes;
            }

            public void setEnvironmentNodes(List<DiscoveryNode> environmentNodes) {
                this.environmentNodes = environmentNodes;
            }

            @Override
            public String toString() {
                return "Data{environmentNodes=" + environmentNodes + '}';
            }
        }

        @Override
        public String toString() {
            return "EnvironmentNodesResponse [data=" + data + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            EnvironmentNodesResponse other = (EnvironmentNodesResponse) obj;
            return Objects.equals(data, other.data);
        }
    }

    public static class Target {
        String alias;
        String connectUrl;
        String jvmId;
        Annotations annotations;
        Recordings recordings;

        public String getAlias() {
            return alias;
        }

        public String getJvmId() {
            return jvmId;
        }

        public void setJvmId(String jvmId) {
            this.jvmId = jvmId;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getConnectUrl() {
            return connectUrl;
        }

        public void setConnectUrl(String connectUrl) {
            this.connectUrl = connectUrl;
        }

        public Annotations getAnnotations() {
            return annotations;
        }

        public void setAnnotations(Annotations annotations) {
            this.annotations = annotations;
        }

        public Recordings getRecordings() {
            return recordings;
        }

        public void setRecordings(Recordings recordings) {
            this.recordings = recordings;
        }

        @Override
        public int hashCode() {
            return Objects.hash(alias, connectUrl, annotations, recordings);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            Target other = (Target) obj;
            return Objects.equals(alias, other.alias)
                    && Objects.equals(connectUrl, other.connectUrl)
                    && Objects.equals(annotations, other.annotations)
                    && Objects.equals(recordings, other.recordings);
        }

        @Override
        public String toString() {
            return "Target [alias="
                    + alias
                    + ", connectUrl="
                    + connectUrl
                    + ", jvmId="
                    + jvmId
                    + ", annotations="
                    + annotations
                    + ", recordings="
                    + recordings
                    + "]";
        }
    }

    public static class TargetNode {
        private String name;
        private BigInteger id;
        private String nodeType;
        private List<Label> labels;
        private Target target;
        private Recordings recordings;
        private ActiveRecording doStartRecording;

        public String getName() {
            return name;
        }

        public BigInteger getId() {
            return id;
        }

        public void setId(BigInteger id) {
            this.id = id;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNodeType() {
            return nodeType;
        }

        public void setNodeType(String nodeType) {
            this.nodeType = nodeType;
        }

        public List<Label> getLabels() {
            return labels;
        }

        public void setLabels(List<Label> labels) {
            this.labels = labels;
        }

        public Target getTarget() {
            return target;
        }

        public void setTarget(Target target) {
            this.target = target;
        }

        public Recordings getRecordings() {
            return recordings;
        }

        public void setRecordings(Recordings recordings) {
            this.recordings = recordings;
        }

        public ActiveRecording getDoStartRecording() {
            return doStartRecording;
        }

        public void setDoStartRecording(ActiveRecording doStartRecording) {
            this.doStartRecording = doStartRecording;
        }

        @Override
        public String toString() {
            return "TargetNode [doStartRecording="
                    + doStartRecording
                    + ", labels="
                    + labels
                    + ", name="
                    + name
                    + ", id="
                    + id
                    + ", nodeType="
                    + nodeType
                    + ", target="
                    + target
                    + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(doStartRecording, labels, name, id, nodeType, recordings, target);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TargetNode other = (TargetNode) obj;
            return Objects.equals(doStartRecording, other.doStartRecording)
                    && Objects.equals(labels, other.labels)
                    && Objects.equals(name, other.name)
                    && Objects.equals(id, other.id)
                    && Objects.equals(nodeType, other.nodeType)
                    && Objects.equals(target, other.target);
        }
    }

    public static class TargetNodesQueryResponse {
        public Data data;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            TargetNodesQueryResponse other = (TargetNodesQueryResponse) obj;
            return Objects.equals(data, other.data);
        }

        @Override
        public String toString() {
            return "TargetNodesQueryResponse{" + "data=" + data + '}';
        }

        public static class Data {
            public List<TargetNode> targetNodes;

            public List<TargetNode> getTargetNodes() {
                return targetNodes;
            }

            public void setTargetNodes(List<TargetNode> targetNodes) {
                this.targetNodes = targetNodes;
            }

            @Override
            public String toString() {
                return "Data{" + "targetNodes=" + targetNodes + '}';
            }

            @Override
            public int hashCode() {
                return Objects.hash(targetNodes);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (obj == null) return false;
                if (getClass() != obj.getClass()) return false;
                Data other = (Data) obj;
                return Objects.equals(targetNodes, other.targetNodes);
            }
        }
    }

    public static class ActiveRecording {
        public long id;
        public long remoteId;
        public String name;
        public String reportUrl;
        public String downloadUrl;
        public RecordingMetadata metadata;
        public String state;
        public long startTime;
        public long duration;
        public boolean continuous;
        public boolean toDisk;
        public long maxSize;
        public long maxAge;
        public List<KeyValue> labels;

        @Override
        public int hashCode() {
            return Objects.hash(
                    id,
                    remoteId,
                    name,
                    reportUrl,
                    downloadUrl,
                    metadata,
                    state,
                    startTime,
                    duration,
                    continuous,
                    toDisk,
                    maxSize,
                    maxAge,
                    labels);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            ActiveRecording other = (ActiveRecording) obj;
            return id == other.id
                    && remoteId == other.remoteId
                    && duration == other.duration
                    && continuous == other.continuous
                    && toDisk == other.toDisk
                    && maxSize == other.maxSize
                    && maxAge == other.maxAge
                    && startTime == other.startTime
                    && Objects.equals(name, other.name)
                    && Objects.equals(reportUrl, other.reportUrl)
                    && Objects.equals(downloadUrl, other.downloadUrl)
                    && Objects.equals(metadata, other.metadata)
                    && Objects.equals(state, other.state)
                    && Objects.equals(labels, other.labels);
        }

        @Override
        public String toString() {
            return "ActiveRecording{"
                    + "id="
                    + id
                    + ", remoteId="
                    + remoteId
                    + ", name='"
                    + name
                    + '\''
                    + ", state='"
                    + state
                    + '\''
                    + ", duration="
                    + duration
                    + ", continuous="
                    + continuous
                    + '}';
        }
    }

    public static class Recordings {
        private ActiveRecordings active;
        private ArchivedRecordings archived;

        public ActiveRecordings getActive() {
            return active;
        }

        public void setActive(ActiveRecordings active) {
            this.active = active;
        }

        public ArchivedRecordings getArchived() {
            return archived;
        }

        public void setArchived(ArchivedRecordings archived) {
            this.archived = archived;
        }

        @Override
        public String toString() {
            return "Recordings [active=" + active + ", archived=" + archived + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(active, archived);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            Recordings other = (Recordings) obj;
            return Objects.equals(active, other.active) && Objects.equals(archived, other.archived);
        }
    }

    public static class ActiveRecordings {
        private List<ActiveRecording> data;

        public List<ActiveRecording> getData() {
            return data;
        }

        public void setData(List<ActiveRecording> data) {
            this.data = data;
        }
    }

    public static class ArchivedRecordings {
        private List<ArchivedRecording> data;

        public List<ArchivedRecording> getData() {
            return data;
        }

        public void setData(List<ArchivedRecording> data) {
            this.data = data;
        }
    }

    public static class ArchivedRecording {
        public String name;
        public String reportUrl;
        public String downloadUrl;
        public RecordingMetadata metadata;
        public long size;
        public long archivedTime;
        public List<KeyValue> labels;
    }

    public static class CreateRecordingMutationResponse {
        @JsonProperty("data")
        public CreateRecording data;

        public CreateRecording getData() {
            return data;
        }

        public void setData(CreateRecording data) {
            this.data = data;
        }
    }

    public static class CreateRecording {
        @JsonProperty("createRecording")
        public List<ActiveRecording> recordings;

        public List<ActiveRecording> getRecordings() {
            return recordings;
        }

        public void setData(List<ActiveRecording> recordings) {
            this.recordings = recordings;
        }
    }
}
