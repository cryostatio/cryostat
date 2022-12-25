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
import { FUpload, MultiFileUpload, UploadCallbacks } from '@app/Shared/FileUploads';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import { ServiceContext } from '@app/Shared/Services/Services';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { ActionGroup, Button, Form, FormGroup, Modal, ModalVariant } from '@patternfly/react-core';
import * as React from 'react';
import { forkJoin, Observable, of } from 'rxjs';
import { catchError, defaultIfEmpty, tap } from 'rxjs/operators';

export interface CertificateUploadModalProps {
  visible: boolean;
  onClose: () => void;
}

export const CertificateUploadModal: React.FunctionComponent<CertificateUploadModalProps> = (props) => {
  const addSubscriptions = useSubscriptions();
  const context = React.useContext(ServiceContext);
  const submitRef = React.useRef<HTMLDivElement>(null); // Use ref to refer to submit trigger div
  const abortRef = React.useRef<HTMLDivElement>(null); // Use ref to refer to abort trigger div

  const [allOks, setAllOks] = React.useState(false);
  const [uploading, setUploading] = React.useState(false);
  const [numOfFiles, setNumOfFiles] = React.useState(0);

  const reset = React.useCallback(() => {
    setUploading(false);
    setNumOfFiles(0);
  }, [setUploading, setNumOfFiles]);

  const onFilesChange = React.useCallback(
    (fileUploads: FUpload[]) => {
      setAllOks(!fileUploads.some((f) => !f.progress || f.progress.progressVariant !== 'success'));
      setNumOfFiles(fileUploads.length);
    },
    [setNumOfFiles, setAllOks]
  );

  const handleClose = React.useCallback(() => {
    if (uploading) {
      abortRef.current && abortRef.current.click();
    } else {
      reset();
      props.onClose();
    }
  }, [uploading, abortRef.current, reset, props.onClose]);

  const handleSubmit = React.useCallback(() => {
    submitRef.current && submitRef.current.click();
  }, [submitRef.current]);

  const onFileSubmit = React.useCallback(
    (fileUploads: FUpload[], { getProgressUpdateCallback, onSingleSuccess, onSingleFailure }: UploadCallbacks) => {
      setUploading(true);

      const tasks: Observable<boolean>[] = [];
      fileUploads.forEach((fileUpload) => {
        tasks.push(
          context.api
            .uploadSSLCertificate(
              fileUpload.file,
              getProgressUpdateCallback(fileUpload.file.name),
              fileUpload.abortSignal
            )
            .pipe(
              tap({
                next: () => {
                  onSingleSuccess(fileUpload.file.name);
                },
                error: (err) => {
                  onSingleFailure(fileUpload.file.name, err);
                },
              }),
              catchError((_) => of(false))
            )
        );
      });

      addSubscriptions(
        forkJoin(tasks)
          .pipe(defaultIfEmpty([true]))
          .subscribe((oks) => {
            setUploading(false);
            setAllOks(oks.reduce((prev, curr, _) => prev && curr, true));
          })
      );
    },
    [setUploading, context.api, addSubscriptions, handleClose, setAllOks]
  );

  const submitButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Submitting',
        spinnerAriaLabel: 'submitting-ssl-certitficates',
        isLoading: uploading,
      } as LoadingPropsType),
    [uploading]
  );

  return (
    <Modal
      isOpen={props.visible}
      variant={ModalVariant.large}
      showClose={true}
      onClose={handleClose}
      title="Upload SSL certificate"
      description="Select a certificate file to upload. Certificates must be DER-encoded (can be binary or base64) and can have .der or .cer extensions."
    >
      <Form>
        <FormGroup label="Certificate File" isRequired fieldId="file">
          <MultiFileUpload
            submitRef={submitRef}
            abortRef={abortRef}
            uploading={uploading}
            dropZoneAccepts={['application/x-x509-ca-cert', 'application/pkix-cert']}
            displayAccepts={['CER', 'DER']}
            onFileSubmit={onFileSubmit}
            onFilesChange={onFilesChange}
          />
        </FormGroup>
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
                isDisabled={!numOfFiles || uploading}
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
  );
};
