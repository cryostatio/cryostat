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
import { ActionGroup, Button, Form, Text, TextVariants } from '@patternfly/react-core';
import { useHistory } from 'react-router-dom';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { ServiceContext } from '@app/Shared/Services/Services';
import { first } from 'rxjs';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import { authFailMessage, ErrorView, isAuthFail, missingSSLMessage } from '@app/ErrorView/ErrorView';

export interface SnapshotRecordingFormProps {}

export const SnapshotRecordingForm: React.FunctionComponent<SnapshotRecordingFormProps> = (props) => {
  const history = useHistory();
  const addSubscription = useSubscriptions();
  const context = React.useContext(ServiceContext);
  const [loading, setLoading] = React.useState(false);
  const [errorMessage, setErrorMessage] = React.useState('');

  const handleCreateSnapshot = React.useCallback(() => {
    setLoading(true);
    addSubscription(
      context.api
        .createSnapshot()
        .pipe(first())
        .subscribe((success) => {
          setLoading(false);
          if (success) {
            history.push('/recordings');
          }
        })
    );
  }, [addSubscription, context.api, history, setLoading]);

  const createButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Creating',
        spinnerAriaLabel: 'create-snapshot-recording',
        isLoading: loading,
      } as LoadingPropsType),
    [loading]
  );

  React.useEffect(() => {
    addSubscription(
      context.target.sslFailure().subscribe(() => {
        // also triggered if api calls in Custom Recording form fail
        setErrorMessage(missingSSLMessage);
      })
    );
  }, [context.target, setErrorMessage, addSubscription]);

  React.useEffect(() => {
    addSubscription(
      context.target.authRetry().subscribe(() => {
        setErrorMessage(''); // Reset on retry
      })
    );
  }, [context.target, setErrorMessage, addSubscription]);

  React.useEffect(() => {
    addSubscription(
      context.target.authFailure().subscribe(() => {
        // also triggered if api calls in Custom Recording form fail
        setErrorMessage(authFailMessage);
      })
    );
  }, [context.target, setErrorMessage, addSubscription]);

  React.useEffect(() => {
    addSubscription(
      context.target.target().subscribe(() => {
        setErrorMessage(''); // Reset on change
      })
    );
  }, [context.target, setErrorMessage, addSubscription]);

  const authRetry = React.useCallback(() => {
    context.target.setAuthRetry();
  }, [context.target]);

  if (errorMessage != '') {
    return (
      <ErrorView
        title={'Error displaying recording creation form'}
        message={errorMessage}
        retry={isAuthFail(errorMessage) ? authRetry : undefined}
      />
    );
  }
  return (
    <>
      <Form isHorizontal>
        <Text component={TextVariants.p}>
          A Snapshot recording is one which contains all information about all events that have been captured in the
          current session by <i>other,&nbsp; non-Snapshot</i> recordings. Snapshots do not themselves define which
          events are enabled, their thresholds, or any other options. A Snapshot is only ever in the STOPPED state from
          the moment it is created.
        </Text>
        <ActionGroup>
          <Button variant="primary" onClick={handleCreateSnapshot} isDisabled={loading} {...createButtonLoadingProps}>
            {loading ? 'Creating' : 'Create'}
          </Button>
          <Button variant="secondary" onClick={history.goBack} isDisabled={loading}>
            Cancel
          </Button>
        </ActionGroup>
      </Form>
    </>
  );
};
