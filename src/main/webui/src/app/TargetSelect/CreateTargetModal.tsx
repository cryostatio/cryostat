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
import { ServiceContext } from '@app/Shared/Services/Services';
import { Target } from '@app/Shared/Services/Target.service';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import {
  ActionGroup,
  Button,
  ButtonType,
  ClipboardCopy,
  Form,
  FormGroup,
  FormHelperText,
  Modal,
  ModalVariant,
  TextInput,
  ValidatedOptions,
} from '@patternfly/react-core';
import * as React from 'react';

export interface CreateTargetModalProps {
  visible: boolean;
  onSuccess: () => void;
  onDismiss: () => void;
}

const jmxServiceUrlFormat = /service:jmx:([a-zA-Z0-9-]+)/g;
const hostPortPairFormat = /([a-zA-Z0-9-]+):([0-9]+)/g;

export const CreateTargetModal: React.FunctionComponent<CreateTargetModalProps> = (props) => {
  const addSubscription = useSubscriptions();
  const context = React.useContext(ServiceContext);

  const [connectUrl, setConnectUrl] = React.useState('');
  const [validConnectUrl, setValidConnectUrl] = React.useState(ValidatedOptions.default);
  const [alias, setAlias] = React.useState('');
  const [loading, setLoading] = React.useState(false);

  const resetForm = React.useCallback(() => {
    setConnectUrl('');
    setAlias('');
  }, [setConnectUrl, setAlias]);

  const createTarget = React.useCallback(
    (target: Target) => {
      setLoading(true);
      addSubscription(
        context.api.createTarget(target).subscribe((success) => {
          setLoading(false);
          if (success) {
            resetForm();
            props.onSuccess();
          }
        })
      );
    },
    [addSubscription, context.api, props.onSuccess, setLoading, resetForm]
  );

  const handleKeyDown = React.useCallback(
    (evt) => {
      if (connectUrl && evt.key === 'Enter') {
        createTarget({ connectUrl, alias: alias.trim() || connectUrl });
      }
    },
    [createTarget, connectUrl, alias]
  );

  const handleSubmit = React.useCallback(() => {
    const isValid = connectUrl && (connectUrl.match(jmxServiceUrlFormat) || connectUrl.match(hostPortPairFormat));

    if (isValid) {
      createTarget({ connectUrl, alias: alias.trim() || connectUrl });
    } else {
      setValidConnectUrl(ValidatedOptions.error);
    }
  }, [createTarget, setValidConnectUrl, connectUrl, alias]);

  const createButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Creating',
        spinnerAriaLabel: 'creating-custom-target',
        isLoading: loading,
      } as LoadingPropsType),
    [loading]
  );

  return (
    <>
      <Modal
        isOpen={props.visible}
        variant={ModalVariant.small}
        showClose={true}
        onClose={props.onDismiss}
        title="Create Target"
        description="Create a custom target connection"
      >
        <Form isHorizontal>
          <FormGroup
            label="Connection URL"
            isRequired
            fieldId="connect-url"
            helperText={
              <FormHelperText isHidden={false} component="div">
                JMX Service URL
                <br />
                e.g.
                <ClipboardCopy hoverTip="Click to copy to clipboard" clickTip="Copied!" variant="inline-compact">
                  {'service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi'}
                </ClipboardCopy>
              </FormHelperText>
            }
            helperTextInvalid={
              'Must be a JMX Service URL, e.g. service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi, or host:port pair'
            }
            validated={validConnectUrl}
          >
            <TextInput
              aria-label={'Connection URL'}
              value={connectUrl}
              isRequired
              type="text"
              id="connect-url"
              onChange={setConnectUrl}
              onKeyDown={handleKeyDown}
              isDisabled={loading}
            />
          </FormGroup>
          <FormGroup label="Alias" fieldId="alias" helperText="Connection Nickname">
            <TextInput
              value={alias}
              type="text"
              id="alias"
              onChange={setAlias}
              onKeyDown={handleKeyDown}
              isDisabled={loading}
            />
          </FormGroup>
        </Form>
        <ActionGroup>
          <Button
            variant="primary"
            type={ButtonType.submit}
            isDisabled={!connectUrl || loading}
            onClick={handleSubmit}
            {...createButtonLoadingProps}
          >
            {loading ? 'Creating' : 'Create'}
          </Button>
        </ActionGroup>
      </Modal>
    </>
  );
};
