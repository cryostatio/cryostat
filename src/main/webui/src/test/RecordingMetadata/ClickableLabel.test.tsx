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
import renderer, { act } from 'react-test-renderer';
import { cleanup, screen } from '@testing-library/react';

import '@testing-library/jest-dom';
import { ClickableLabel } from '@app/RecordingMetadata/ClickableLabel';
import { RecordingLabel } from '@app/RecordingMetadata/RecordingLabel';
import { renderDefault } from '../Common';

const mockLabel = {
  key: 'someLabel',
  value: 'someValue',
} as RecordingLabel;
const mockLabelAsString = 'someLabel: someValue';

const onLabelClick = jest.fn((label: RecordingLabel) => {
  /**Do nothing. Used for checking renders */
});

const blueLabelBorderColor = '#06c';
const greyLabelBorderColor = '#8a8d90';

describe('<ClickableLabel />', () => {
  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ClickableLabel label={mockLabel} isSelected={true} onLabelClick={onLabelClick}></ClickableLabel>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('should display blue when the label is currently selected', async () => {
    renderDefault(<ClickableLabel label={mockLabel} isSelected={true} onLabelClick={onLabelClick}></ClickableLabel>);

    const label = screen.getByLabelText(mockLabelAsString);
    expect(label).toBeInTheDocument();
    expect(label).toBeVisible();

    expect(label.classList.contains('pf-m-blue')).toBe(true);
  });

  it('should display grey when the label is currently not selected', async () => {
    renderDefault(<ClickableLabel label={mockLabel} isSelected={false} onLabelClick={onLabelClick}></ClickableLabel>);

    const label = screen.getByLabelText(mockLabelAsString);
    expect(label).toBeInTheDocument();
    expect(label).toBeVisible();

    expect(label.classList.contains('pf-m-blue')).toBe(false);
  });

  it('should display hover effect when hovered and is selected', async () => {
    const { user } = renderDefault(
      <ClickableLabel label={mockLabel} isSelected={true} onLabelClick={onLabelClick}></ClickableLabel>
    );

    const label = screen.getByLabelText(mockLabelAsString);
    expect(label).toBeInTheDocument();
    expect(label).toBeVisible();

    expect(label.classList.contains('pf-m-blue')).toBe(true);

    await user.hover(label);

    const labelStyle = label.style;
    expect(labelStyle.cursor).toBe('pointer');
    expect(labelStyle.getPropertyValue('--pf-c-label__content--before--BorderWidth')).toBe('2.5px');
    expect(labelStyle.getPropertyValue('--pf-c-label__content--before--BorderColor')).toBe(blueLabelBorderColor);
  });

  it('should display hover effect when hovered and is not selected', async () => {
    const { user } = renderDefault(
      <ClickableLabel label={mockLabel} isSelected={false} onLabelClick={onLabelClick}></ClickableLabel>
    );

    const label = screen.getByLabelText(mockLabelAsString);
    expect(label).toBeInTheDocument();
    expect(label).toBeVisible();

    expect(label.classList.contains('pf-m-blue')).toBe(false);

    await user.hover(label);

    const labelStyle = label.style;
    expect(labelStyle.cursor).toBe('pointer');
    expect(labelStyle.getPropertyValue('--pf-c-label__content--before--BorderWidth')).toBe('2.5px');
    expect(labelStyle.getPropertyValue('--pf-c-label__content--before--BorderColor')).toBe(greyLabelBorderColor);
  });

  it('should update label filters when clicked', async () => {
    const { user } = renderDefault(
      <ClickableLabel label={mockLabel} isSelected={true} onLabelClick={onLabelClick}></ClickableLabel>
    );

    const label = screen.getByLabelText(mockLabelAsString);
    expect(label).toBeInTheDocument();
    expect(label).toBeVisible();

    expect(label.classList.contains('pf-m-blue')).toBe(true);

    await user.click(label);

    expect(onLabelClick).toHaveBeenCalledTimes(1);
    expect(onLabelClick).toHaveBeenCalledWith(mockLabel);
  });
});
