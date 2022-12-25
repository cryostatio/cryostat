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
import { CloseIcon, ExclamationCircleIcon, FileIcon, PlusCircleIcon, UploadIcon } from '@patternfly/react-icons';
import {
  Button,
  FormHelperText,
  HelperText,
  HelperTextItem,
  List,
  ListItem,
  Popover,
  Split,
  SplitItem,
  Text,
  TextInput,
  ValidatedOptions,
} from '@patternfly/react-core';
import { parseLabelsFromFile, RecordingLabel } from '@app/RecordingMetadata/RecordingLabel';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { LoadingView } from '@app/LoadingView/LoadingView';
import { catchError, Observable, of, zip } from 'rxjs';

export interface RecordingLabelFieldsProps {
  labels: RecordingLabel[];
  setLabels: (labels: RecordingLabel[]) => void;
  setValid: (isValid: ValidatedOptions) => void;
  isUploadable?: boolean;
  isDisabled?: boolean;
}

export const LabelPattern = /^\S+$/;

const getValidatedOption = (isValid: boolean) => {
  return isValid ? ValidatedOptions.success : ValidatedOptions.error;
};

const matchesLabelSyntax = (l: RecordingLabel) => {
  return l && LabelPattern.test(l.key) && LabelPattern.test(l.value);
};

export const RecordingLabelFields: React.FunctionComponent<RecordingLabelFieldsProps> = (props) => {
  const inputRef = React.useRef<HTMLInputElement>(null); // Use ref to refer to child component
  const addSubscription = useSubscriptions();

  const [loading, setLoading] = React.useState(false);
  const [invalidUploads, setInvalidUploads] = React.useState<string[]>([]);

  const handleKeyChange = React.useCallback(
    (idx, key) => {
      const updatedLabels = [...props.labels];
      updatedLabels[idx].key = key;
      props.setLabels(updatedLabels);
    },
    [props.labels, props.setLabels]
  );

  const handleValueChange = React.useCallback(
    (idx, value) => {
      const updatedLabels = [...props.labels];
      updatedLabels[idx].value = value;
      props.setLabels(updatedLabels);
    },
    [props.labels, props.setLabels]
  );

  const handleAddLabelButtonClick = React.useCallback(() => {
    props.setLabels([...props.labels, { key: '', value: '' } as RecordingLabel]);
  }, [props.labels, props.setLabels]);

  const handleDeleteLabelButtonClick = React.useCallback(
    (idx) => {
      const updated = [...props.labels];
      updated.splice(idx, 1);
      props.setLabels(updated);
    },
    [props.labels, props.setLabels]
  );

  const isLabelValid = React.useCallback(matchesLabelSyntax, [matchesLabelSyntax]);

  const isDuplicateKey = React.useCallback((key: string, labels: RecordingLabel[]) => {
    return labels.filter((label) => label.key === key).length > 1;
  }, []);

  const allLabelsValid = React.useMemo(() => {
    if (!props.labels.length) {
      return true;
    }
    return props.labels.reduce(
      (prev, curr, idx) => isLabelValid(curr) && !isDuplicateKey(curr.key, props.labels) && prev,
      true
    );
  }, [props.labels, isLabelValid, isDuplicateKey]);

  const validKeys = React.useMemo(() => {
    const arr = Array(props.labels.length).fill(ValidatedOptions.default);
    props.labels.forEach((label, index) => {
      if (label.key.length > 0) {
        arr[index] = getValidatedOption(LabelPattern.test(label.key) && !isDuplicateKey(label.key, props.labels));
      } // Ignore initial empty key inputs
    });
    return arr;
  }, [props.labels, LabelPattern, getValidatedOption]);

  const validValues = React.useMemo(() => {
    const arr = Array(props.labels.length).fill(ValidatedOptions.default);
    props.labels.forEach((label, index) => {
      if (label.value.length > 0) {
        arr[index] = getValidatedOption(LabelPattern.test(label.value));
      } // Ignore initial empty value inputs
    });
    return arr;
  }, [props.labels, LabelPattern, getValidatedOption]);

  React.useEffect(() => {
    props.setValid(getValidatedOption(allLabelsValid));
  }, [props.setValid, allLabelsValid, getValidatedOption]);

  const handleUploadLabel = React.useCallback(
    (e) => {
      const files = e.target.files;
      if (files && files.length) {
        const tasks: Observable<RecordingLabel[]>[] = [];
        setLoading(true);
        for (const labelFile of e.target.files) {
          tasks.push(
            parseLabelsFromFile(labelFile).pipe(
              catchError((_) => {
                setInvalidUploads((old) => old.concat([labelFile.name]));
                return of([]);
              })
            )
          );
        }
        addSubscription(
          zip(tasks).subscribe((labelArrays: RecordingLabel[][]) => {
            setLoading(false);
            const labels = labelArrays.reduce((acc, next) => acc.concat(next), []);
            props.setLabels([...props.labels, ...labels]);
          })
        );
      }
    },
    [props.setLabels, props.labels, addSubscription, setLoading]
  );

  const closeWarningPopover = React.useCallback(() => setInvalidUploads([]), [setInvalidUploads]);

  const openLabelFileBrowse = React.useCallback(() => {
    inputRef.current && inputRef.current.click();
  }, [inputRef]);

  return loading ? (
    <LoadingView />
  ) : (
    <>
      <Button
        aria-label="Add Label"
        onClick={handleAddLabelButtonClick}
        variant="link"
        icon={<PlusCircleIcon />}
        isDisabled={props.isDisabled}
      >
        Add Label
      </Button>
      {props.isUploadable && (
        <>
          <Popover
            isVisible={!!invalidUploads.length}
            aria-label="uploading warning"
            alertSeverityVariant="danger"
            headerContent="Invalid Selection"
            headerComponent="h1"
            shouldClose={closeWarningPopover}
            headerIcon={<ExclamationCircleIcon />}
            bodyContent={
              <>
                <Text component="h4">{`The following file${
                  invalidUploads.length > 1 ? 's' : ''
                } did not contain valid recording metadata:`}</Text>
                <List>
                  {invalidUploads.map((uploadName) => (
                    <ListItem key={uploadName} icon={<FileIcon />}>
                      {uploadName}
                    </ListItem>
                  ))}
                </List>
              </>
            }
          >
            <Button
              aria-label="Upload Labels"
              onClick={openLabelFileBrowse}
              variant="link"
              icon={<UploadIcon />}
              isDisabled={props.isDisabled}
            >
              Upload Labels
            </Button>
          </Popover>
          <input
            ref={inputRef}
            accept={'.json'}
            type="file"
            style={{ display: 'none' }}
            onChange={handleUploadLabel}
            multiple
          />
        </>
      )}
      {props.labels.map((label, idx) => (
        <Split hasGutter key={idx}>
          <SplitItem isFilled>
            <TextInput
              isRequired
              type="text"
              id="label-key-input"
              name="label-key-input"
              aria-describedby="label-key-input-helper"
              aria-label="Label Key"
              value={label.key ?? ''}
              onChange={(key) => handleKeyChange(idx, key)}
              validated={validKeys[idx]}
              isDisabled={props.isDisabled}
            />
            <Text>Key</Text>
            <FormHelperText
              isHidden={validKeys[idx] !== ValidatedOptions.error && validValues[idx] !== ValidatedOptions.error}
              component="div"
            >
              <HelperText id="label-error-text">
                <HelperTextItem variant="error">
                  Keys must be unique. Labels should not contain whitespace.
                </HelperTextItem>
              </HelperText>
            </FormHelperText>
          </SplitItem>
          <SplitItem isFilled>
            <TextInput
              isRequired
              type="text"
              id="label-value-input"
              name="label-value-input"
              aria-describedby="label-value-input-helper"
              aria-label="Label Value"
              value={label.value ?? ''}
              onChange={(value) => handleValueChange(idx, value)}
              validated={validValues[idx]}
              isDisabled={props.isDisabled}
            />
            <Text>Value</Text>
          </SplitItem>
          <SplitItem>
            <Button
              onClick={() => handleDeleteLabelButtonClick(idx)}
              variant="link"
              aria-label="Remove Label"
              isDisabled={props.isDisabled}
              icon={<CloseIcon color="gray" size="sm" />}
            />
          </SplitItem>
        </Split>
      ))}
    </>
  );
};
