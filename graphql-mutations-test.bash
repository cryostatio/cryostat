#!/usr/bin/env bash

set -e

doQuery() {
  http --follow -v --auth=user:pass :8080/api/v3/graphql query="$1"
}

queryArchives() {
  doQuery '
    query queryArchives {
      targetNodes {
        name
        target {
          connectUrl
          archivedRecordings {
            aggregate {
              count
              size
            }
            data {
              name
            }
          }
        }
      }
    }
  '
}

createRecording() {
  doQuery '
    mutation createRecording {
      createRecording(nodes: { name: "Podman" }, recording: {
        name: "test",
        template: "Profiling",
        templateType: "TARGET"
      }) {
        name
      }
    }
  '
}

listRecordings() {
  doQuery '
    query listRecordings {
      environmentNodes(filter: { name: "Podman" }) {
        name
        descendantTargets {
          name
          target {
            activeRecordings {
              aggregate {
                count
              }
              data {
                name
              }
            }
          }
        }
      }
    }
  '
}

doArchive() {
  doQuery '
    mutation archiveRecording {
      archiveRecording(nodes: { name: "Podman" }, recordings: { name: "test" }) {
        name
      }
    }
  '
}

deleteRecordings() {
  doQuery '
    mutation deleteRecording {
      deleteRecording(nodes: { name: "Podman" }, recordings: { name: "test" }) {
        name
      }
    }
  '
}

queryArchives
sleep 3
createRecording
sleep 2
listRecordings
sleep 10
doArchive
sleep 2
queryArchives
sleep 5
deleteRecordings
