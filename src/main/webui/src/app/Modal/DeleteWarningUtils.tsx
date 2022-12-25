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
export enum DeleteWarningType {
  DeleteActiveRecordings = 'DeleteActiveRecordings',
  DeleteArchivedRecordings = 'DeleteArchivedRecordings',
  DeleteAutomatedRules = 'DeleteAutomatedRules',
  DeleteEventTemplates = 'DeleteEventTemplates',
  DeleteProbeTemplates = 'DeleteProbeTemplates',
  DeleteActiveProbes = 'DeleteActiveProbes',
  DeleteJMXCredentials = 'DeleteJMXCredentials',
  DeleteCustomTargets = 'DeleteCustomTargets',
}

export interface DeleteWarning {
  id: DeleteWarningType;
  title: string;
  label: string;
  description: string;
  ariaLabel: string;
}

export const DeleteActiveRecordings: DeleteWarning = {
  id: DeleteWarningType.DeleteActiveRecordings,
  title: 'Permanently delete Active Recording?',
  label: 'Delete Active Recording',
  description: `Recording and report data will be lost.`,
  ariaLabel: 'Recording delete warning',
};

export const DeleteArchivedRecordings: DeleteWarning = {
  id: DeleteWarningType.DeleteArchivedRecordings,
  title: 'Permanently delete Archived Recording?',
  label: 'Delete Archived Recording',
  description: `Recording and report data will be lost.`,
  ariaLabel: 'Recording delete warning',
};

export const DeleteAutomatedRules: DeleteWarning = {
  id: DeleteWarningType.DeleteAutomatedRules,
  title: 'Permanently delete Automated Rule?',
  label: 'Delete Automated Rule',
  description: `Rule data will be lost.`,
  ariaLabel: 'Automated rule delete warning',
};

export const DeleteEventTemplates: DeleteWarning = {
  id: DeleteWarningType.DeleteEventTemplates,
  title: 'Permanently delete Event Template?',
  label: 'Delete Event Template',
  description: `Custom event template data will be lost.`,
  ariaLabel: 'Event template delete warning',
};

export const DeleteProbeTemplates: DeleteWarning = {
  id: DeleteWarningType.DeleteProbeTemplates,
  title: 'Permanently delete Probe Template?',
  label: 'Delete Probe Template',
  description: `Custom probe template data will be lost.`,
  ariaLabel: 'Probe template delete warning',
};

export const DeleteActiveProbes: DeleteWarning = {
  id: DeleteWarningType.DeleteActiveProbes,
  title: 'Permanently remove Active Probes from the target?',
  label: 'Remove Active Probes',
  description: `Active probes will be removed from the target.`,
  ariaLabel: 'Active Probes remove warning',
};

export const DeleteJMXCredentials: DeleteWarning = {
  id: DeleteWarningType.DeleteJMXCredentials,
  title: 'Permanently delete JMX Credentials?',
  label: 'Delete JMX Credentials',
  description: `Credential data for this target will be lost.`,
  ariaLabel: 'JMX Credentials delete warning',
};

export const DeleteCustomTargets: DeleteWarning = {
  id: DeleteWarningType.DeleteCustomTargets,
  title: 'Permanently delete Custom Target?',
  label: 'Delete Custom Targets',
  description: `Custom target information will be lost.`,
  ariaLabel: 'Custom Targets delete warning',
};

export const DeleteWarningKinds: DeleteWarning[] = [
  DeleteActiveRecordings,
  DeleteArchivedRecordings,
  DeleteAutomatedRules,
  DeleteEventTemplates,
  DeleteProbeTemplates,
  DeleteActiveProbes,
  DeleteJMXCredentials,
  DeleteCustomTargets,
];

export const getFromWarningMap = (warning: DeleteWarningType): DeleteWarning | undefined => {
  const wt = DeleteWarningKinds.find((t) => t.id === warning);
  return wt;
};
