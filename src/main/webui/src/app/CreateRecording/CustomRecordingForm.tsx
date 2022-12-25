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
import { NotificationsContext } from '@app/Notifications/Notifications';
import { ServiceContext } from '@app/Shared/Services/Services';
import {
  ActionGroup,
  Button,
  Checkbox,
  ExpandableSection,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  HelperText,
  HelperTextItem,
  Label,
  Split,
  SplitItem,
  Text,
  TextInput,
  TextVariants,
  Tooltip,
  ValidatedOptions,
} from '@patternfly/react-core';
import { HelpIcon } from '@patternfly/react-icons';
import { useHistory } from 'react-router-dom';
import { concatMap, first, filter } from 'rxjs/operators';
import { EventTemplate } from './CreateRecording';
import { RecordingOptions, RecordingAttributes } from '@app/Shared/Services/Api.service';
import { DurationPicker } from '@app/DurationPicker/DurationPicker';
import { SelectTemplateSelectorForm } from '@app/TemplateSelector/SelectTemplateSelectorForm';
import { RecordingLabel } from '@app/RecordingMetadata/RecordingLabel';
import { RecordingLabelFields } from '@app/RecordingMetadata/RecordingLabelFields';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import { TemplateType } from '@app/Shared/Services/Api.service';
import { authFailMessage, ErrorView, isAuthFail } from '@app/ErrorView/ErrorView';
import { NO_TARGET } from '@app/Shared/Services/Target.service';
import { forkJoin } from 'rxjs';

export interface CustomRecordingFormProps {
  prefilled?: {
    templateName?: string;
    templateType?: TemplateType;
  };
}

export const RecordingNamePattern = /^[\w_]+$/;
export const DurationPattern = /^[1-9][0-9]*$/;

export const CustomRecordingForm: React.FunctionComponent<CustomRecordingFormProps> = ({ prefilled }) => {
  const context = React.useContext(ServiceContext);
  const notifications = React.useContext(NotificationsContext);
  const history = useHistory();
  const addSubscription = useSubscriptions();

  const [recordingName, setRecordingName] = React.useState('');
  const [nameValid, setNameValid] = React.useState(ValidatedOptions.default);
  const [continuous, setContinuous] = React.useState(false);
  const [archiveOnStop, setArchiveOnStop] = React.useState(true);
  const [duration, setDuration] = React.useState(30);
  const [durationUnit, setDurationUnit] = React.useState(1000);
  const [durationValid, setDurationValid] = React.useState(ValidatedOptions.success);
  const [templates, setTemplates] = React.useState([] as EventTemplate[]);
  const [templateName, setTemplateName] = React.useState<string | undefined>(prefilled?.templateName);
  const [templateType, setTemplateType] = React.useState<TemplateType | undefined>(prefilled?.templateType);
  const [maxAge, setMaxAge] = React.useState(0);
  const [maxAgeUnits, setMaxAgeUnits] = React.useState(1);
  const [maxSize, setMaxSize] = React.useState(0);
  const [maxSizeUnits, setMaxSizeUnits] = React.useState(1);
  const [toDisk, setToDisk] = React.useState(true);
  const [labels, setLabels] = React.useState([] as RecordingLabel[]);
  const [labelsValid, setLabelsValid] = React.useState(ValidatedOptions.default);
  const [loading, setLoading] = React.useState(false);
  const [errorMessage, setErrorMessage] = React.useState('');

  const handleCreateRecording = React.useCallback(
    (recordingAttributes: RecordingAttributes) => {
      setLoading(true);
      addSubscription(
        context.api
          .createRecording(recordingAttributes)
          .pipe(first())
          .subscribe((resp) => {
            setLoading(false);
            if (resp && resp.ok) {
              history.push('/recordings');
            }
          })
      );
    },
    [addSubscription, context.api, history, setLoading]
  );

  const handleContinuousChange = React.useCallback(
    (checked) => {
      setContinuous(checked);
      setDuration(0);
      setDurationValid(checked ? ValidatedOptions.success : ValidatedOptions.error);
    },
    [setContinuous, setDuration, setDurationValid]
  );

  const handleDurationChange = React.useCallback(
    (evt) => {
      setDuration(Number(evt));
      setDurationValid(DurationPattern.test(evt) ? ValidatedOptions.success : ValidatedOptions.error);
    },
    [setDurationValid, setDuration]
  );

  const handleDurationUnitChange = React.useCallback(
    (evt) => {
      setDurationUnit(Number(evt));
    },
    [setDurationUnit]
  );

  const handleTemplateChange = React.useCallback(
    (templateName?: string, templateType?: TemplateType) => {
      setTemplateName(templateName);
      setTemplateType(templateType);
    },
    [setTemplateName, setTemplateType]
  );

  const getEventString = React.useCallback(() => {
    var str = '';
    if (!!templateName) {
      str += `template=${templateName}`;
    }
    if (!!templateType) {
      str += `,type=${templateType}`;
    }
    return str;
  }, [templateName, templateType]);

  const getFormattedLabels = React.useCallback(() => {
    let obj = {};

    labels.forEach((l) => {
      if (!!l.key && !!l.value) {
        obj[l.key] = l.value;
      }
    });

    return obj;
  }, [labels]);

  const handleRecordingNameChange = React.useCallback(
    (name) => {
      setNameValid(RecordingNamePattern.test(name) ? ValidatedOptions.success : ValidatedOptions.error);
      setRecordingName(name);
    },
    [setNameValid, setRecordingName]
  );

  const handleMaxAgeChange = React.useCallback(
    (evt) => {
      setMaxAge(Number(evt));
    },
    [setMaxAge]
  );

  const handleMaxAgeUnitChange = React.useCallback(
    (evt) => {
      setMaxAgeUnits(Number(evt));
    },
    [setMaxAgeUnits]
  );

  const handleMaxSizeChange = React.useCallback(
    (evt) => {
      setMaxSize(Number(evt));
    },
    [setMaxSize]
  );

  const handleMaxSizeUnitChange = React.useCallback(
    (evt) => {
      setMaxSizeUnits(Number(evt));
    },
    [setMaxSizeUnits]
  );

  const handleToDiskChange = React.useCallback(
    (checked, evt) => {
      setToDisk(evt.target.checked);
    },
    [setToDisk]
  );

  const setRecordingOptions = React.useCallback(
    (options: RecordingOptions) => {
      // toDisk is not set, and defaults to true because of https://github.com/cryostatio/cryostat/issues/263
      setMaxAge(options.maxAge || 0);
      setMaxAgeUnits(1);
      setMaxSize(options.maxSize || 0);
      setMaxSizeUnits(1);
    },
    [setMaxAge, setMaxAgeUnits, setMaxSize, setMaxSizeUnits]
  );

  const handleSubmit = React.useCallback(() => {
    const notificationMessages: string[] = [];
    if (nameValid !== ValidatedOptions.success) {
      notificationMessages.push(`Recording name ${recordingName} is invalid`);
    }

    if (notificationMessages.length > 0) {
      const message = notificationMessages.join('. ').trim() + '.';
      notifications.warning('Invalid form data', message);
      return;
    }

    const options: RecordingOptions = {
      toDisk: toDisk,
      maxAge: toDisk ? (continuous ? maxAge * maxAgeUnits : undefined) : undefined,
      maxSize: toDisk ? maxSize * maxSizeUnits : undefined,
    };
    const recordingAttributes: RecordingAttributes = {
      name: recordingName,
      events: getEventString(),
      duration: continuous ? undefined : duration * (durationUnit / 1000),
      archiveOnStop: archiveOnStop && !continuous,
      options: options,
      metadata: { labels: getFormattedLabels() },
    };
    handleCreateRecording(recordingAttributes);
  }, [
    getEventString,
    getFormattedLabels,
    continuous,
    duration,
    durationUnit,
    maxAge,
    maxAgeUnits,
    maxSize,
    maxSizeUnits,
    nameValid,
    notifications,
    notifications.warning,
    recordingName,
    toDisk,
    handleCreateRecording,
  ]);

  const refeshFormOptions = React.useCallback(() => {
    addSubscription(
      context.target
        .target()
        .pipe(
          filter((target) => target !== NO_TARGET),
          first(),
          concatMap((target) =>
            forkJoin({
              templates: context.api.doGet<EventTemplate[]>(
                `targets/${encodeURIComponent(target.connectUrl)}/templates`
              ),
              recordingOptions: context.api.doGet<RecordingOptions>(
                `targets/${encodeURIComponent(target.connectUrl)}/recordingOptions`
              ),
            })
          )
        )
        .subscribe({
          next: (formOptions) => {
            setErrorMessage('');
            setTemplates(formOptions.templates);
            setRecordingOptions(formOptions.recordingOptions);
          },
          error: (error) => {
            setErrorMessage(error.message); // If both throw, first error will be shown
            setTemplates([]);
            setRecordingOptions({});
          },
        })
    );
  }, [addSubscription, context.target, context.api, setTemplates, setRecordingOptions, setErrorMessage]);

  React.useEffect(() => {
    addSubscription(
      context.target.authFailure().subscribe(() => {
        setErrorMessage(authFailMessage);
        setTemplates([]);
        setRecordingOptions({});
      })
    );
  }, [context.target, setErrorMessage, addSubscription, setTemplates, setRecordingOptions]);

  React.useEffect(() => {
    addSubscription(context.target.target().subscribe(refeshFormOptions));
  }, [addSubscription, context.target, refeshFormOptions]);

  const isFormInvalid: boolean = React.useMemo(() => {
    return (
      nameValid !== ValidatedOptions.success ||
      durationValid !== ValidatedOptions.success ||
      !templateName ||
      !templateType ||
      labelsValid !== ValidatedOptions.success
    );
  }, [nameValid, durationValid, templateName, templateType, labelsValid]);

  const hasReservedLabels = React.useMemo(
    () => labels.some((label) => label.key === 'template.name' || label.key === 'template.type'),
    [labels]
  );

  const createButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Creating',
        spinnerAriaLabel: 'create-active-recording',
        isLoading: loading,
      } as LoadingPropsType),
    [loading]
  );

  const selectedSpecifier = React.useMemo(() => {
    if (templateName && templateType) {
      return `${templateName},${templateType}`;
    }
    return '';
  }, [templateName, templateType]);

  const authRetry = React.useCallback(() => {
    context.target.setAuthRetry();
  }, [context.target]);

  if (errorMessage != '') {
    return (
      <ErrorView
        title={'Error displaying recording creation form'}
        message={errorMessage}
        retry={isAuthFail(errorMessage) ? authRetry : undefined}
      />
    );
  }
  return (
    <>
      <Text component={TextVariants.small}>
        JDK Flight Recordings are compact records of events which have occurred within the target JVM. Many event types
        are built-in to the JVM itself, while others are user-defined.
      </Text>
      <Form isHorizontal>
        <FormGroup
          label="Name"
          isRequired
          fieldId="recording-name"
          helperText="Enter a recording name. This will be unique within the target JVM"
          helperTextInvalid="A recording name may only contain letters, numbers, and underscores"
          validated={nameValid}
        >
          <TextInput
            value={recordingName}
            isRequired
            isDisabled={loading}
            type="text"
            id="recording-name"
            aria-describedby="recording-name-helper"
            onChange={handleRecordingNameChange}
            validated={nameValid}
          />
        </FormGroup>
        <FormGroup
          label="Duration"
          isRequired
          fieldId="recording-duration"
          validated={durationValid}
          helperText={
            continuous
              ? 'A continuous recording will never be automatically stopped'
              : archiveOnStop
              ? 'Time before the recording is automatically stopped and copied to archive'
              : 'Time before the recording is automatically stopped'
          }
          helperTextInvalid="A recording may only have a positive integer duration"
        >
          <Split hasGutter>
            <SplitItem>
              <Checkbox
                label="Continuous"
                isChecked={continuous}
                isDisabled={loading}
                onChange={handleContinuousChange}
                aria-label="Continuous checkbox"
                id="recording-continuous"
                name="recording-continuous"
              />
            </SplitItem>
            <SplitItem>
              <Checkbox
                label="Archive on Stop"
                isDisabled={continuous || loading}
                isChecked={archiveOnStop && !continuous}
                onChange={setArchiveOnStop}
                aria-label="ArchiveOnStop checkbox"
                id="recording-archive-on-stop"
                name="recording-archive-on-stop"
              />
            </SplitItem>
          </Split>
          <DurationPicker
            enabled={!continuous && !loading}
            period={duration}
            onPeriodChange={handleDurationChange}
            unitScalar={durationUnit}
            onUnitScalarChange={handleDurationUnitChange}
          />
        </FormGroup>
        <FormGroup
          label="Template"
          isRequired
          fieldId="recording-template"
          validated={!templateName ? ValidatedOptions.default : ValidatedOptions.success}
          helperText={'The Event Template to be applied in this recording'}
          helperTextInvalid="A Template must be selected"
        >
          <SelectTemplateSelectorForm
            selected={selectedSpecifier}
            templates={templates}
            validated={!templateName ? ValidatedOptions.default : ValidatedOptions.success}
            disabled={loading}
            onSelect={handleTemplateChange}
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
            isHelperTextBeforeField
            helperText={
              <HelperText>
                <HelperTextItem
                  isDynamic
                  variant={hasReservedLabels ? 'warning' : undefined}
                  hasIcon={hasReservedLabels}
                >
                  Labels with key <Label isCompact>template.name</Label> and <Label isCompact>template.type</Label> are
                  set by Cryostat and will be overwritten if specifed.
                </HelperTextItem>
              </HelperText>
            }
          >
            <RecordingLabelFields
              labels={labels}
              setLabels={setLabels}
              setValid={setLabelsValid}
              isDisabled={loading}
            />
          </FormGroup>
        </ExpandableSection>
        <ExpandableSection toggleTextExpanded="Hide advanced options" toggleTextCollapsed="Show advanced options">
          <Text component={TextVariants.small}>A value of 0 for maximum size or age means unbounded.</Text>
          <FormGroup
            fieldId="To Disk"
            helperText="Write contents of buffer onto disk. If disabled, the buffer acts as circular buffer only keeping the most recent recording information"
          >
            <Checkbox
              label="To Disk"
              id="toDisk-checkbox"
              isChecked={toDisk}
              onChange={handleToDiskChange}
              isDisabled={loading}
            />
          </FormGroup>
          <FormGroup
            label="Maximum size"
            fieldId="maxSize"
            helperText="The maximum size of recording data saved to disk"
          >
            <Split hasGutter={true}>
              <SplitItem isFilled>
                <TextInput
                  value={maxSize}
                  isRequired
                  type="number"
                  id="maxSize"
                  aria-label="max size value"
                  onChange={handleMaxSizeChange}
                  min="0"
                  isDisabled={!toDisk || loading}
                />
              </SplitItem>
              <SplitItem>
                <FormSelect
                  value={maxSizeUnits}
                  onChange={handleMaxSizeUnitChange}
                  aria-label="Max size units input"
                  isDisabled={!toDisk || loading}
                >
                  <FormSelectOption key="1" value="1" label="B" />
                  <FormSelectOption key="2" value={1024} label="KiB" />
                  <FormSelectOption key="3" value={1024 * 1024} label="MiB" />
                </FormSelect>
              </SplitItem>
            </Split>
          </FormGroup>
          <FormGroup label="Maximum age" fieldId="maxAge" helperText="The maximum age of recording data stored to disk">
            <Split hasGutter={true}>
              <SplitItem isFilled>
                <TextInput
                  value={maxAge}
                  isRequired
                  type="number"
                  id="maxAgeDuration"
                  aria-label="Max age duration"
                  onChange={handleMaxAgeChange}
                  min="0"
                  isDisabled={!continuous || !toDisk || loading}
                />
              </SplitItem>
              <SplitItem>
                <FormSelect
                  value={maxAgeUnits}
                  onChange={handleMaxAgeUnitChange}
                  aria-label="Max Age units Input"
                  isDisabled={!continuous || !toDisk || loading}
                >
                  <FormSelectOption key="1" value="1" label="Seconds" />
                  <FormSelectOption key="2" value={60} label="Minutes" />
                  <FormSelectOption key="3" value={60 * 60} label="Hours" />
                </FormSelect>
              </SplitItem>
            </Split>
          </FormGroup>
        </ExpandableSection>
        <ActionGroup>
          <Button
            variant="primary"
            onClick={handleSubmit}
            isDisabled={isFormInvalid || loading}
            {...createButtonLoadingProps}
          >
            {loading ? 'Creating' : 'Create'}
          </Button>
          <Button variant="secondary" onClick={history.goBack} isDisabled={loading}>
            Cancel
          </Button>
        </ActionGroup>
      </Form>
    </>
  );
};
