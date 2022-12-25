/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
import * as React from 'react';
import { Button, Split, SplitItem, Stack, StackItem, Text, Tooltip, ValidatedOptions } from '@patternfly/react-core';
import { ServiceContext } from '@app/Shared/Services/Services';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import {
  ActiveRecording,
  ArchivedRecording,
  Recording,
  RecordingDirectory,
  UPLOADS_SUBDIRECTORY,
} from '@app/Shared/Services/Api.service';
import { includesLabel, parseLabels, RecordingLabel } from './RecordingLabel';
import { combineLatest, concatMap, filter, first, forkJoin, map, merge, Observable, of } from 'rxjs';
import { LabelCell } from '@app/RecordingMetadata/LabelCell';
import { RecordingLabelFields } from './RecordingLabelFields';
import { HelpIcon } from '@patternfly/react-icons';
import { NO_TARGET } from '@app/Shared/Services/Target.service';
import { NotificationCategory } from '@app/Shared/Services/NotificationChannel.service';
import { hashCode } from '@app/utils/utils';
import { uploadAsTarget } from '@app/Archives/Archives';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';

export interface BulkEditLabelsProps {
  isTargetRecording: boolean;
  isUploadsTable?: boolean;
  checkedIndices: number[];
  directory?: RecordingDirectory;
  directoryRecordings?: ArchivedRecording[];
}

export const BulkEditLabels: React.FunctionComponent<BulkEditLabelsProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const [recordings, setRecordings] = React.useState([] as Recording[]);
  const [editing, setEditing] = React.useState(false);
  const [commonLabels, setCommonLabels] = React.useState([] as RecordingLabel[]);
  const [savedCommonLabels, setSavedCommonLabels] = React.useState([] as RecordingLabel[]);
  const [valid, setValid] = React.useState(ValidatedOptions.default);
  const [loading, setLoading] = React.useState(false);
  const addSubscription = useSubscriptions();

  const getIdxFromRecording = React.useCallback(
    (r: Recording): number => (props.isTargetRecording ? (r as ActiveRecording).id : hashCode(r.name)),
    [hashCode, props.isTargetRecording]
  );

  const handlePostUpdate = React.useCallback(() => {
    setEditing(false);
    setLoading(false);
  }, [setLoading, setEditing]);

  const handleUpdateLabels = React.useCallback(() => {
    setLoading(true);
    const tasks: Observable<any>[] = [];
    const toDelete = savedCommonLabels.filter((label) => !includesLabel(commonLabels, label));

    recordings.forEach((r: Recording) => {
      const idx = getIdxFromRecording(r);
      if (props.checkedIndices.includes(idx)) {
        let updatedLabels = [...parseLabels(r.metadata.labels), ...commonLabels];
        updatedLabels = updatedLabels.filter((label) => {
          return !includesLabel(toDelete, label);
        });
        if (props.directory) {
          tasks.push(
            context.api.postRecordingMetadataFromPath(props.directory.jvmId, r.name, updatedLabels).pipe(first())
          );
        }
        if (props.isTargetRecording) {
          tasks.push(context.api.postTargetRecordingMetadata(r.name, updatedLabels).pipe(first()));
        } else if (props.isUploadsTable) {
          tasks.push(context.api.postUploadedRecordingMetadata(r.name, updatedLabels).pipe(first()));
        } else {
          tasks.push(context.api.postRecordingMetadata(r.name, updatedLabels).pipe(first()));
        }
      }
    });
    addSubscription(
      forkJoin(tasks).subscribe({
        next: handlePostUpdate,
        error: handlePostUpdate,
      })
    );
  }, [
    addSubscription,
    recordings,
    props.checkedIndices,
    props.isTargetRecording,
    props.isUploadsTable,
    props.directory,
    props.directoryRecordings,
    editing,
    commonLabels,
    savedCommonLabels,
    parseLabels,
    context.api,
    handlePostUpdate,
  ]);

  const handleEditLabels = React.useCallback(() => {
    setEditing(true);
  }, [setEditing]);

  const handleCancel = React.useCallback(() => {
    setEditing(false);
    setCommonLabels(savedCommonLabels);
  }, [setEditing, setCommonLabels, savedCommonLabels]);

  const updateCommonLabels = React.useCallback(
    (setLabels: (l: RecordingLabel[]) => void) => {
      let allRecordingLabels = [] as RecordingLabel[][];

      recordings.forEach((r: Recording) => {
        const idx = getIdxFromRecording(r);
        if (props.checkedIndices.includes(idx)) {
          allRecordingLabels.push(parseLabels(r.metadata.labels));
        }
      });

      const updatedCommonLabels =
        allRecordingLabels.length > 0
          ? allRecordingLabels.reduce(
              (prev, curr) => prev.filter((label) => includesLabel(curr, label)),
              allRecordingLabels[0]
            )
          : [];

      setLabels(updatedCommonLabels);
    },
    [recordings, props.checkedIndices]
  );

  const refreshRecordingList = React.useCallback(() => {
    let observable: Observable<Recording[]>;
    if (props.directoryRecordings) {
      observable = of(props.directoryRecordings);
    } else if (props.isTargetRecording) {
      observable = context.target.target().pipe(
        filter((target) => target !== NO_TARGET),
        concatMap((target) =>
          context.api.doGet<ActiveRecording[]>(`targets/${encodeURIComponent(target.connectUrl)}/recordings`)
        ),
        first()
      );
    } else {
      observable = props.isUploadsTable
        ? context.api
            .graphql<any>(
              `query GetUploadedRecordings($filter: ArchivedRecordingFilterInput) {
                archivedRecordings(filter: $filter) {
                  data {
                    name
                    downloadUrl
                    reportUrl
                    metadata {
                      labels
                    }
                  }
                }
              }`,
              { filter: { sourceTarget: UPLOADS_SUBDIRECTORY } }
            )
            .pipe(
              map((v) => v.data.archivedRecordings.data as ArchivedRecording[]),
              first()
            )
        : context.target.target().pipe(
            filter((target) => target !== NO_TARGET),
            concatMap((target) =>
              context.api.graphql<any>(
                `query ActiveRecordingsForTarget($connectUrl: String) {
                targetNodes(filter: { name: $connectUrl }) {
                  recordings {
                    archived {
                      data {
                        name
                        downloadUrl
                        reportUrl
                        metadata {
                          labels
                        }
                      }
                    }
                  }
                }
              }`,
                { connectUrl: target.connectUrl }
              )
            ),
            map((v) => v.data.targetNodes[0].recordings.archived.data as ArchivedRecording[]),
            first()
          );
    }

    addSubscription(observable.subscribe((value) => setRecordings(value)));
  }, [
    addSubscription,
    props.isTargetRecording,
    props.isUploadsTable,
    props.directoryRecordings,
    context,
    context.target,
    context.api,
    setRecordings,
  ]);

  const saveButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Saving',
        spinnerAriaLabel: 'saving-recording-labels',
        isLoading: loading,
      } as LoadingPropsType),
    [loading]
  );

  React.useEffect(() => {
    addSubscription(context.target.target().subscribe(refreshRecordingList));
  }, [addSubscription, context, context.target, refreshRecordingList]);

  // Depends only on RecordingMetadataUpdated notifications
  // since updates on list of recordings will mount a completely new BulkEditLabels.
  React.useEffect(() => {
    addSubscription(
      combineLatest([
        props.isUploadsTable ? of(uploadAsTarget) : context.target.target(),
        context.notificationChannel.messages(NotificationCategory.RecordingMetadataUpdated),
      ]).subscribe((parts) => {
        const currentTarget = parts[0];
        const event = parts[1];
        if (currentTarget.connectUrl != event.message.target) {
          return;
        }
        setRecordings((old) =>
          old.map((o) =>
            o.name == event.message.recordingName ? { ...o, metadata: { labels: event.message.metadata.labels } } : o
          )
        );
      })
    );
  }, [addSubscription, context.notificationChannel, setRecordings]);

  React.useEffect(() => {
    updateCommonLabels(setCommonLabels);
    updateCommonLabels(setSavedCommonLabels);

    if (!recordings.length && editing) {
      setEditing(false);
    }
  }, [recordings, setCommonLabels, setSavedCommonLabels, updateCommonLabels, setEditing]);

  React.useEffect(() => {
    if (!props.checkedIndices.length) {
      setEditing(false);
    }
  }, [props.checkedIndices, setEditing]);

  return (
    <>
      <Stack hasGutter>
        <StackItem>
          <Split hasGutter>
            <SplitItem>
              <Text>Edit Recording Labels</Text>
            </SplitItem>
            <SplitItem>
              <Tooltip
                content={
                  <div>
                    Labels present on all selected recordings will appear here. Editing the labels will affect ALL
                    selected recordings.
                  </div>
                }
              >
                <HelpIcon noVerticalAlign />
              </Tooltip>
            </SplitItem>
          </Split>
        </StackItem>
        <StackItem>
          <LabelCell target="" labels={savedCommonLabels} />
        </StackItem>
        <StackItem>
          {editing ? (
            <>
              <RecordingLabelFields
                labels={commonLabels}
                setLabels={setCommonLabels}
                setValid={setValid}
                isDisabled={loading}
              />
              <Split hasGutter>
                <SplitItem>
                  <Button
                    variant="primary"
                    onClick={handleUpdateLabels}
                    isDisabled={valid != ValidatedOptions.success || loading}
                    {...saveButtonLoadingProps}
                  >
                    {loading ? 'Saving' : 'Save'}
                  </Button>
                </SplitItem>
                <SplitItem>
                  <Button variant="secondary" onClick={handleCancel} isDisabled={loading}>
                    Cancel
                  </Button>
                </SplitItem>
              </Split>
            </>
          ) : (
            <Button
              key="edit labels"
              aria-label="Edit Labels"
              variant="secondary"
              onClick={handleEditLabels}
              isDisabled={!props.checkedIndices.length}
            >
              Edit
            </Button>
          )}
        </StackItem>
      </Stack>
    </>
  );
};
