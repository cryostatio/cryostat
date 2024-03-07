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
          recordings {
            archived {
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
    }
  '
}

createRecording() {
  doQuery '
    query createRecording {
      environmentNodes(filter: { name: "Podman" }) {
        name
        descendantTargets {
          name
          target {
            doStartRecording(recording: {
              name: "test",
              template: "Profiling",
              templateType: "TARGET"
            }) {
              name
            }
          }
        }
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
            recordings {
              active {
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
    }
  '
}

doArchive() {
  doQuery '
    query doArchive {
      environmentNodes(filter: { name: "Podman" }) {
        name
        descendantTargets {
          name
          target {
            recordings {
              active(filter: { name: "test" }) {
                data {
                  name
                  doArchive {
                    name
                  }
                }
              }
            }
          }
        }
      }
    }
  '
}

deleteRecordings() {
  doQuery '
    query deleteRecordings {
      environmentNodes(filter: { name: "Podman" }) {
        name
        descendantTargets {
          name
          target {
            recordings {
              active(filter: { name: "test" }) {
                data {
                  name
                  doDelete {
                    name
                  }
                }
              }
            }
          }
        }
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
