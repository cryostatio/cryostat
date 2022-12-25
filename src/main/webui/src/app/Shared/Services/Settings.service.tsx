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

import { DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import { getFromLocalStorage, saveToLocalStorage } from '@app/utils/LocalStorage';
import { BehaviorSubject, Observable } from 'rxjs';
import {
  AutomatedAnalysisRecordingConfig,
  automatedAnalysisRecordingName,
  defaultAutomatedAnalysisRecordingConfig,
  RecordingAttributes,
} from './Api.service';
import { NotificationCategory } from './NotificationChannel.service';

export enum FeatureLevel {
  DEVELOPMENT = 0,
  BETA = 1,
  PRODUCTION = 2,
}

export function enumKeys<O extends Object, K extends keyof O = keyof O>(obj: O): K[] {
  return Object.keys(obj).filter((k) => Number.isNaN(+k)) as K[];
}

export const automatedAnalysisConfigToRecordingAttributes = (
  config: AutomatedAnalysisRecordingConfig
): RecordingAttributes => {
  return {
    name: automatedAnalysisRecordingName,
    events: config.template,
    duration: undefined,
    archiveOnStop: false,
    options: {
      toDisk: true,
      maxAge: config.maxAge,
      maxSize: config.maxSize,
    },
    metadata: {
      labels: {
        origin: automatedAnalysisRecordingName,
      },
    },
  } as RecordingAttributes;
};

export class SettingsService {
  private readonly _featureLevel$ = new BehaviorSubject<FeatureLevel>(
    getFromLocalStorage('FEATURE_LEVEL', FeatureLevel.PRODUCTION)
  );

  constructor() {
    this._featureLevel$.subscribe((featureLevel: FeatureLevel) => saveToLocalStorage('FEATURE_LEVEL', featureLevel));
  }

  featureLevel(): Observable<FeatureLevel> {
    return this._featureLevel$.asObservable();
  }

  setFeatureLevel(featureLevel: FeatureLevel): void {
    this._featureLevel$.next(featureLevel);
  }

  autoRefreshEnabled(): boolean {
    return getFromLocalStorage('AUTO_REFRESH_ENABLED', 'false') === 'true';
  }

  setAutoRefreshEnabled(enabled: boolean): void {
    saveToLocalStorage('AUTO_REFRESH_ENABLED', String(enabled));
  }

  autoRefreshPeriod(defaultPeriod = 30): number {
    return Number(getFromLocalStorage('AUTO_REFRESH_PERIOD', defaultPeriod));
  }

  setAutoRefreshPeriod(period: number): void {
    saveToLocalStorage('AUTO_REFRESH_PERIOD', String(period));
  }

  autoRefreshUnits(defaultUnits = 1000): number {
    return Number(getFromLocalStorage('AUTO_REFRESH_UNITS', defaultUnits));
  }

  setAutoRefreshUnits(units: number): void {
    saveToLocalStorage('AUTO_REFRESH_UNITS', String(units));
  }

  automatedAnalysisRecordingConfig(
    defaultConfig = defaultAutomatedAnalysisRecordingConfig
  ): AutomatedAnalysisRecordingConfig {
    return getFromLocalStorage('AUTOMATED_ANALYSIS_RECORDING_CONFIG', defaultConfig);
  }

  setAutomatedAnalysisRecordingConfig(config: AutomatedAnalysisRecordingConfig): void {
    saveToLocalStorage('AUTOMATED_ANALYSIS_RECORDING_CONFIG', config);
  }

  deletionDialogsEnabled(): Map<DeleteWarningType, boolean> {
    const value = getFromLocalStorage('DELETION_DIALOGS_ENABLED', undefined);
    if (typeof value === 'object') {
      const obj = new Map(Array.from(Object.entries(value)));
      const res = new Map<DeleteWarningType, boolean>();
      obj.forEach((v: any) => {
        res.set(v[0] as DeleteWarningType, v[1] as boolean);
      });
      for (const t in DeleteWarningType) {
        if (!res.has(DeleteWarningType[t])) {
          res.set(DeleteWarningType[t], true);
        }
      }
      return res;
    }

    const map = new Map<DeleteWarningType, boolean>();
    for (const cat in DeleteWarningType) {
      map.set(DeleteWarningType[cat], true);
    }
    this.setDeletionDialogsEnabled(map);
    return map;
  }

  deletionDialogsEnabledFor(type: DeleteWarningType): boolean {
    const res = this.deletionDialogsEnabled().get(type);
    if (typeof res != 'boolean') {
      return true;
    }
    return res;
  }

  setDeletionDialogsEnabled(map: Map<DeleteWarningType, boolean>): void {
    const value = Array.from(map.entries());
    saveToLocalStorage('DELETION_DIALOGS_ENABLED', value);
  }

  setDeletionDialogsEnabledFor(type: DeleteWarningType, enabled: boolean) {
    const map = this.deletionDialogsEnabled();
    map.set(type, enabled);
    this.setDeletionDialogsEnabled(map);
  }

  visibleNotificationsCount(): number {
    return getFromLocalStorage('VISIBLE_NOTIFICATIONS_COUNT', 5);
  }

  setVisibleNotificationCount(count: number): void {
    saveToLocalStorage('VISIBLE_NOTIFICATIONS_COUNT', count);
  }

  notificationsEnabled(): Map<NotificationCategory, boolean> {
    const value = getFromLocalStorage('NOTIFICATIONS_ENABLED', undefined);
    if (typeof value === 'object') {
      const obj = new Map(Array.from(Object.entries(value)));
      const res = new Map<NotificationCategory, boolean>();
      obj.forEach((v: any) => {
        res.set(v[0] as NotificationCategory, v[1] as boolean);
      });
      for (const t in NotificationCategory) {
        if (!res.has(NotificationCategory[t])) {
          res.set(NotificationCategory[t], true);
        }
      }
      return res;
    }
    const map = new Map<NotificationCategory, boolean>();
    for (const cat in NotificationCategory) {
      map.set(NotificationCategory[cat], true);
    }
    this.setNotificationsEnabled(map);
    return map;
  }

  notificationsEnabledFor(category: NotificationCategory): boolean {
    const res = this.notificationsEnabled().get(category);
    if (typeof res != 'boolean') {
      return true;
    }
    return res;
  }

  setNotificationsEnabled(map: Map<NotificationCategory, boolean>): void {
    const value = Array.from(map.entries());
    saveToLocalStorage('NOTIFICATIONS_ENABLED', value);
  }

  webSocketDebounceMs(defaultWebSocketDebounceMs = 100): number {
    return Number(getFromLocalStorage('WEBSOCKET_DEBOUNCE_MS', defaultWebSocketDebounceMs));
  }

  setWebSocketDebounceMs(debounce: number): void {
    saveToLocalStorage('WEBSOCKET_DEBOUNCE_MS', String(debounce));
  }
}
