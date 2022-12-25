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
import { ServiceContext } from '@app/Shared/Services/Services';
import { NotificationsContext } from '@app/Notifications/Notifications';
import { isEqualTarget, NO_TARGET, Target } from '@app/Shared/Services/Target.service';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import {
  Button,
  Card,
  CardActions,
  CardBody,
  CardExpandableContent,
  CardHeader,
  CardTitle,
  Select,
  SelectOption,
  SelectVariant,
} from '@patternfly/react-core';
import { ContainerNodeIcon, PlusCircleIcon, TrashIcon } from '@patternfly/react-icons';
import { CreateTargetModal } from './CreateTargetModal';
import { DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import { DeleteWarningModal } from '@app/Modal/DeleteWarningModal';
import { getFromLocalStorage, removeFromLocalStorage, saveToLocalStorage } from '@app/utils/LocalStorage';
import { SerializedTarget } from '@app/Shared/SerializedTarget';
import { NoTargetSelected } from '@app/TargetView/NoTargetSelected';
import { first } from 'rxjs';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';

export const CUSTOM_TARGETS_REALM = 'Custom Targets';

export interface TargetSelectProps {
  // display a simple, non-expandable component. set this if the view elsewhere
  // contains a <SerializedTarget /> or other repeated components
  simple?: boolean;
}

export const TargetSelect: React.FunctionComponent<TargetSelectProps> = (props) => {
  const notifications = React.useContext(NotificationsContext);
  const context = React.useContext(ServiceContext);
  const addSubscription = useSubscriptions();

  const [isExpanded, setExpanded] = React.useState(false);
  const [selected, setSelected] = React.useState(NO_TARGET);
  const [targets, setTargets] = React.useState([] as Target[]);
  const [isDropdownOpen, setDropdownOpen] = React.useState(false);
  const [isLoading, setLoading] = React.useState(false);
  const [isModalOpen, setModalOpen] = React.useState(false);
  const [warningModalOpen, setWarningModalOpen] = React.useState(false);

  const setCachedTargetSelection = React.useCallback(
    (target, errorCallback?) => saveToLocalStorage('TARGET', target, errorCallback),
    []
  );

  const removeCachedTargetSelection = React.useCallback(() => removeFromLocalStorage('TARGET'), []);

  const getCachedTargetSelection = React.useCallback(() => getFromLocalStorage('TARGET', NO_TARGET), []);

  const resetTargetSelection = React.useCallback(() => {
    context.target.setTarget(NO_TARGET);
    removeCachedTargetSelection();
  }, [context.target, removeCachedTargetSelection]);

  const onExpand = React.useCallback(() => {
    setExpanded((v) => !v);
  }, [setExpanded]);

  const onSelect = React.useCallback(
    // ATTENTION: do not add onSelect as deps for effect hook as it updates with selected states
    (evt, selection, isPlaceholder) => {
      if (isPlaceholder) {
        resetTargetSelection();
      } else {
        if (!isEqualTarget(selection, selected)) {
          context.target.setTarget(selection);
          setCachedTargetSelection(selection, () => {
            notifications.danger('Cannot set target');
            context.target.setTarget(NO_TARGET);
          });
        }
      }
      setDropdownOpen(false);
    },
    [context.target, notifications, setDropdownOpen, setCachedTargetSelection, resetTargetSelection, selected]
  );

  const selectTargetFromCache = React.useCallback(
    (targets) => {
      if (!targets.length) {
        // Ignore first emitted value
        return;
      }
      const cachedTarget = getCachedTargetSelection();
      const cachedTargetExists = targets.some((target: Target) => isEqualTarget(cachedTarget, target));
      if (cachedTargetExists) {
        context.target.setTarget(cachedTarget);
      } else {
        resetTargetSelection();
      }
    },
    [context.target, getCachedTargetSelection, resetTargetSelection]
  );

  React.useEffect(() => {
    addSubscription(
      context.targets.targets().subscribe((targets) => {
        // Target Discovery notifications will trigger an event here.
        setTargets(targets);
        selectTargetFromCache(targets);
      })
    );
  }, [addSubscription, context.targets, setTargets, selectTargetFromCache]);

  React.useEffect(() => {
    addSubscription(context.target.target().subscribe(setSelected));
  }, [addSubscription, context.target, setSelected]);

  const refreshTargetList = React.useCallback(() => {
    setLoading(true);
    addSubscription(context.targets.queryForTargets().subscribe(() => setLoading(false)));
  }, [addSubscription, context.targets, setLoading]);

  React.useEffect(() => {
    if (!context.settings.autoRefreshEnabled()) {
      return;
    }
    const id = window.setInterval(
      () => refreshTargetList(),
      context.settings.autoRefreshPeriod() * context.settings.autoRefreshUnits()
    );
    return () => window.clearInterval(id);
  }, [context.settings, refreshTargetList]);

  const showCreateTargetModal = React.useCallback(() => {
    setModalOpen(true);
  }, [setModalOpen]);

  const closeCreateTargetModal = React.useCallback(() => {
    setModalOpen(false);
  }, [setModalOpen]);

  const deleteTarget = React.useCallback(() => {
    setLoading(true);
    addSubscription(
      context.api.deleteTarget(selected).subscribe((ok) => {
        setLoading(false);
        if (!ok) {
          const id =
            !selected.alias || selected.alias === selected.connectUrl
              ? selected.connectUrl
              : `${selected.alias} [${selected.connectUrl}]`;
          notifications.danger('Target Deletion Failed', `The selected target (${id}) could not be deleted`);
        }
      })
    );
  }, [addSubscription, context.api, notifications, selected, setLoading]);

  const deletionDialogsEnabled = React.useMemo(
    () => context.settings.deletionDialogsEnabledFor(DeleteWarningType.DeleteCustomTargets),
    [context.settings]
  );

  const handleDeleteButton = React.useCallback(() => {
    if (deletionDialogsEnabled) {
      setWarningModalOpen(true);
    } else {
      deleteTarget();
    }
  }, [deletionDialogsEnabled, setWarningModalOpen, deleteTarget]);

  const handleWarningModalClose = React.useCallback(() => {
    setWarningModalOpen(false);
  }, [setWarningModalOpen]);

  const handleCreateModalClose = React.useCallback(() => {
    setModalOpen(false);
  }, [setModalOpen]);

  const deleteArchivedWarningModal = React.useMemo(() => {
    return (
      <DeleteWarningModal
        warningType={DeleteWarningType.DeleteCustomTargets}
        visible={warningModalOpen}
        onAccept={deleteTarget}
        onClose={handleWarningModalClose}
      />
    );
  }, [warningModalOpen, deleteTarget, handleWarningModalClose]);

  const selectOptions = React.useMemo(
    () =>
      [
        <SelectOption key="placeholder" value="Select target..." isPlaceholder={true} itemCount={targets.length} />,
      ].concat(
        targets.map((t: Target) => (
          <SelectOption key={t.connectUrl} value={t} isPlaceholder={false}>
            {!t.alias || t.alias === t.connectUrl ? `${t.connectUrl}` : `${t.alias} (${t.connectUrl})`}
          </SelectOption>
        ))
      ),
    [targets]
  );

  const cardHeaderProps = React.useMemo(
    () =>
      props.simple
        ? {}
        : {
            onExpand: onExpand,
            toggleButtonProps: {
              id: 'target-select-expand-button',
              'aria-label': 'Details',
              'aria-labelledby': 'expandable-card-title target-select-expand-button',
              'aria-expanded': isExpanded,
            },
          },
    [props.simple, onExpand, isExpanded]
  );

  const deleteButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Deleting',
        spinnerAriaLabel: 'deleting-custom-target',
        isLoading: isLoading,
      } as LoadingPropsType),
    [isLoading]
  );

  return (
    <>
      <Card isRounded isCompact isExpanded={isExpanded}>
        <CardHeader {...cardHeaderProps}>
          <CardTitle>Target JVM</CardTitle>
          <CardActions>
            <Button
              aria-label="Create target"
              isDisabled={isLoading}
              onClick={showCreateTargetModal}
              variant="control"
              icon={<PlusCircleIcon />}
            />
            <Button
              aria-label="Delete target"
              isDisabled={
                isLoading || selected == NO_TARGET || selected.annotations?.cryostat['REALM'] !== CUSTOM_TARGETS_REALM
              }
              onClick={handleDeleteButton}
              variant="control"
              icon={<TrashIcon />}
              {...deleteButtonLoadingProps}
            />
          </CardActions>
        </CardHeader>
        <CardBody>
          <Select
            toggleIcon={<ContainerNodeIcon />}
            variant={SelectVariant.single}
            onSelect={onSelect}
            onToggle={setDropdownOpen}
            selections={selected.alias || selected.connectUrl}
            isDisabled={isLoading}
            isOpen={isDropdownOpen}
            aria-label="Select Target"
          >
            {selectOptions}
          </Select>
        </CardBody>
        <CardExpandableContent>
          <CardBody>{selected === NO_TARGET ? <NoTargetSelected /> : <SerializedTarget target={selected} />}</CardBody>
        </CardExpandableContent>
      </Card>
      <CreateTargetModal
        visible={isModalOpen}
        onSuccess={closeCreateTargetModal}
        onDismiss={handleCreateModalClose}
      ></CreateTargetModal>
      {deleteArchivedWarningModal}
    </>
  );
};
