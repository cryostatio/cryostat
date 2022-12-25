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

import { parseLabels } from '@app/RecordingMetadata/RecordingLabel';
import { ActiveRecording, RecordingState } from '@app/Shared/Services/Api.service';
import { NotificationCategory } from '@app/Shared/Services/NotificationChannel.service';
import { ServiceContext } from '@app/Shared/Services/Services';
import { NO_TARGET } from '@app/Shared/Services/Target.service';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import {
  Button,
  Checkbox,
  Drawer,
  DrawerContent,
  DrawerContentBody,
  Text,
  Toolbar,
  ToolbarContent,
  ToolbarGroup,
  ToolbarItem,
} from '@patternfly/react-core';
import { Tbody, Tr, Td, ExpandableRowContent } from '@patternfly/react-table';
import * as React from 'react';
import { useHistory, useRouteMatch } from 'react-router-dom';
import { combineLatest, forkJoin, merge, Observable } from 'rxjs';
import { concatMap, filter, first } from 'rxjs/operators';
import { LabelCell } from '../RecordingMetadata/LabelCell';
import { RecordingActions } from './RecordingActions';
import { RecordingLabelsPanel } from './RecordingLabelsPanel';
import { filterRecordings, RecordingFilters, RecordingFiltersCategories } from './RecordingFilters';
import { RecordingsTable } from './RecordingsTable';
import { ReportFrame } from './ReportFrame';
import { DeleteWarningModal } from '../Modal/DeleteWarningModal';
import { DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import { useDispatch, useSelector } from 'react-redux';
import {
  recordingAddFilterIntent,
  recordingDeleteFilterIntent,
  recordingAddTargetIntent,
  recordingDeleteCategoryFiltersIntent,
  recordingDeleteAllFiltersIntent,
  RootState,
  StateDispatch,
} from '@app/Shared/Redux/ReduxStore';
import { emptyActiveRecordingFilters, TargetRecordingFilters } from '@app/Shared/Redux/Filters/RecordingFilterSlice';
import { authFailMessage } from '@app/ErrorView/ErrorView';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import { UpdateFilterOptions } from '@app/Shared/Redux/Filters/Common';

export enum PanelContent {
  LABELS,
}
export interface ActiveRecordingsTableProps {
  archiveEnabled: boolean;
}

export const ActiveRecordingsTable: React.FunctionComponent<ActiveRecordingsTableProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const routerHistory = useHistory();
  const { url } = useRouteMatch();
  const addSubscription = useSubscriptions();
  const dispatch = useDispatch<StateDispatch>();

  const [targetConnectURL, setTargetConnectURL] = React.useState('');
  const [recordings, setRecordings] = React.useState([] as ActiveRecording[]);
  const [filteredRecordings, setFilteredRecordings] = React.useState([] as ActiveRecording[]);
  const [headerChecked, setHeaderChecked] = React.useState(false);
  const [checkedIndices, setCheckedIndices] = React.useState([] as number[]);
  const [expandedRows, setExpandedRows] = React.useState([] as string[]);
  const [showDetailsPanel, setShowDetailsPanel] = React.useState(false);
  const [panelContent, setPanelContent] = React.useState(PanelContent.LABELS);
  const [isLoading, setIsLoading] = React.useState(false);
  const [errorMessage, setErrorMessage] = React.useState('');
  const [actionLoadings, setActionLoadings] = React.useState<Record<ActiveActions, boolean>>({
    ARCHIVE: false,
    DELETE: false,
    STOP: false,
  });

  const targetRecordingFilters = useSelector((state: RootState) => {
    const filters = state.recordingFilters.list.filter(
      (targetFilter: TargetRecordingFilters) => targetFilter.target === targetConnectURL
    );
    return filters.length > 0 ? filters[0].active.filters : emptyActiveRecordingFilters;
  }) as RecordingFiltersCategories;

  const tableColumns: string[] = ['Name', 'Start Time', 'Duration', 'State', 'Labels'];

  const handleRowCheck = React.useCallback(
    (checked, index) => {
      if (checked) {
        setCheckedIndices((ci) => [...ci, index]);
      } else {
        setHeaderChecked(false);
        setCheckedIndices((ci) => ci.filter((v) => v !== index));
      }
    },
    [setCheckedIndices, setHeaderChecked]
  );

  const handleHeaderCheck = React.useCallback(
    (event, checked) => {
      setHeaderChecked(checked);
      setCheckedIndices(checked ? filteredRecordings.map((r) => r.id) : []);
    },
    [setHeaderChecked, setCheckedIndices, filteredRecordings]
  );

  const handleCreateRecording = React.useCallback(() => {
    routerHistory.push(`${url}/create`);
  }, [routerHistory]);

  const handleEditLabels = React.useCallback(() => {
    setShowDetailsPanel(true);
    setPanelContent(PanelContent.LABELS);
  }, [setShowDetailsPanel, setPanelContent]);

  const handleRecordings = React.useCallback(
    (recordings) => {
      setRecordings(recordings);
      setIsLoading(false);
      setErrorMessage('');
    },
    [setRecordings, setIsLoading, setErrorMessage]
  );

  const handleError = React.useCallback(
    (error) => {
      setIsLoading(false);
      setErrorMessage(error.message);
      setRecordings([]);
    },
    [setIsLoading, setErrorMessage, setRecordings]
  );

  const refreshRecordingList = React.useCallback(() => {
    setIsLoading(true);
    addSubscription(
      context.target
        .target()
        .pipe(
          filter((target) => target !== NO_TARGET),
          concatMap((target) =>
            context.api.doGet<ActiveRecording[]>(`targets/${encodeURIComponent(target.connectUrl)}/recordings`)
          ),
          first()
        )
        .subscribe({
          next: handleRecordings,
          error: handleError,
        })
    );
  }, [addSubscription, context, context.target, context.api, setIsLoading, handleRecordings, handleError]);

  React.useEffect(() => {
    addSubscription(
      context.target.target().subscribe((target) => {
        setTargetConnectURL(target.connectUrl);
        dispatch(recordingAddTargetIntent(target.connectUrl));
        refreshRecordingList();
      })
    );
  }, [
    addSubscription,
    context,
    context.target,
    refreshRecordingList,
    setTargetConnectURL,
    dispatch,
    recordingAddTargetIntent,
  ]);

  React.useEffect(() => {
    addSubscription(
      combineLatest([
        context.target.target(),
        merge(
          context.notificationChannel.messages(NotificationCategory.ActiveRecordingCreated),
          context.notificationChannel.messages(NotificationCategory.SnapshotCreated)
        ),
      ]).subscribe((parts) => {
        const currentTarget = parts[0];
        const event = parts[1];
        if (currentTarget.connectUrl != event.message.target) {
          return;
        }
        setRecordings((old) => old.concat([event.message.recording]));
      })
    );
  }, [addSubscription, context, context.notificationChannel, setRecordings]);

  React.useEffect(() => {
    addSubscription(
      combineLatest([
        context.target.target(),
        merge(
          context.notificationChannel.messages(NotificationCategory.ActiveRecordingDeleted),
          context.notificationChannel.messages(NotificationCategory.SnapshotDeleted)
        ),
      ]).subscribe((parts) => {
        const currentTarget = parts[0];
        const event = parts[1];
        if (currentTarget.connectUrl != event.message.target) {
          return;
        }

        setRecordings((old) => old.filter((r) => r.name !== event.message.recording.name));
        setCheckedIndices((old) => old.filter((idx) => idx !== event.message.recording.id));
      })
    );
  }, [addSubscription, context, context.notificationChannel, setRecordings, setCheckedIndices]);

  React.useEffect(() => {
    addSubscription(
      combineLatest([
        context.target.target(),
        context.notificationChannel.messages(NotificationCategory.ActiveRecordingStopped),
      ]).subscribe((parts) => {
        const currentTarget = parts[0];
        const event = parts[1];
        if (currentTarget.connectUrl != event.message.target) {
          return;
        }
        setRecordings((old) => {
          const updated = [...old];
          for (const r of updated) {
            if (r.name === event.message.recording.name) {
              r.state = RecordingState.STOPPED;
            }
          }
          return updated;
        });
      })
    );
  }, [addSubscription, context, context.notificationChannel, setRecordings]);

  React.useEffect(() => {
    addSubscription(
      context.target.authFailure().subscribe(() => {
        setErrorMessage(authFailMessage);
        setRecordings([]);
      })
    );
  }, [context, context.target, setErrorMessage, addSubscription, setRecordings]);

  React.useEffect(() => {
    addSubscription(
      combineLatest([
        context.target.target(),
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
  }, [addSubscription, context, context.notificationChannel, setRecordings]);

  React.useEffect(() => {
    setFilteredRecordings(filterRecordings(recordings, targetRecordingFilters));
  }, [recordings, targetRecordingFilters, setFilteredRecordings, filterRecordings]);

  React.useEffect(() => {
    setCheckedIndices((ci) => {
      const filteredRecordingIdx = new Set(filteredRecordings.map((r) => r.id));
      return ci.filter((idx) => filteredRecordingIdx.has(idx));
    });
  }, [filteredRecordings, setCheckedIndices]);

  React.useEffect(() => {
    setHeaderChecked(checkedIndices.length === filteredRecordings.length);
  }, [setHeaderChecked, checkedIndices]);

  React.useEffect(() => {
    if (!context.settings.autoRefreshEnabled()) {
      return;
    }
    const id = window.setInterval(
      () => refreshRecordingList(),
      context.settings.autoRefreshPeriod() * context.settings.autoRefreshUnits()
    );
    return () => window.clearInterval(id);
  }, [refreshRecordingList, context, context.settings]);

  const handlePostActions = React.useCallback(
    (action: ActiveActions) => {
      setActionLoadings((old) => {
        const newActionLoadings = { ...old };
        newActionLoadings[action] = false;
        return newActionLoadings;
      });
    },
    [setActionLoadings]
  );

  const handleArchiveRecordings = React.useCallback(() => {
    setActionLoadings((old) => ({ ...old, ARCHIVE: true }));
    const tasks: Observable<boolean>[] = [];
    filteredRecordings.forEach((r: ActiveRecording) => {
      if (checkedIndices.includes(r.id)) {
        handleRowCheck(false, r.id);
        tasks.push(context.api.archiveRecording(r.name).pipe(first()));
      }
    });
    addSubscription(
      forkJoin(tasks).subscribe({
        next: () => handlePostActions('ARCHIVE'),
        error: () => handlePostActions('ARCHIVE'),
      })
    );
  }, [
    filteredRecordings,
    checkedIndices,
    handleRowCheck,
    context.api,
    addSubscription,
    setActionLoadings,
    handlePostActions,
  ]);

  const handleStopRecordings = React.useCallback(() => {
    setActionLoadings((old) => ({ ...old, STOP: true }));
    const tasks: Observable<boolean>[] = [];
    filteredRecordings.forEach((r: ActiveRecording) => {
      if (checkedIndices.includes(r.id)) {
        handleRowCheck(false, r.id);
        if (r.state === RecordingState.RUNNING || r.state === RecordingState.STARTING) {
          tasks.push(context.api.stopRecording(r.name).pipe(first()));
        }
      }
    });
    addSubscription(
      forkJoin(tasks).subscribe({
        next: () => handlePostActions('STOP'),
        error: () => handlePostActions('STOP'),
      })
    );
  }, [
    filteredRecordings,
    checkedIndices,
    handleRowCheck,
    context.api,
    addSubscription,
    setActionLoadings,
    handlePostActions,
  ]);

  const handleDeleteRecordings = React.useCallback(() => {
    setActionLoadings((old) => ({ ...old, DELETE: true }));
    const tasks: Observable<{}>[] = [];
    filteredRecordings.forEach((r: ActiveRecording) => {
      if (checkedIndices.includes(r.id)) {
        context.reports.delete(r);
        tasks.push(context.api.deleteRecording(r.name).pipe(first()));
      }
    });
    addSubscription(
      forkJoin(tasks).subscribe({
        next: () => handlePostActions('DELETE'),
        error: () => handlePostActions('DELETE'),
      })
    );
  }, [
    filteredRecordings,
    checkedIndices,
    context.reports,
    context.api,
    addSubscription,
    setActionLoadings,
    handlePostActions,
  ]);

  const handleClearFilters = React.useCallback(() => {
    dispatch(recordingDeleteAllFiltersIntent(targetConnectURL, false));
  }, [dispatch, recordingDeleteAllFiltersIntent, targetConnectURL]);

  const updateFilters = React.useCallback(
    (target, { filterValue, filterKey, deleted = false, deleteOptions }: UpdateFilterOptions) => {
      if (deleted) {
        if (deleteOptions && deleteOptions.all) {
          dispatch(recordingDeleteCategoryFiltersIntent(target, filterKey, false));
        } else {
          dispatch(recordingDeleteFilterIntent(target, filterKey, filterValue, false));
        }
      } else {
        dispatch(recordingAddFilterIntent(target, filterKey, filterValue, false));
      }
    },
    [dispatch, recordingDeleteCategoryFiltersIntent, recordingDeleteFilterIntent, recordingAddFilterIntent]
  );

  const RecordingRow = (props) => {
    const parsedLabels = React.useMemo(() => {
      return parseLabels(props.recording.metadata.labels);
    }, [props.recording.metadata.labels]);

    const expandedRowId = React.useMemo(
      () => `active-table-row-${props.recording.name}-${props.recording.startTime}-exp`,
      [props.recording.name, props.recording.startTime]
    );

    const handleToggle = React.useCallback(() => toggleExpanded(expandedRowId), [toggleExpanded]);

    const isExpanded = React.useMemo(() => {
      return expandedRows.includes(expandedRowId);
    }, [expandedRows, expandedRowId]);

    const handleCheck = React.useCallback(
      (checked) => {
        handleRowCheck(checked, props.index);
      },
      [handleRowCheck, props.index]
    );

    const parentRow = React.useMemo(() => {
      const ISOTime = (props) => {
        const fmt = React.useMemo(() => new Date(props.timeStr).toISOString(), [props.timeStr]);
        return <span>{fmt}</span>;
      };

      const RecordingDuration = (props) => {
        const str = React.useMemo(
          () => (props.duration === 0 ? 'Continuous' : `${props.duration / 1000}s`),
          [props.duration]
        );
        return <span>{str}</span>;
      };

      return (
        <Tr key={`${props.index}_parent`}>
          <Td key={`active-table-row-${props.index}_0`}>
            <Checkbox
              name={`active-table-row-${props.index}-check`}
              onChange={handleCheck}
              isChecked={checkedIndices.includes(props.index)}
              id={`active-table-row-${props.index}-check`}
            />
          </Td>
          <Td
            key={`active-table-row-${props.index}_1`}
            id={`active-ex-toggle-${props.index}`}
            aria-controls={`active-ex-expand-${props.index}`}
            expand={{
              rowIndex: props.index,
              isExpanded: isExpanded,
              onToggle: handleToggle,
            }}
          />
          <Td key={`active-table-row-${props.index}_2`} dataLabel={tableColumns[0]}>
            {props.recording.name}
          </Td>
          <Td key={`active-table-row-${props.index}_3`} dataLabel={tableColumns[1]}>
            <ISOTime timeStr={props.recording.startTime} />
          </Td>
          <Td key={`active-table-row-${props.index}_4`} dataLabel={tableColumns[2]}>
            <RecordingDuration duration={props.recording.duration} />
          </Td>
          <Td key={`active-table-row-${props.index}_5`} dataLabel={tableColumns[3]}>
            {props.recording.state}
          </Td>
          <Td key={`active-table-row-${props.index}_6`} dataLabel={tableColumns[4]}>
            <LabelCell
              target={targetConnectURL}
              clickableOptions={{
                updateFilters: updateFilters,
                labelFilters: props.labelFilters,
              }}
              labels={parsedLabels}
            />
          </Td>
          <RecordingActions
            index={props.index}
            recording={props.recording}
            uploadFn={() => context.api.uploadActiveRecordingToGrafana(props.recording.name)}
          />
        </Tr>
      );
    }, [
      props.duration,
      props.index,
      props.recording,
      props.recording.duration,
      props.recording.name,
      props.recording.startTime,
      props.recording.state,
      props.timeStr,
      props.recording.metadata.labels,
      props.labelFilters,
      context.api,
      context.api.uploadActiveRecordingToGrafana,
      checkedIndices,
      handleCheck,
      handleToggle,
      updateFilters,
      isExpanded,
      tableColumns,
      parsedLabels,
      targetConnectURL,
    ]);

    const childRow = React.useMemo(() => {
      return (
        <Tr key={`${props.index}_child`} isExpanded={isExpanded}>
          <Td key={`active-ex-expand-${props.index}`} dataLabel={'Content Details'} colSpan={tableColumns.length + 3}>
            <ExpandableRowContent>
              <Text>Recording Options:</Text>
              <Text>
                toDisk = {String(props.recording.toDisk)} &emsp; maxAge = {props.recording.maxAge / 1000}s &emsp;
                maxSize = {props.recording.maxSize}B
              </Text>
              <br></br>
              <hr></hr>
              <br></br>
              <Text>Automated Analysis:</Text>
              <ReportFrame isExpanded={isExpanded} recording={props.recording} width="100%" height="640" />
            </ExpandableRowContent>
          </Td>
        </Tr>
      );
    }, [
      props.recording,
      props.recording.name,
      props.duration,
      props.index,
      isExpanded,
      tableColumns,
      props.recording.toDisk,
      props.recording.maxAge,
      props.recording.maxSize,
    ]);
    return (
      <Tbody key={props.index} isExpanded={isExpanded}>
        {parentRow}
        {childRow}
      </Tbody>
    );
  };

  const toggleExpanded = React.useCallback(
    (id: string) => {
      setExpandedRows((expandedRows) => {
        const idx = expandedRows.indexOf(id);
        return idx >= 0
          ? [...expandedRows.slice(0, idx), ...expandedRows.slice(idx + 1, expandedRows.length)]
          : [...expandedRows, id];
      });
    },
    [expandedRows, setExpandedRows]
  );

  const RecordingsToolbar = React.useMemo(
    () => (
      <ActiveRecordingsToolbar
        target={targetConnectURL}
        checkedIndices={checkedIndices}
        targetRecordingFilters={targetRecordingFilters}
        recordings={recordings}
        filteredRecordings={filteredRecordings}
        updateFilters={updateFilters}
        handleClearFilters={handleClearFilters}
        archiveEnabled={props.archiveEnabled}
        handleCreateRecording={handleCreateRecording}
        handleArchiveRecordings={handleArchiveRecordings}
        handleEditLabels={handleEditLabels}
        handleStopRecordings={handleStopRecordings}
        handleDeleteRecordings={handleDeleteRecordings}
        actionLoadings={actionLoadings}
      />
    ),
    [
      targetConnectURL,
      checkedIndices,
      targetRecordingFilters,
      recordings,
      filteredRecordings,
      updateFilters,
      handleClearFilters,
      props.archiveEnabled,
      handleCreateRecording,
      handleArchiveRecordings,
      handleEditLabels,
      handleStopRecordings,
      handleDeleteRecordings,
      actionLoadings,
    ]
  );

  const recordingRows = React.useMemo(() => {
    return filteredRecordings.map((r) => (
      <RecordingRow key={r.id} recording={r} labelFilters={targetRecordingFilters.Label} index={r.id} />
    ));
  }, [filteredRecordings, expandedRows, targetRecordingFilters, checkedIndices]);

  const LabelsPanel = React.useMemo(
    () => (
      <RecordingLabelsPanel
        setShowPanel={setShowDetailsPanel}
        isTargetRecording={true}
        checkedIndices={checkedIndices}
      />
    ),
    [checkedIndices, setShowDetailsPanel]
  );

  return (
    <Drawer isExpanded={showDetailsPanel} isInline id={'active-recording-drawer'}>
      <DrawerContent
        panelContent={{ [PanelContent.LABELS]: LabelsPanel }[panelContent]}
        className="recordings-table-drawer-content"
      >
        <DrawerContentBody hasPadding>
          <RecordingsTable
            tableTitle="Active Flight Recordings"
            toolbar={RecordingsToolbar}
            tableColumns={tableColumns}
            isHeaderChecked={headerChecked}
            onHeaderCheck={handleHeaderCheck}
            isEmpty={!recordings.length}
            isEmptyFilterResult={!filteredRecordings.length}
            clearFilters={handleClearFilters}
            isLoading={isLoading}
            isNestedTable={false}
            errorMessage={errorMessage}
          >
            {recordingRows}
          </RecordingsTable>
        </DrawerContentBody>
      </DrawerContent>
    </Drawer>
  );
};

export type ActiveActions = 'ARCHIVE' | 'STOP' | 'DELETE';

export interface ActiveRecordingsToolbarProps {
  target: string;
  checkedIndices: number[];
  targetRecordingFilters: RecordingFiltersCategories;
  recordings: ActiveRecording[];
  filteredRecordings: ActiveRecording[];
  updateFilters: (target: string, updateFilterOptions: UpdateFilterOptions) => void;
  handleClearFilters: () => void;
  archiveEnabled: boolean;
  handleCreateRecording: () => void;
  handleArchiveRecordings: () => void;
  handleEditLabels: () => void;
  handleStopRecordings: () => void;
  handleDeleteRecordings: () => void;
  actionLoadings: Record<ActiveActions, boolean>;
}

const ActiveRecordingsToolbar: React.FunctionComponent<ActiveRecordingsToolbarProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const [warningModalOpen, setWarningModalOpen] = React.useState(false);

  const deletionDialogsEnabled = React.useMemo(
    () => context.settings.deletionDialogsEnabledFor(DeleteWarningType.DeleteActiveRecordings),
    [context, context.settings, context.settings.deletionDialogsEnabledFor]
  );

  const handleWarningModalClose = React.useCallback(() => {
    setWarningModalOpen(false);
  }, [setWarningModalOpen]);

  const handleDeleteButton = React.useCallback(() => {
    if (deletionDialogsEnabled) {
      setWarningModalOpen(true);
    } else {
      props.handleDeleteRecordings();
    }
  }, [deletionDialogsEnabled, setWarningModalOpen, props.handleDeleteRecordings]);

  const isStopDisabled = React.useMemo(() => {
    if (!props.checkedIndices.length || props.actionLoadings['STOP']) {
      return true;
    }
    const filtered = props.filteredRecordings.filter((r) => props.checkedIndices.includes(r.id));
    const anyRunning = filtered.some((r) => r.state === RecordingState.RUNNING || r.state == RecordingState.STARTING);
    return !anyRunning;
  }, [props.checkedIndices, props.filteredRecordings]);

  const actionLoadingProps = React.useMemo<Record<ActiveActions, LoadingPropsType>>(
    () => ({
      ARCHIVE: {
        spinnerAriaValueText: 'Archiving',
        spinnerAriaLabel: 'archive-active-recording',
        isLoading: props.actionLoadings['ARCHIVE'],
      },
      STOP: {
        spinnerAriaValueText: 'Stopping',
        spinnerAriaLabel: 'stop-active-recording',
        isLoading: props.actionLoadings['STOP'],
      },
      DELETE: {
        spinnerAriaValueText: 'Deleting',
        spinnerAriaLabel: 'deleting-active-recording',
        isLoading: props.actionLoadings['DELETE'],
      },
    }),
    [props.actionLoadings]
  );

  const buttons = React.useMemo(() => {
    let arr = [
      <Button key="create" variant="primary" onClick={props.handleCreateRecording}>
        Create
      </Button>,
    ];
    if (props.archiveEnabled) {
      arr.push(
        <Button
          key="archive"
          variant="secondary"
          onClick={props.handleArchiveRecordings}
          isDisabled={!props.checkedIndices.length || props.actionLoadings['ARCHIVE']}
          {...actionLoadingProps['ARCHIVE']}
        >
          {props.actionLoadings['ARCHIVE'] ? 'Archiving' : 'Archive'}
        </Button>
      );
    }
    arr = [
      ...arr,
      <Button
        key="edit labels"
        variant="secondary"
        onClick={props.handleEditLabels}
        isDisabled={!props.checkedIndices.length}
      >
        Edit Labels
      </Button>,
      <Button
        key="stop"
        variant="tertiary"
        onClick={props.handleStopRecordings}
        isDisabled={isStopDisabled}
        {...actionLoadingProps['STOP']}
      >
        {props.actionLoadings['STOP'] ? 'Stopping' : 'Stop'}
      </Button>,
      <Button
        key="delete"
        variant="danger"
        onClick={handleDeleteButton}
        isDisabled={!props.checkedIndices.length || props.actionLoadings['DELETE']}
        {...actionLoadingProps['DELETE']}
      >
        {props.actionLoadings['DELETE'] ? 'Deleting' : 'Delete'}
      </Button>,
    ];
    return (
      <>
        {arr.map((btn, idx) => (
          <ToolbarItem key={idx}>{btn}</ToolbarItem>
        ))}
      </>
    );
  }, [
    props.checkedIndices,
    props.handleCreateRecording,
    props.handleArchiveRecordings,
    props.handleEditLabels,
    props.handleStopRecordings,
    props.actionLoadings,
    handleDeleteButton,
    actionLoadingProps,
  ]);

  const deleteActiveWarningModal = React.useMemo(() => {
    return (
      <DeleteWarningModal
        warningType={DeleteWarningType.DeleteActiveRecordings}
        visible={warningModalOpen}
        onAccept={props.handleDeleteRecordings}
        onClose={handleWarningModalClose}
      />
    );
  }, [warningModalOpen, props.handleDeleteRecordings, handleWarningModalClose]);

  return (
    <Toolbar
      id="active-recordings-toolbar"
      aria-label="active-recording-toolbar"
      clearAllFilters={props.handleClearFilters}
    >
      <ToolbarContent>
        <RecordingFilters
          target={props.target}
          isArchived={false}
          recordings={props.recordings}
          filters={props.targetRecordingFilters}
          updateFilters={props.updateFilters}
        />
        <ToolbarGroup style={{ alignSelf: 'start' }} variant="button-group">
          {buttons}
        </ToolbarGroup>
        {deleteActiveWarningModal}
      </ToolbarContent>
    </Toolbar>
  );
};
