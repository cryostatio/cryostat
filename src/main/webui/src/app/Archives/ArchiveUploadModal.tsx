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
import { RecordingLabel } from '@app/RecordingMetadata/RecordingLabel';
import { RecordingLabelFields } from '@app/RecordingMetadata/RecordingLabelFields';
import { FUpload, MultiFileUpload, UploadCallbacks } from '@app/Shared/FileUploads';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import { ServiceContext } from '@app/Shared/Services/Services';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import {
  ActionGroup,
  Button,
  ExpandableSection,
  Form,
  FormGroup,
  Modal,
  ModalVariant,
  Text,
  Tooltip,
  ValidatedOptions,
} from '@patternfly/react-core';
import { HelpIcon } from '@patternfly/react-icons';
import * as React from 'react';
import { forkJoin, Observable, of } from 'rxjs';
import { catchError, defaultIfEmpty, tap } from 'rxjs/operators';

export interface ArchiveUploadModalProps {
  visible: boolean;
  onClose: () => void;
}

export const ArchiveUploadModal: React.FunctionComponent<ArchiveUploadModalProps> = (props) => {
  const addSubscriptions = useSubscriptions();
  const context = React.useContext(ServiceContext);
  const submitRef = React.useRef<HTMLDivElement>(null); // Use ref to refer to submit trigger div
  const abortRef = React.useRef<HTMLDivElement>(null); // Use ref to refer to abort trigger div

  const [uploading, setUploading] = React.useState(false);
  const [numOfFiles, setNumOfFiles] = React.useState(0);
  const [allOks, setAllOks] = React.useState(false);
  const [labels, setLabels] = React.useState([] as RecordingLabel[]);
  const [valid, setValid] = React.useState(ValidatedOptions.success);

  const getFormattedLabels = React.useCallback(() => {
    const formattedLabels = {};
    labels.forEach((l) => {
      if (l.key && l.value) {
        formattedLabels[l.key] = l.value;
      }
    });
    return formattedLabels;
  }, [labels]);

  const reset = React.useCallback(() => {
    setUploading(false);
    setLabels([] as RecordingLabel[]);
    setValid(ValidatedOptions.success);
    setNumOfFiles(0);
  }, [setUploading, setLabels, setValid, setNumOfFiles]);

  const handleClose = React.useCallback(() => {
    if (uploading) {
      abortRef.current && abortRef.current.click();
    } else {
      reset();
      props.onClose();
    }
  }, [uploading, abortRef.current, reset, props.onClose]);

  const onFileSubmit = React.useCallback(
    (fileUploads: FUpload[], { getProgressUpdateCallback, onSingleSuccess, onSingleFailure }: UploadCallbacks) => {
      setUploading(true);

      const tasks: Observable<string | undefined>[] = [];

      fileUploads.forEach((fileUpload) => {
        tasks.push(
          context.api
            .uploadRecording(
              fileUpload.file,
              getFormattedLabels(),
              getProgressUpdateCallback(fileUpload.file.name),
              fileUpload.abortSignal
            )
            .pipe(
              tap({
                next: (_) => {
                  onSingleSuccess(fileUpload.file.name);
                },
                error: (err) => {
                  onSingleFailure(fileUpload.file.name, err);
                },
              }),
              catchError((_) => of(undefined))
            )
        );
      });

      addSubscriptions(
        forkJoin(tasks)
          .pipe(defaultIfEmpty(['']))
          .subscribe((savedNames) => {
            setUploading(false);
            setAllOks(!savedNames.some((name) => name === undefined));
          })
      );
    },
    [addSubscriptions, context.api, setUploading, handleClose, getFormattedLabels, setAllOks]
  );

  const handleSubmit = React.useCallback(() => {
    submitRef.current && submitRef.current.click();
  }, [submitRef.current]);

  const onFilesChange = React.useCallback(
    (fileUploads: FUpload[]) => {
      setAllOks(!fileUploads.some((f) => !f.progress || f.progress.progressVariant !== 'success'));
      setNumOfFiles(fileUploads.length);
    },
    [setNumOfFiles, setAllOks]
  );

  const submitButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Submitting',
        spinnerAriaLabel: 'submitting-uploaded-recording',
        isLoading: uploading,
      } as LoadingPropsType),
    [uploading]
  );

  return (
    <>
      <Modal
        isOpen={props.visible}
        variant={ModalVariant.large}
        showClose={true}
        onClose={handleClose}
        title="Re-Upload Archived Recording"
        description={
          <Text>
            <span>
              Select a JDK Flight Recorder file to re-upload. Files must be .jfr binary format and follow the naming
              convention used by Cryostat when archiving recordings
            </span>{' '}
            <Tooltip
              content={
                <Text>
                  Archive naming conventions: <b>target-name_recordingName_timestamp.jfr</b>.
                  <br />
                  For example: io-cryostat-Cryostat_profiling_timestamp.jfr
                </Text>
              }
            >
              <sup style={{ cursor: 'pointer' }}>
                <b>[?]</b>
              </sup>
            </Tooltip>
            <span>.</span>
          </Text>
        }
      >
        <Form>
          <FormGroup label="JFR File" isRequired fieldId="file">
            <MultiFileUpload
              submitRef={submitRef}
              abortRef={abortRef}
              uploading={uploading}
              displayAccepts={['JFR']}
              onFileSubmit={onFileSubmit}
              onFilesChange={onFilesChange}
            />
          </FormGroup>
          <ExpandableSection toggleTextExpanded="Hide metadata options" toggleTextCollapsed="Show metadata options">
            <FormGroup
              label="Labels"
              fieldId="labels"
              labelIcon={
                <Tooltip content={<Text>Unique key-value pairs containing information about the recording.</Text>}>
                  <HelpIcon noVerticalAlign />
                </Tooltip>
              }
            >
              <RecordingLabelFields
                isUploadable
                labels={labels}
                setLabels={setLabels}
                setValid={setValid}
                isDisabled={uploading}
              />
            </FormGroup>
          </ExpandableSection>
          <ActionGroup>
            {allOks && numOfFiles ? (
              <Button variant="primary" onClick={handleClose}>
                Close
              </Button>
            ) : (
              <>
                <Button
                  variant="primary"
                  onClick={handleSubmit}
                  isDisabled={!numOfFiles || valid !== ValidatedOptions.success || uploading}
                  {...submitButtonLoadingProps}
                >
                  {uploading ? 'Submitting' : 'Submit'}
                </Button>
                <Button variant="link" onClick={handleClose}>
                  Cancel
                </Button>
              </>
            )}
          </ActionGroup>
        </Form>
      </Modal>
    </>
  );
};
