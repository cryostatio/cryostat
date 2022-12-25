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
import { EventTemplate } from '@app/CreateRecording/CreateRecording';
import { authFailMessage, ErrorView, isAuthFail } from '@app/ErrorView/ErrorView';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import {
  AutomatedAnalysisRecordingConfig,
  automatedAnalysisRecordingName,
  TemplateType,
} from '@app/Shared/Services/Api.service';
import { ServiceContext } from '@app/Shared/Services/Services';
import { automatedAnalysisConfigToRecordingAttributes } from '@app/Shared/Services/Settings.service';
import { NO_TARGET } from '@app/Shared/Services/Target.service';
import { SelectTemplateSelectorForm } from '@app/TemplateSelector/SelectTemplateSelectorForm';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import {
  ActionGroup,
  Button,
  Form,
  FormGroup,
  FormSection,
  FormSelect,
  FormSelectOption,
  HelperText,
  HelperTextItem,
  Split,
  SplitItem,
  Text,
  TextInput,
  TextVariants,
  ValidatedOptions,
} from '@patternfly/react-core';
import { CogIcon } from '@patternfly/react-icons';
import * as React from 'react';
import { Link } from 'react-router-dom';
import { concatMap, filter, first } from 'rxjs';
interface AutomatedAnalysisConfigFormProps {
  onCreate?: () => void;
  onSave?: () => void;
  isSettingsForm: boolean;
}

export const AutomatedAnalysisConfigForm: React.FunctionComponent<AutomatedAnalysisConfigFormProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const addSubscription = useSubscriptions();

  const [templates, setTemplates] = React.useState([] as EventTemplate[]);
  const [templateName, setTemplateName] = React.useState<string | undefined>(undefined);
  const [templateType, setTemplateType] = React.useState<TemplateType | undefined>(undefined);
  const [maxAge, setMaxAge] = React.useState(context.settings.automatedAnalysisRecordingConfig().maxAge);
  const [maxAgeUnits, setMaxAgeUnits] = React.useState(1);
  const [maxSize, setMaxSize] = React.useState(context.settings.automatedAnalysisRecordingConfig().maxSize);
  const [maxSizeUnits, setMaxSizeUnits] = React.useState(1);
  const [isLoading, setIsLoading] = React.useState(false);
  const [isSaveLoading, setIsSaveLoading] = React.useState(false);
  const [showHelperMessage, setShowHelperMessage] = React.useState(false);
  const [errorMessage, setErrorMessage] = React.useState('');

  const createButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Creating',
        spinnerAriaLabel: 'create-active-recording',
        isLoading: isLoading,
      } as LoadingPropsType),
    [isLoading]
  );

  const saveButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Saving',
        spinnerAriaLabel: 'save-config-settings',
        isLoading: isSaveLoading,
      } as LoadingPropsType),
    [isSaveLoading]
  );

  const isFormInvalid: boolean = React.useMemo(() => {
    return !templateName || !templateType;
  }, [templateName, templateType]);

  const refreshTemplates = React.useCallback(() => {
    addSubscription(
      context.target
        .target()
        .pipe(
          filter((target) => target !== NO_TARGET),
          first(),
          concatMap((target) =>
            context.api
              .doGet<EventTemplate[]>(`targets/${encodeURIComponent(target.connectUrl)}/templates`)
              .pipe(first())
          )
        )
        .subscribe({
          next: (templates) => {
            setErrorMessage('');
            setTemplates(templates);
          },
          error: (err) => {
            setErrorMessage(err.message);
            setTemplates([]);
          },
        })
    );
  }, [addSubscription, context.target, context.api, setErrorMessage, setTemplates]);

  React.useEffect(() => {
    addSubscription(
      context.target.authFailure().subscribe(() => {
        setErrorMessage(authFailMessage);
        setTemplates([]);
      })
    );
  }, [addSubscription, context.target, setErrorMessage, setTemplates]);

  React.useEffect(() => {
    addSubscription(context.target.target().subscribe(refreshTemplates));
  }, [addSubscription, context.target, refreshTemplates]);

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

  const handleSubmit = React.useCallback(() => {
    setIsLoading(true);
    const config: AutomatedAnalysisRecordingConfig = {
      template: getEventString(),
      maxAge: maxAge * maxAgeUnits,
      maxSize: maxSize * maxSizeUnits,
    };
    const recordingAttributes = automatedAnalysisConfigToRecordingAttributes(config);
    addSubscription(
      context.api.createRecording(recordingAttributes).subscribe({
        next: (resp) => {
          if (resp && resp.ok && props.onCreate) {
            props.onCreate();
          }
          setIsLoading(false);
        },
        error: (err) => {
          setIsLoading(false);
        },
      })
    );
  }, [
    addSubscription,
    context.api,
    setIsLoading,
    getEventString,
    props.onCreate,
    maxAge,
    maxAgeUnits,
    maxSize,
    maxSizeUnits,
  ]);

  const handleSaveConfig = React.useCallback(() => {
    const options: AutomatedAnalysisRecordingConfig = {
      template: getEventString(),
      maxAge: maxAge * maxAgeUnits,
      maxSize: maxSize * maxSizeUnits,
    };
    context.settings.setAutomatedAnalysisRecordingConfig(options);
    setIsSaveLoading(true);
    const timer = setTimeout(() => {
      setShowHelperMessage(true);
      if (props.onSave) {
        props.onSave();
      }
      setIsSaveLoading(false);
    }, 500);
    return () => clearInterval(timer);
  }, [
    getEventString,
    setIsSaveLoading,
    setShowHelperMessage,
    props.onSave,
    maxAge,
    maxAgeUnits,
    maxSize,
    maxSizeUnits,
    context.settings,
    context.settings.setAutomatedAnalysisRecordingConfig,
  ]);

  const authRetry = React.useCallback(() => {
    context.target.setAuthRetry();
  }, [context.target]);

  const selectedSpecifier = React.useMemo(() => {
    if (templateName && templateType) {
      return `${templateName},${templateType}`;
    }
    return '';
  }, [templateName, templateType]);

  if (errorMessage != '') {
    return (
      <ErrorView
        title={'Error displaying recording configuration settings'}
        message={errorMessage}
        retry={isAuthFail(errorMessage) ? authRetry : undefined}
      />
    );
  }
  return (
    <Form isHorizontal={props.isSettingsForm}>
      <FormSection title={props.isSettingsForm ? undefined : 'Profiling Recording Configuration'}>
        <FormGroup
          label="Template"
          isRequired
          fieldId="recording-template"
          validated={!templateName ? ValidatedOptions.default : ValidatedOptions.success}
          helperText="The Event Template to be applied in this recording"
          helperTextInvalid="A Template must be selected"
        >
          <SelectTemplateSelectorForm
            templates={templates}
            validated={!templateName ? ValidatedOptions.default : ValidatedOptions.success}
            disabled={isLoading || isSaveLoading}
            onSelect={handleTemplateChange}
            selected={selectedSpecifier}
          />
        </FormGroup>
        <FormGroup label="Maximum size" fieldId="maxSize" helperText="The maximum size of recording data saved to disk">
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
                isDisabled={isLoading || isSaveLoading}
              />
            </SplitItem>
            <SplitItem>
              <FormSelect
                value={maxSizeUnits}
                onChange={handleMaxSizeUnitChange}
                aria-label="Max size units input"
                isDisabled={isLoading || isSaveLoading}
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
                isDisabled={isLoading || isSaveLoading}
              />
            </SplitItem>
            <SplitItem>
              <FormSelect
                value={maxAgeUnits}
                onChange={handleMaxAgeUnitChange}
                aria-label="Max Age units Input"
                isDisabled={isLoading || isSaveLoading}
              >
                <FormSelectOption key="1" value="1" label="Seconds" />
                <FormSelectOption key="2" value={60} label="Minutes" />
                <FormSelectOption key="3" value={60 * 60} label="Hours" />
              </FormSelect>
            </SplitItem>
          </Split>
        </FormGroup>
        <ActionGroup>
          {props.isSettingsForm || (
            <Button
              variant="primary"
              onClick={handleSubmit}
              isDisabled={isFormInvalid || isLoading}
              {...createButtonLoadingProps}
            >
              {isLoading ? 'Creating' : 'Create'}
            </Button>
          )}
          <Button
            variant={props.isSettingsForm ? 'primary' : 'secondary'}
            onClick={handleSaveConfig}
            isDisabled={isFormInvalid || isSaveLoading}
            {...saveButtonLoadingProps}
          >
            {isSaveLoading ? 'Saving' : 'Save configuration'}
          </Button>
          {showHelperMessage && !isSaveLoading && (
            <HelperText className={`${automatedAnalysisRecordingName}-config-save-helper`}>
              <HelperTextItem variant="success">
                <Text component={TextVariants.p}>Automated analysis recording configuration saved.</Text>
                {!props.isSettingsForm && (
                  <Text>
                    You can also change this in the&nbsp;
                    <Link to="/settings">
                      <CogIcon /> Settings
                    </Link>
                    &nbsp;view.
                  </Text>
                )}
              </HelperTextItem>
            </HelperText>
          )}
          {templateType == 'TARGET' && (
            <HelperText className={`${automatedAnalysisRecordingName}-config-save-template-warning-helper`}>
              <HelperTextItem variant="warning">
                <Text component={TextVariants.p}>
                  WARNING: Setting a Target Template as a default template type configuration may not apply to all
                  Target JVMs if they do not support them.
                </Text>
              </HelperTextItem>
            </HelperText>
          )}
        </ActionGroup>
      </FormSection>
    </Form>
  );
};
