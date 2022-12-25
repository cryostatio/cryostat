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
import { NotificationsContext } from '@app/Notifications/Notifications';
import { ActiveRecording, Recording } from '@app/Shared/Services/Api.service';
import { ServiceContext } from '@app/Shared/Services/Services';
import { Target } from '@app/Shared/Services/Target.service';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { Dropdown, DropdownItem, KebabToggle } from '@patternfly/react-core';
import { Td } from '@patternfly/react-table';
import * as React from 'react';
import { Observable } from 'rxjs';
import { first } from 'rxjs/operators';

export interface RowAction {
  title: string | React.ReactNode;
  key: string;
  onClick: () => void;
}

export interface RecordingActionsProps {
  index: number;
  recording: Recording;
  sourceTarget?: Observable<Target>;
  uploadFn: () => Observable<boolean>;
}

export const RecordingActions: React.FunctionComponent<RecordingActionsProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const notifications = React.useContext(NotificationsContext);
  const [grafanaEnabled, setGrafanaEnabled] = React.useState(false);
  const [isOpen, setIsOpen] = React.useState(false);

  const addSubscription = useSubscriptions();

  React.useEffect(() => {
    addSubscription(
      context.api
        .grafanaDatasourceUrl()
        .pipe(first())
        .subscribe(() => setGrafanaEnabled(true))
    );
  }, [context.api, setGrafanaEnabled, addSubscription]);

  const grafanaUpload = React.useCallback(() => {
    notifications.info('Upload Started', `Recording "${props.recording.name}" uploading...`);
    addSubscription(
      props
        .uploadFn()
        .pipe(first())
        .subscribe((success) => {
          if (success) {
            notifications.success('Upload Success', `Recording "${props.recording.name}" uploaded`);
            context.api
              .grafanaDashboardUrl()
              .pipe(first())
              .subscribe((url) => window.open(url, '_blank'));
          }
        })
    );
  }, [addSubscription, notifications, props.uploadFn, context.api]);

  const handleDownloadRecording = React.useCallback(() => {
    context.api.downloadRecording(props.recording);
  }, [context.api, props.recording]);

  const handleViewReport = React.useCallback(() => {
    context.api.downloadReport(props.recording);
  }, [context.api, props.recording]);

  const actionItems = React.useMemo(() => {
    const actionItems = [
      {
        title: 'Download Recording',
        key: 'download-recording',
        onClick: handleDownloadRecording,
      },
      {
        title: 'View Report ...',
        key: 'view-report',
        onClick: handleViewReport,
      },
    ] as RowAction[];
    if (grafanaEnabled) {
      actionItems.push({
        title: 'View in Grafana ...',
        key: 'view-in-grafana',
        onClick: grafanaUpload,
      });
    }
    return actionItems;
  }, [handleDownloadRecording, handleViewReport, grafanaEnabled, grafanaUpload]);

  const onSelect = React.useCallback(
    (action: RowAction) => {
      setIsOpen(false);
      action.onClick();
    },
    [setIsOpen]
  );

  return (
    <Td isActionCell>
      <Dropdown
        menuAppendTo={document.body}
        position="right"
        direction="down"
        toggle={<KebabToggle id="toggle-kebab" onToggle={setIsOpen} />}
        isPlain
        isOpen={isOpen}
        dropdownItems={actionItems.map((action) => (
          <DropdownItem key={action.key} onClick={() => onSelect(action)}>
            {action.title}
          </DropdownItem>
        ))}
      />
    </Td>
  );
};
