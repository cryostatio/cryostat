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
import { Divider, ExpandableSection, Switch, Stack, StackItem } from '@patternfly/react-core';
import { ServiceContext } from '@app/Shared/Services/Services';
import { UserSetting } from './Settings';
import { DeleteWarningType, getFromWarningMap } from '@app/Modal/DeleteWarningUtils';

const Component = () => {
  const context = React.useContext(ServiceContext);
  const [state, setState] = React.useState(context.settings.deletionDialogsEnabled());
  const [expanded, setExpanded] = React.useState(false);

  const handleCheckboxChange = React.useCallback(
    (checked, element) => {
      state.set(DeleteWarningType[element.target.id], checked);
      context.settings.setDeletionDialogsEnabled(state);
      setState(new Map(state));
    },
    [state, setState, context.settings]
  );

  const handleCheckAll = React.useCallback(
    (checked) => {
      const newState = new Map();
      Array.from(state.entries()).forEach((v) => newState.set(v[0], checked));
      context.settings.setDeletionDialogsEnabled(newState);
      setState(newState);
    },
    [state, setState]
  );

  const allChecked = React.useMemo(() => {
    return Array.from(state.entries())
      .map((e) => e[1])
      .reduce((a, b) => a && b);
  }, [state]);

  const switches = React.useMemo(() => {
    return Array.from(state.entries(), ([key, value]) => (
      <StackItem key={key}>
        <Switch
          id={key}
          label={getFromWarningMap(key)?.label || key.toString()}
          isChecked={value}
          onChange={handleCheckboxChange}
        />
      </StackItem>
    ));
  }, [state]);

  return (
    <>
      <Stack hasGutter>
        <StackItem key="all-deletion-warnings">
          <Switch
            id="all-deletion-warnings"
            label="All Deletion Warnings"
            isChecked={allChecked}
            onChange={handleCheckAll}
          />
        </StackItem>
        <Divider />
        <ExpandableSection
          toggleText={expanded ? 'Show less' : 'Show more'}
          onToggle={setExpanded}
          isExpanded={expanded}
        >
          {switches}
        </ExpandableSection>
      </Stack>
    </>
  );
};

export const DeletionDialogControl: UserSetting = {
  title: 'Show Deletion Dialogs',
  description: 'Enable or disable deletion dialogs by deletion type.',
  content: Component,
};
