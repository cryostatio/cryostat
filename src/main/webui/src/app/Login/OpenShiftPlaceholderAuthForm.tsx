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
import { Button, EmptyState, EmptyStateBody, EmptyStateIcon, Text, TextVariants, Title } from '@patternfly/react-core';
import { FormProps } from './FormProps';
import { LockIcon } from '@patternfly/react-icons';
import { ServiceContext } from '@app/Shared/Services/Services';
import { AuthMethod, SessionState } from '@app/Shared/Services/Login.service';
import { NotificationsContext } from '@app/Notifications/Notifications';
import { combineLatest } from 'rxjs';

export const OpenShiftPlaceholderAuthForm: React.FunctionComponent<FormProps> = (props) => {
  const serviceContext = React.useContext(ServiceContext);
  const notifications = React.useContext(NotificationsContext);
  const [showPermissionDenied, setShowPermissionDenied] = React.useState(false);

  React.useEffect(() => {
    const sub = combineLatest([
      serviceContext.login.getSessionState(),
      notifications.problemsNotifications(),
    ]).subscribe((parts) => {
      const sessionState = parts[0];
      const errors = parts[1];
      const missingCryostatPermissions = errors.find((error) => error.title.includes('401')) !== undefined;

      setShowPermissionDenied(sessionState === SessionState.NO_USER_SESSION && missingCryostatPermissions);
    });
    return () => sub.unsubscribe();
  }, [setShowPermissionDenied]);

  const handleSubmit = React.useCallback(
    (evt) => {
      // Triggers a redirect to OpenShift Container Platform login page
      props.onSubmit(evt, 'anInvalidToken', AuthMethod.BEARER, true);
    },
    [props, props.onSubmit, serviceContext.login]
  );

  const permissionDenied = (
    <EmptyState>
      <EmptyStateIcon variant="container" component={LockIcon} />
      <Title size="lg" headingLevel="h4">
        Access Permissions Needed
      </Title>
      <EmptyStateBody>
        <Text>
          {`To continue, add permissions to your current account or login with a
        different account. For more info, see the User Authentication section of the `}
        </Text>
        <Text
          component={TextVariants.a}
          target="_blank"
          href="https://github.com/cryostatio/cryostat-operator#user-authentication"
        >
          Cryostat Operator README.
        </Text>
      </EmptyStateBody>
      <Button variant="primary" onClick={handleSubmit}>
        Retry Login
      </Button>
    </EmptyState>
  );

  return <>{showPermissionDenied && permissionDenied}</>;
};

export const OpenShiftAuthDescriptionText = () => {
  return (
    <Text component={TextVariants.p}>The Cryostat server is configured to use OpenShift OAuth authentication.</Text>
  );
};
