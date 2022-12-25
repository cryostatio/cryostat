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
import _ from 'lodash';
import { Link } from 'react-router-dom';
import {
  Badge,
  Button,
  Checkbox,
  EmptyState,
  EmptyStateIcon,
  Text,
  Title,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import { ExpandableRowContent, TableComposable, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { SearchIcon } from '@patternfly/react-icons';
import { concatMap, forkJoin, Observable, of } from 'rxjs';
import { ServiceContext } from '@app/Shared/Services/Services';
import { Target } from '@app/Shared/Services/Target.service';
import { StoredCredential } from '@app/Shared/Services/Api.service';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { CreateJmxCredentialModal } from './CreateJmxCredentialModal';
import { SecurityCard } from '../SecurityPanel';
import { NotificationCategory } from '@app/Shared/Services/NotificationChannel.service';
import { TargetDiscoveryEvent } from '@app/Shared/Services/Targets.service';
import { LoadingView } from '@app/LoadingView/LoadingView';
import { DeleteWarningModal } from '@app/Modal/DeleteWarningModal';
import { DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import { MatchedTargetsTable } from './MatchedTargetsTable';

const enum Actions {
  HANDLE_REFRESH,
  HANDLE_TARGET_NOTIFICATION,
  HANDLE_CREDENTIALS_STORED_NOTIFICATION,
  HANDLE_CREDENTIALS_DELETED_NOTIFICATION,
  HANDLE_ROW_CHECK,
  HANDLE_HEADER_CHECK,
  HANDLE_TOGGLE_EXPANDED,
}

const reducer = (state, action) => {
  switch (action.type) {
    case Actions.HANDLE_REFRESH: {
      const credentials: StoredCredential[] = action.payload.credentials;
      let counts: number[] = [];
      for (const c of credentials) {
        counts.push(c.numMatchingTargets);
      }
      return {
        ...state,
        credentials: credentials,
        counts: counts,
      };
    }
    case Actions.HANDLE_TARGET_NOTIFICATION: {
      const target: Target = action.payload.target;
      let updated = [...state.counts];
      for (let i = 0; i < state.credentials.length; i++) {
        let match: boolean = eval(state.credentials[i].matchExpression);
        if (match) {
          updated[i] += action.payload.kind === 'FOUND' ? 1 : -1;
        }
      }
      return {
        ...state,
        counts: updated,
      };
    }
    case Actions.HANDLE_CREDENTIALS_STORED_NOTIFICATION: {
      return {
        ...state,
        credentials: state.credentials.concat(action.payload.credential),
        counts: state.counts.concat(action.payload.credential.numMatchingTargets),
      };
    }
    case Actions.HANDLE_CREDENTIALS_DELETED_NOTIFICATION: {
      const deletedCredential: StoredCredential = action.payload.credential;
      let deletedIdx;
      for (deletedIdx = 0; deletedIdx < state.credentials.length; deletedIdx++) {
        if (_.isEqual(deletedCredential, state.credentials[deletedIdx])) break;
      }
      const updatedCounts = [...state.counts];
      updatedCounts.splice(deletedIdx, 1);
      const updatedCheckedCredentials = state.checkedCredentials.filter((o) => !_.isEqual(o, deletedCredential));

      return {
        ...state,
        credentials: state.credentials.filter((o) => !_.isEqual(o, deletedCredential)),
        expandedCredentials: state.expandedCredentials.filter((o) => !_.isEqual(o, deletedCredential)),
        checkedCredentials: updatedCheckedCredentials,
        isHeaderChecked: updatedCheckedCredentials.length === 0 ? false : state.isHeaderChecked,
        counts: updatedCounts,
      };
    }
    case Actions.HANDLE_ROW_CHECK: {
      if (action.payload.checked) {
        return {
          ...state,
          checkedCredentials: state.checkedCredentials.concat(action.payload.credential),
        };
      } else {
        return {
          ...state,
          checkedCredentials: state.checkedCredentials.filter((o) => !_.isEqual(o, action.payload.credential)),
          isHeaderChecked: false,
        };
      }
    }
    case Actions.HANDLE_HEADER_CHECK: {
      return {
        ...state,
        checkedCredentials: action.payload.checked ? [...state.credentials] : [],
        isHeaderChecked: action.payload.checked,
      };
    }
    case Actions.HANDLE_TOGGLE_EXPANDED: {
      const credential: StoredCredential = action.payload.credential;
      const idx = state.expandedCredentials.indexOf(credential);
      const updated =
        idx >= 0
          ? [
              ...state.expandedCredentials.slice(0, idx),
              ...state.expandedCredentials.slice(idx + 1, state.expandedCredentials.length),
            ]
          : [...state.expandedCredentials, credential];

      return {
        ...state,
        expandedCredentials: updated,
      };
    }
    default: {
      return state;
    }
  }
};

export const StoreJmxCredentials = () => {
  const context = React.useContext(ServiceContext);
  const [state, dispatch] = React.useReducer(reducer, {
    credentials: [] as StoredCredential[],
    expandedCredentials: [] as StoredCredential[],
    checkedCredentials: [] as StoredCredential[],
    isHeaderChecked: false,
    counts: [] as number[],
  });
  const [showAuthModal, setShowAuthModal] = React.useState(false);
  const [warningModalOpen, setWarningModalOpen] = React.useState(false);
  const [isLoading, setIsLoading] = React.useState(false);
  const addSubscription = useSubscriptions();

  const tableColumns: string[] = ['Match Expression', 'Count'];
  const tableTitle = 'Stored Credentials';

  const refreshStoredCredentialsAndCounts = React.useCallback(() => {
    setIsLoading(true);
    addSubscription(
      context.api.getCredentials().subscribe((credentials: StoredCredential[]) => {
        dispatch({ type: Actions.HANDLE_REFRESH, payload: { credentials: credentials } });
        setIsLoading(false);
      })
    );
  }, [addSubscription, context, context.api, setIsLoading]);

  React.useEffect(() => {
    refreshStoredCredentialsAndCounts();
  }, []);

  React.useEffect(() => {
    if (!context.settings.autoRefreshEnabled()) {
      return;
    }
    const id = window.setInterval(
      () => refreshStoredCredentialsAndCounts(),
      context.settings.autoRefreshPeriod() * context.settings.autoRefreshUnits()
    );
    return () => window.clearInterval(id);
  }, [context.target, context.settings, refreshStoredCredentialsAndCounts]);

  React.useEffect(() => {
    addSubscription(
      context.notificationChannel.messages(NotificationCategory.CredentialsStored).subscribe((v) => {
        dispatch({ type: Actions.HANDLE_CREDENTIALS_STORED_NOTIFICATION, payload: { credential: v.message } });
      })
    );
  }, [addSubscription, context, context.notificationChannel]);

  React.useEffect(() => {
    addSubscription(
      context.notificationChannel
        .messages(NotificationCategory.CredentialsDeleted)
        .pipe(
          concatMap((v) =>
            of(dispatch({ type: Actions.HANDLE_CREDENTIALS_DELETED_NOTIFICATION, payload: { credential: v.message } }))
          )
        )
        .subscribe(() => {} /* do nothing - dispatch will have already handled updating state */)
    );
  }, [addSubscription, context, context.notificationChannel]);

  const handleTargetNotification = (evt: TargetDiscoveryEvent) => {
    if (evt.kind === 'FOUND' || evt.kind === 'LOST') {
      dispatch({ type: Actions.HANDLE_TARGET_NOTIFICATION, payload: { target: evt.serviceRef, kind: evt.kind } });
    }
  };

  React.useEffect(() => {
    addSubscription(
      context.notificationChannel
        .messages(NotificationCategory.TargetJvmDiscovery)
        .pipe(concatMap((v) => of(handleTargetNotification(v.message.event))))
        .subscribe(() => {} /* do nothing - dispatch will have already handled updating state */)
    );
  }, [addSubscription, context, context.notificationChannel]);

  const handleHeaderCheck = React.useCallback((event, checked) => {
    dispatch({ type: Actions.HANDLE_HEADER_CHECK, payload: { checked: checked } });
  }, []);

  const handleDeleteCredentials = React.useCallback(() => {
    const tasks: Observable<any>[] = [];
    state.credentials.forEach((credential) => {
      if (state.checkedCredentials.includes(credential)) {
        tasks.push(context.api.deleteCredentials(credential.id));
      }
    });
    addSubscription(forkJoin(tasks).subscribe());
  }, [state.credentials, state.checkedCredentials, context, context.api, addSubscription]);

  const handleAuthModalOpen = React.useCallback(() => {
    setShowAuthModal(true);
  }, [setShowAuthModal]);

  const handleAuthModalClose = React.useCallback(() => {
    setShowAuthModal(false);
  }, [setShowAuthModal]);

  const handleDeleteButton = React.useCallback(() => {
    if (context.settings.deletionDialogsEnabledFor(DeleteWarningType.DeleteJMXCredentials)) {
      setWarningModalOpen(true);
    } else {
      handleDeleteCredentials();
    }
  }, [context, context.settings, setWarningModalOpen, handleDeleteCredentials]);

  const handleWarningModalClose = React.useCallback(() => {
    setWarningModalOpen(false);
  }, [setWarningModalOpen]);

  const TargetCredentialsToolbar = () => {
    const buttons = React.useMemo(() => {
      const arr = [
        <Button variant="primary" aria-label="add-jmx-credential" onClick={handleAuthModalOpen}>
          Add
        </Button>,
        <Button
          key="delete"
          variant="danger"
          aria-label="delete-selected-jmx-credential"
          onClick={handleDeleteButton}
          isDisabled={!state.checkedCredentials.length}
        >
          Delete
        </Button>,
      ];
      return (
        <>
          {arr.map((btn, idx) => (
            <ToolbarItem key={idx}>{btn}</ToolbarItem>
          ))}
        </>
      );
    }, [state.checkedCredentials]);

    const deleteCredentialModal = React.useMemo(() => {
      return (
        <DeleteWarningModal
          warningType={DeleteWarningType.DeleteJMXCredentials}
          visible={warningModalOpen}
          onAccept={handleDeleteCredentials}
          onClose={handleWarningModalClose}
        />
      );
    }, [state.checkedCredentials]);

    return (
      <Toolbar id="target-credentials-toolbar">
        <ToolbarContent>{buttons}</ToolbarContent>
        {deleteCredentialModal}
      </Toolbar>
    );
  };

  const matchExpressionRows = React.useMemo(() => {
    return state.credentials.map((credential, idx) => {
      let isExpanded: boolean = state.expandedCredentials.includes(credential);
      let isChecked: boolean = state.checkedCredentials.includes(credential);

      const handleToggleExpanded = () => {
        if (state.counts[idx] !== 0 || isExpanded) {
          dispatch({ type: Actions.HANDLE_TOGGLE_EXPANDED, payload: { credential: credential } });
        }
      };

      const handleRowCheck = (checked: boolean) => {
        dispatch({ type: Actions.HANDLE_ROW_CHECK, payload: { checked: checked, credential: credential } });
      };

      return (
        <Tr key={`${idx}_parent`}>
          <Td
            key={`credentials-table-row-${idx}_0`}
            id={`credentials-ex-toggle-${idx}`}
            aria-controls={`credentials-ex-expand-${idx}`}
            expand={{
              rowIndex: idx,
              isExpanded: isExpanded,
              onToggle: handleToggleExpanded,
            }}
          />
          <Td key={`credentials-table-row-${idx}_1`}>
            <Checkbox
              name={`credentials-table-row-${idx}-check`}
              onChange={handleRowCheck}
              isChecked={isChecked}
              id={`credentials-table-row-${idx}-check`}
              aria-label={`credentials-table-row-${idx}-check`}
            />
          </Td>
          <Td key={`credentials-table-row-${idx}_2`} dataLabel={tableColumns[0]}>
            {credential.matchExpression}
          </Td>
          <Td key={`credentials-table-row-${idx}_3`} dataLabel={tableColumns[1]}>
            <Badge key={`${idx}_count`}>{state.counts[idx]}</Badge>
          </Td>
        </Tr>
      );
    });
  }, [state.credentials, state.expandedCredentials, state.checkedCredentials, state.counts]);

  const targetRows = React.useMemo(() => {
    return state.credentials.map((credential, idx) => {
      let isExpanded: boolean = state.expandedCredentials.includes(credential);

      return (
        <Tr key={`${idx}_child`} isExpanded={isExpanded}>
          <Td key={`credentials-ex-expand-${idx}`} dataLabel={'Content Details'} colSpan={tableColumns.length + 2}>
            {isExpanded ? (
              <ExpandableRowContent>
                <MatchedTargetsTable id={credential.id} matchExpression={credential.matchExpression} />
              </ExpandableRowContent>
            ) : null}
          </Td>
        </Tr>
      );
    });
  }, [state.credentials, state.expandedCredentials]);

  const rowPairs = React.useMemo(() => {
    let rowPairs: JSX.Element[] = [];
    for (let i = 0; i < matchExpressionRows.length; i++) {
      rowPairs.push(matchExpressionRows[i]);
      rowPairs.push(targetRows[i]);
    }
    return rowPairs;
  }, [matchExpressionRows, targetRows]);

  let content: JSX.Element;
  if (isLoading) {
    content = (
      <>
        <LoadingView />
      </>
    );
  } else if (state.credentials.length === 0) {
    content = (
      <>
        <EmptyState>
          <EmptyStateIcon icon={SearchIcon} />
          <Title headingLevel="h4" size="lg">
            No {tableTitle}
          </Title>
        </EmptyState>
      </>
    );
  } else {
    content = (
      <>
        <TableComposable aria-label={tableTitle}>
          <Thead>
            <Tr>
              <Th key="table-header-expand" />
              <Th
                key="table-header-check-all"
                select={{
                  onSelect: handleHeaderCheck,
                  isSelected: state.isHeaderChecked,
                }}
              />
              {tableColumns.map((key, idx) => (
                <Th key={`table-header-${key}`} width={key === 'Match Expression' ? 90 : 15}>
                  {key}
                </Th>
              ))}
            </Tr>
          </Thead>
          <Tbody>{rowPairs}</Tbody>
        </TableComposable>
      </>
    );
  }

  return (
    <>
      <TargetCredentialsToolbar />
      {content}
      <CreateJmxCredentialModal
        visible={showAuthModal}
        onDismiss={handleAuthModalClose}
        onSave={handleAuthModalClose}
      />
    </>
  );
};

export const StoreJmxCredentialsCard: SecurityCard = {
  title: 'Store JMX Credentials',
  description: (
    <Text>
      Credentials that Cryostat uses to connect to target JVMs over JMX are stored here. These are stored in encrypted
      storage managed by the Cryostat backend. These credentials may be used for manually managing recordings and event
      templates on target JVMs, as well as for Automated Rules which run in the background and open unattended target
      connections. Any locally-stored client credentials held by your browser session are not displayed here. See{' '}
      <Link to="/settings">Settings</Link> to configure locally-stored credentials.
    </Text>
  ),
  content: StoreJmxCredentials,
};
