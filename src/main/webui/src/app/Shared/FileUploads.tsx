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

import { CancelUploadModal } from '@app/Modal/CancelUploadModal';
import { NotificationsContext } from '@app/Notifications/Notifications';
import {
  MultipleFileUpload,
  MultipleFileUploadMain,
  MultipleFileUploadStatus,
  MultipleFileUploadStatusItem,
} from '@patternfly/react-core';
import { InProgressIcon, UploadIcon } from '@patternfly/react-icons';
import React from 'react';
import { Prompt } from 'react-router-dom';
import { Subject } from 'rxjs';

export type ProgressVariant = 'success' | 'danger' | 'warning';

export interface FUpload {
  file: File;
  abortSignal: Subject<void>;
  progress?: {
    progressValue: number;
    progressVariant: ProgressVariant;
  };
  helperText?: string;
  error?: Error; // error.message take precedence over helperText
}

export interface UploadCallbacks {
  getProgressUpdateCallback: (filename: string) => (progress: number | string) => void;
  onSingleSuccess: (filename: string) => void;
  onSingleFailure: (filename: string, error: Error) => void;
}

export interface MultiFileUploadProps {
  submitRef?: React.RefObject<HTMLDivElement>;
  abortRef?: React.RefObject<HTMLDivElement>;
  uploading: boolean;
  displayAccepts: String[];
  dropZoneAccepts?: String[]; // Infer from displayAccepts, if not specified
  onFilesChange?: (files: FUpload[]) => void;
  onFileSubmit: (fileUploads: FUpload[], uploadCallbacks: UploadCallbacks) => void;
  titleIcon?: React.ReactNode;
  titleText?: React.ReactNode;
  titleTextSeparator?: React.ReactNode;
  infoText?: React.ReactNode;
}

export const MultiFileUpload: React.FunctionComponent<MultiFileUploadProps> = ({
  titleIcon,
  titleText,
  titleTextSeparator,
  infoText,
  displayAccepts,
  dropZoneAccepts,
  onFileSubmit,
  uploading,
  onFilesChange,
  submitRef,
  abortRef,
}) => {
  const notifications = React.useContext(NotificationsContext);
  const [fileUploads, setFileUploads] = React.useState<FUpload[]>([]);
  const [showCancelPrompt, setShowCancelPrompt] = React.useState(false);

  const dzAccept = React.useMemo(() => {
    if (dropZoneAccepts && dropZoneAccepts.length) {
      return dropZoneAccepts.join(',');
    }
    return displayAccepts.map((t) => `.${t.toLocaleLowerCase()}`).join(',');
  }, [dropZoneAccepts, displayAccepts]);

  const handleFileDrop = React.useCallback(
    (droppedFiles: File[]) => {
      setFileUploads((old) => {
        // Check for re-uploads
        const currentFilenames = old.map((fileUpload) => fileUpload.file.name);
        const reUploadFilenames = droppedFiles.filter((f) => currentFilenames.includes(f.name)).map((f) => f.name);

        const newFileUploads = [
          ...old.filter((fileUpload) => !reUploadFilenames.includes(fileUpload.file.name)),
          ...droppedFiles.map(
            (f) =>
              ({
                file: f,
                abortSignal: new Subject<void>(),
              } as FUpload)
          ),
        ];
        onFilesChange && onFilesChange(newFileUploads);
        return newFileUploads;
      });
    },
    [setFileUploads, onFilesChange]
  );

  const handleFileReject = React.useCallback(
    (rejectedFiles: File[]) => {
      rejectedFiles.forEach((f) => {
        if (!dzAccept.includes(f.type) || f.type === '') {
          const message = `Expected file format: ${dzAccept}, but received ${
            f.type === '' ? 'unknown type' : f.type
          } for ${f.name}`;
          notifications.warning(`Incompatible file format`, message);
        } else {
          notifications.warning(`Failed to load file`, f.name);
        }
      });
    },
    [notifications, dzAccept]
  );

  const handleFileRemove = React.useCallback(
    (removedFilename: string, abort = false) => {
      if (abort) {
        const fileToAbort = fileUploads.find((fileUpload) => fileUpload.file.name === removedFilename);
        fileToAbort?.abortSignal.next();
      } else {
        setFileUploads((old) => {
          const newFileUploads = old.filter((fileUpload) => fileUpload.file.name !== removedFilename);
          onFilesChange && onFilesChange(newFileUploads);
          return newFileUploads;
        });
      }
    },
    [fileUploads, setFileUploads, onFilesChange]
  );

  const getProgressUpdateCallback = React.useCallback(
    (filename: string) => {
      return (progress: string | number) => {
        setFileUploads((old) => {
          const match = old.find((f) => f.file.name === filename);
          if (match) {
            return [
              ...old.filter((f) => f.file.name !== filename),
              {
                ...match,
                progress: {
                  progressValue: progress,
                  progressVariant: `${progress}` == '100' ? 'success' : undefined,
                },
              } as FUpload,
            ];
          }
          return old;
        });
      };
    },
    [setFileUploads]
  );

  const onSingleFailure = React.useCallback(
    (filename: string, error: Error) => {
      setFileUploads((old) => {
        const match = old.find((f) => f.file.name === filename);
        if (match) {
          return [
            ...old.filter((f) => f.file.name !== filename),
            {
              ...match,
              progress: {
                progressValue: match.progress?.progressValue || 0,
                progressVariant: 'danger',
              },
              error: error,
            } as FUpload,
          ];
        }
        return old;
      });
    },
    [setFileUploads]
  );

  const onSingleSuccess = React.useCallback(
    (filename: String) => {
      setFileUploads((old) => {
        const match = old.find((f) => f.file.name === filename);
        if (match) {
          return [
            ...old.filter((f) => f.file.name !== filename),
            {
              ...match,
              progress: {
                progressValue: 100,
                progressVariant: 'success',
              },
            } as FUpload,
          ];
        }
        return old;
      });
    },
    [setFileUploads]
  );

  const handleSubmit = React.useCallback(() => {
    setFileUploads((old) => {
      const toUploads = old.filter((f) => f.error || !f.progress); // Failed or newly uploaded
      onFileSubmit(toUploads, { getProgressUpdateCallback, onSingleSuccess, onSingleFailure });

      const newFileUploads = old.map(
        (f) =>
          ({
            ...f,
            error: undefined,
            progress: f.progress?.progressVariant === 'success' ? f.progress : undefined,
            helperText: f.progress?.progressVariant === 'success' ? 'Already uploaded' : undefined,
          } as FUpload)
      );
      return newFileUploads;
    });
  }, [onFileSubmit, getProgressUpdateCallback, onSingleSuccess, onSingleFailure]);

  const handleCloseCancelModal = React.useCallback(() => setShowCancelPrompt(false), [setShowCancelPrompt]);

  const handleOpenCancelModal = React.useCallback(() => setShowCancelPrompt(true), [setShowCancelPrompt]);

  const handleAbortAll = React.useCallback(() => {
    fileUploads
      .filter((fileUpload) => !fileUpload.progress?.progressVariant)
      .map((fileUpload) => {
        fileUpload.abortSignal.next(); // trigger abort
      });

    handleCloseCancelModal();
  }, [fileUploads, handleCloseCancelModal]);

  const handleCancel = React.useCallback(() => {
    if (uploading) {
      handleOpenCancelModal();
    }
  }, [handleOpenCancelModal, uploading]);

  const numOfSuccessUploads = React.useMemo(() => {
    return fileUploads.filter((fUpload) => fUpload.progress && fUpload.progress.progressVariant === 'success').length;
  }, [fileUploads]);

  const sortedFileUploads = React.useMemo(() => {
    return fileUploads.sort((a, b) => a.file.name.localeCompare(b.file.name, undefined, { numeric: true }));
  }, [fileUploads]);

  return (
    <>
      <Prompt when={uploading} message="Are you sure you wish to cancel the file upload?" />
      <CancelUploadModal
        visible={showCancelPrompt}
        title="Upload in Progress"
        message="Are you sure you wish to cancel the file uploads? Successfully uploaded files won't be aborted."
        onYes={handleAbortAll}
        onNo={handleCloseCancelModal}
      />
      <MultipleFileUpload
        onFileDrop={handleFileDrop}
        dropzoneProps={{
          accept: dzAccept,
          onDropRejected: handleFileReject,
        }}
        disabled={uploading}
      >
        <MultipleFileUploadMain
          titleIcon={titleIcon || <UploadIcon />}
          titleText={titleText || 'Drag and drop files here'}
          titleTextSeparator={titleTextSeparator || 'or'}
          infoText={infoText || `Accepted file types: ${displayAccepts.join(', ')}`}
        />
        {fileUploads.length ? (
          <MultipleFileUploadStatus
            statusToggleText={`${numOfSuccessUploads} of ${fileUploads.length} files uploaded`}
            statusToggleIcon={<InProgressIcon />}
          >
            {sortedFileUploads.map((fileUpload) => (
              <MultipleFileUploadStatusItem
                file={fileUpload.file}
                key={fileUpload.file.name}
                onClearClick={() => handleFileRemove(fileUpload.file.name, uploading)}
                customFileHandler={(_) => {}} // To disable built-in file reader and default styling
                progressValue={fileUpload.progress?.progressValue}
                progressVariant={fileUpload.progress?.progressVariant}
                progressHelperText={fileUpload.error?.message || fileUpload.helperText}
              />
            ))}
          </MultipleFileUploadStatus>
        ) : undefined}
      </MultipleFileUpload>
      {/* fake action buttons */}
      <div ref={submitRef} id={'start-upload-files'} hidden onClick={handleSubmit} />
      <div ref={abortRef} id={'abort-upload-files'} hidden onClick={handleCancel} />
    </>
  );
};
