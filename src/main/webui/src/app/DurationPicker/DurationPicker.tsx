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
import { FormSelect, FormSelectOption, Split, SplitItem, TextInput } from '@patternfly/react-core';

export interface DurationPickerProps {
  onPeriodChange: (period: number) => void;
  onUnitScalarChange: (unitScalar: number) => void;
  period: number;
  unitScalar: number;
  enabled: boolean;
}

export const DurationPicker: React.FunctionComponent<DurationPickerProps> = (props) => {
  return (
    <>
      <Split hasGutter={true}>
        <SplitItem isFilled>
          <TextInput
            value={props.period}
            isRequired
            type="number"
            id="duration-picker-period"
            aria-describedby="recording-duration-helper"
            onChange={(v) => props.onPeriodChange(Number(v))}
            isDisabled={!props.enabled}
            min="0"
          />
        </SplitItem>
        <SplitItem>
          <FormSelect
            value={props.unitScalar}
            onChange={(v) => props.onUnitScalarChange(Number(v))}
            aria-label="Duration Picker Units Input"
            isDisabled={!props.enabled}
          >
            <FormSelectOption key="1" value={1 * 1000} label="Seconds" />
            <FormSelectOption key="2" value={60 * 1000} label="Minutes" />
            <FormSelectOption key="3" value={60 * 60 * 1000} label="Hours" />
          </FormSelect>
        </SplitItem>
      </Split>
    </>
  );
};
