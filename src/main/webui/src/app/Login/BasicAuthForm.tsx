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
import { ServiceContext } from '@app/Shared/Services/Services';
import { ActionGroup, Button, Checkbox, Form, FormGroup, Text, TextInput, TextVariants } from '@patternfly/react-core';
import { map } from 'rxjs/operators';
import { FormProps } from './FormProps';
import { Base64 } from 'js-base64';
import { AuthMethod } from '@app/Shared/Services/Login.service';

export const BasicAuthForm: React.FunctionComponent<FormProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const [username, setUsername] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [rememberMe, setRememberMe] = React.useState(true);

  React.useEffect(() => {
    const sub = context.login
      .getToken()
      .pipe(map(Base64.decode))
      .subscribe((creds) => {
        if (!creds.includes(':')) {
          setUsername(creds);
          return;
        }
        let parts: string[] = creds.split(':');
        setUsername(parts[0]);
        setPassword(parts[1]);
      });
    return () => sub.unsubscribe();
  }, [context, context.api, setUsername, setPassword]);

  const handleUserChange = React.useCallback(
    (evt) => {
      setUsername(evt);
    },
    [setUsername]
  );

  const handlePasswordChange = React.useCallback(
    (evt) => {
      setPassword(evt);
    },
    [setPassword]
  );

  const handleRememberMeToggle = React.useCallback(
    (evt) => {
      setRememberMe(evt);
    },
    [setRememberMe]
  );

  const handleSubmit = React.useCallback(
    (evt) => {
      props.onSubmit(evt, `${username}:${password}`, AuthMethod.BASIC, rememberMe);
    },
    [props, props.onSubmit, username, password, context.login, rememberMe]
  );

  // FIXME Patternfly Form component onSubmit is not triggered by Enter keydown when the Form contains
  // multiple FormGroups. This key handler is a workaround to allow keyboard-driven use of the form
  const handleKeyDown = React.useCallback(
    (evt) => {
      if (evt.key === 'Enter') {
        handleSubmit(evt);
      }
    },
    [handleSubmit]
  );

  return (
    <Form onSubmit={handleSubmit}>
      <FormGroup label="Username" isRequired fieldId="username" helperText="Please provide your username">
        <TextInput
          isRequired
          type="text"
          id="username"
          name="username"
          aria-describedby="username-helper"
          value={username}
          onChange={handleUserChange}
          onKeyDown={handleKeyDown}
        />
      </FormGroup>
      <FormGroup label="Password" isRequired fieldId="password" helperText="Please provide your password">
        <TextInput
          isRequired
          type="password"
          id="password"
          name="password"
          aria-describedby="password-helper"
          value={password}
          onChange={handlePasswordChange}
          onKeyDown={handleKeyDown}
        />
      </FormGroup>
      <Checkbox id="remember-me" label="Remember Me" isChecked={rememberMe} onChange={handleRememberMeToggle} />
      <ActionGroup>
        <Button variant="primary" onClick={handleSubmit}>
          Login
        </Button>
      </ActionGroup>
    </Form>
  );
};

export const BasicAuthDescriptionText = () => {
  return <Text component={TextVariants.p}>The Cryostat server is configured with Basic authentication.</Text>;
};
