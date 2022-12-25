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
import { FormSelect, FormSelectOption, FormSelectOptionGroup, ValidatedOptions } from '@patternfly/react-core';
import * as React from 'react';
import { EventTemplate } from '@app/CreateRecording/CreateRecording';
import { TemplateType } from '@app/Shared/Services/Api.service';

export interface TemplateSelectionGroup {
  groupLabel: string;
  disabled?: boolean;
  options: {
    value: any;
    label: string;
    disabled?: boolean;
  }[];
}

export interface SelectTemplateSelectorFormProps {
  selected: string; // e.g. "Continuous,TARGET"
  templates: EventTemplate[];
  disabled?: boolean;
  validated?: ValidatedOptions;
  onSelect: (template?: string, templateType?: TemplateType) => void;
}

export const SelectTemplateSelectorForm: React.FunctionComponent<SelectTemplateSelectorFormProps> = (props) => {
  const groups = React.useMemo(
    () =>
      [
        {
          groupLabel: 'Target Templates',
          options: props.templates
            .filter((t) => t.type === 'TARGET')
            .map((t) => ({
              value: `${t.name},${t.type}`,
              label: t.name,
            })),
        },
        {
          groupLabel: 'Custom Templates',
          options: props.templates
            .filter((t) => t.type === 'CUSTOM')
            .map((t) => ({
              value: `${t.name},${t.type}`,
              label: t.name,
            })),
        },
      ] as TemplateSelectionGroup[],
    [props.templates]
  );

  const handleTemplateSelect = React.useCallback(
    (selected: string) => {
      if (!selected.length) {
        props.onSelect(undefined, undefined);
      } else {
        const str = selected.split(',');
        props.onSelect(str[0], str[1] as TemplateType);
      }
    },
    [props.onSelect]
  );

  return (
    <>
      <FormSelect
        isDisabled={props.disabled}
        value={props.selected}
        validated={props.validated || ValidatedOptions.default}
        onChange={handleTemplateSelect}
        aria-label="Event Template Input"
        id="recording-template"
      >
        <FormSelectOption key="-1" value="" label="Select a Template" isPlaceholder />
        {groups.map((group, index) => (
          <FormSelectOptionGroup isDisabled={group.disabled} key={index} label={group.groupLabel}>
            {group.options.map((option, idx) => (
              <FormSelectOption key={idx} label={option.label} value={option.value} isDisabled={option.disabled} />
            ))}
          </FormSelectOptionGroup>
        ))}
      </FormSelect>
    </>
  );
};
