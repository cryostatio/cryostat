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
import * as tlr from '@testing-library/react';
import renderer, { act } from 'react-test-renderer';
import { cleanup, screen } from '@testing-library/react';
import { RecordingLabel } from '@app/RecordingMetadata/RecordingLabel';
import '@testing-library/jest-dom';
import { RecordingLabelFields, RecordingLabelFieldsProps } from '@app/RecordingMetadata/RecordingLabelFields';
import { ValidatedOptions } from '@patternfly/react-core';
import { renderDefault } from '../Common';

const mockUploadedRecordingLabels = {
  someUploaded: 'someUploadedValue',
};
const mockMetadataFileName = 'mock.metadata.json';
const mockMetadataFile = new File(
  [JSON.stringify({ labels: { ...mockUploadedRecordingLabels } })],
  mockMetadataFileName,
  { type: 'json' }
);
mockMetadataFile.text = jest.fn(
  () => new Promise((resolve, _) => resolve(JSON.stringify({ labels: { ...mockUploadedRecordingLabels } })))
);

describe('<RecordingLabelFields />', () => {
  // RecordingLabelFields component modifies labels in-place, so we need to reinitialize mocks
  // after every tests.
  let mockLabels: RecordingLabel[];
  let mockValid: ValidatedOptions;
  let mockProps: RecordingLabelFieldsProps;
  let mockLabel1: RecordingLabel;
  let mockLabel2: RecordingLabel;
  let mockEmptyLabel: RecordingLabel;

  afterEach(cleanup);

  beforeEach(() => {
    mockLabel1 = {
      key: 'someLabel',
      value: 'someValue',
    } as RecordingLabel;
    mockLabel2 = {
      key: 'anotherLabel',
      value: 'anotherValue',
    } as RecordingLabel;
    mockEmptyLabel = {
      key: '',
      value: '',
    } as RecordingLabel;
    mockLabels = [mockLabel1, mockLabel2];
    mockValid = ValidatedOptions.default;
    mockProps = {
      labels: mockLabels,
      setLabels: jest.fn((labels: RecordingLabel[]) => (mockLabels = labels.slice())),
      setValid: jest.fn((state: ValidatedOptions) => (mockValid = state)),
    };
  });

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(<RecordingLabelFields {...mockProps} />);
    });
    expect(tree).toMatchSnapshot();
  });

  it('displays all labels in form fields', async () => {
    renderDefault(<RecordingLabelFields {...mockProps} />);

    const inputs = [
      screen.getByDisplayValue('someLabel'),
      screen.getByDisplayValue('someValue'),
      screen.getByDisplayValue('anotherLabel'),
      screen.getByDisplayValue('anotherValue'),
    ];

    inputs.forEach((input) => {
      expect(input).toBeInTheDocument();
      expect(input).toBeVisible();
    });

    const addLabelButton = screen.getByRole('button', { name: 'Add Label' });
    expect(addLabelButton).toBeInTheDocument();
    expect(addLabelButton).toBeVisible();

    expect(mockProps.setValid).toHaveBeenCalledTimes(1);
    expect(mockProps.setValid).toHaveBeenCalledWith(ValidatedOptions.success);
  });

  it('updates the label key when entering text into the Key input', async () => {
    const { user } = renderDefault(<RecordingLabelFields {...mockProps} />);

    const labelKeyInput = screen.getAllByLabelText('Label Key')[0] as HTMLInputElement;
    expect(labelKeyInput).toBeInTheDocument();
    expect(labelKeyInput).toBeVisible();

    labelKeyInput.setSelectionRange(0, mockProps.labels[0].key.length);
    labelKeyInput.focus();
    await user.paste('someEditedKey');

    expect(mockProps.labels[0].key).toBe('someEditedKey');
  });

  it('updates the label value when entering text into the Value input', async () => {
    const { user } = renderDefault(<RecordingLabelFields {...mockProps} />);

    const labelValueInput = screen.getAllByLabelText('Label Value')[0] as HTMLInputElement;
    expect(labelValueInput).toBeInTheDocument();
    expect(labelValueInput).toBeVisible();

    labelValueInput.setSelectionRange(0, mockProps.labels[0].value.length);
    labelValueInput.focus();
    await user.paste('someEditedValue');

    expect(mockProps.labels[0].value).toBe('someEditedValue');
  });

  it('validates labels on initial render', async () => {
    renderDefault(<RecordingLabelFields {...mockProps} />);

    expect(mockProps.setValid).toHaveBeenCalledTimes(1);
    expect(mockProps.setValid).toHaveBeenCalledWith(ValidatedOptions.success);
    expect(mockValid).toBe(ValidatedOptions.success);
  });

  it('adds a label when Add Label is clicked', async () => {
    const { user } = renderDefault(<RecordingLabelFields {...mockProps} />);

    expect(screen.getAllByLabelText('Label Key').length).toBe(2);
    expect(screen.getAllByLabelText('Label Value').length).toBe(2);

    await user.click(screen.getByText('Add Label'));

    expect(mockProps.setLabels).toHaveBeenCalledTimes(1);
    expect(mockProps.setLabels).toHaveBeenCalledWith([mockLabel1, mockLabel2, mockEmptyLabel]);
  });

  it('removes the correct label when Delete button is clicked', async () => {
    const { user } = renderDefault(<RecordingLabelFields {...mockProps} />);

    expect(screen.getAllByLabelText('Label Key').length).toBe(2);
    expect(screen.getAllByLabelText('Label Value').length).toBe(2);

    await user.click(screen.getAllByLabelText('Remove Label')[0]);

    expect(mockProps.setLabels).toHaveBeenCalledTimes(1);
    expect(mockProps.setLabels).toHaveBeenCalledWith([mockLabel2]);
  });

  it('validates the label key when valid', async () => {
    renderDefault(<RecordingLabelFields {...mockProps} />);

    expect(mockValid).toBe(ValidatedOptions.success);

    screen.getAllByLabelText('Label Key').forEach((element) => {
      expect(element).toBeInTheDocument();
      expect(element).toBeVisible();
      expect(element.classList.contains('pf-m-success')).toBe(true);
    });
  });

  it('validates the label value when valid', async () => {
    renderDefault(<RecordingLabelFields {...mockProps} />);

    expect(mockValid).toBe(ValidatedOptions.success);

    screen.getAllByLabelText('Label Value').forEach((element) => {
      expect(element).toBeInTheDocument();
      expect(element).toBeVisible();
      expect(element.classList.contains('pf-m-success')).toBe(true);
    });
  });

  it('invalidates form when the label key is invalid', async () => {
    const invalidLabels = [{ key: 'label with whitespace', value: 'someValue' }];

    renderDefault(<RecordingLabelFields {...mockProps} labels={invalidLabels} />);

    expect(mockValid).toBe(ValidatedOptions.error);
  });

  it('invalidates form when the label value is invalid', async () => {
    const invalidLabels = [{ key: 'someLabel', value: 'value with whitespace' }];

    renderDefault(<RecordingLabelFields {...mockProps} labels={invalidLabels} />);

    expect(mockValid).toBe(ValidatedOptions.error);
  });

  it('shows error text when the label key is invalid', async () => {
    const invalidLabels = [{ key: 'label with whitespace', value: 'someValue' }];

    renderDefault(<RecordingLabelFields {...mockProps} labels={invalidLabels} />);

    expect(mockValid).toBe(ValidatedOptions.error);

    const errorText = await screen.findByText('Keys must be unique. Labels should not contain whitespace.');
    expect(errorText).toBeInTheDocument();
    expect(errorText).toBeVisible();
  });

  it('shows error text when the label value is invalid', async () => {
    const invalidLabels = [{ key: 'someLabel', value: 'value with whitespace' }];

    renderDefault(<RecordingLabelFields {...mockProps} labels={invalidLabels} />);

    expect(mockValid).toBe(ValidatedOptions.error);

    const errorText = await screen.findByText('Keys must be unique. Labels should not contain whitespace.');
    expect(errorText).toBeInTheDocument();
    expect(errorText).toBeVisible();
  });

  it('shows upload button when upload is enabled ', async () => {
    mockProps.isUploadable = true;

    renderDefault(<RecordingLabelFields {...mockProps} />);

    const uploadButton = screen.getByRole('button', { name: 'Upload Labels' });
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();
  });

  it('updates label list when upload is enabled and upload is submitted', async () => {
    mockProps.isUploadable = true;

    const { user } = renderDefault(<RecordingLabelFields {...mockProps} />);

    const uploadButton = screen.getByRole('button', { name: 'Upload Labels' });
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();

    await user.click(uploadButton);

    const labelUploadInput = document.querySelector("input[accept='.json'][type='file']") as HTMLInputElement;
    expect(labelUploadInput).toBeInTheDocument();

    await tlr.act(async () => {
      await user.upload(labelUploadInput, mockMetadataFile);
    });

    expect(labelUploadInput.files).not.toBe(null);
    expect(labelUploadInput.files![0]).toStrictEqual(mockMetadataFile);

    expect(mockProps.setLabels).toHaveBeenCalledTimes(1);
    expect(mockProps.setLabels).toHaveBeenCalledWith([
      mockLabel1,
      mockLabel2,
      { key: 'someUploaded', value: 'someUploadedValue' },
    ]);
  });
});
