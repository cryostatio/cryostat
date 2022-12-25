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

const SECOND_MILLIS = 1000;
const MINUTE_MILLIS = 60 * SECOND_MILLIS;
const HOUR_MILLIS = 60 * MINUTE_MILLIS;
const DAY_MILLIS = 24 * HOUR_MILLIS;

export const createBlobURL = (content: any, contentType: string, timeout: number = 1000) => {
  const blob = new Blob([content], { type: contentType });
  const url = window.URL.createObjectURL(blob);
  setTimeout(() => window.URL.revokeObjectURL(url), timeout);
  return url;
};

export function accessibleRouteChangeHandler() {
  return window.setTimeout(() => {
    const mainContainer = document.getElementById('primary-app-container');
    if (mainContainer) {
      mainContainer.focus();
    }
  }, 50);
}

export const hashCode = (str: string): number => {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0; // Force 32-bit number
  }
  return hash;
};

export const sizeUnits = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];

export const formatBytes = (bytes: number, decimals = 2): string => {
  if (bytes <= 0) return '0 B';

  const k = 1024; // 1 KB
  const dm = Math.max(decimals, 0); // Decimal places

  const i = Math.floor(Math.log(bytes) / Math.log(k));

  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizeUnits[i]}`;
};

export interface AutomatedAnalysisTimerObject {
  quantity: number;
  unit: string;
  interval: number;
}

export const calculateAnalysisTimer = (reportTime: number): AutomatedAnalysisTimerObject => {
  let interval, timerQuantity, timerUnits;
  let now = Date.now();
  const reportMillis = now - reportTime;
  if (reportMillis < MINUTE_MILLIS) {
    timerQuantity = Math.round(reportMillis / SECOND_MILLIS);
    interval = SECOND_MILLIS - (reportMillis % SECOND_MILLIS);
    timerUnits = 'second';
  } else if (reportMillis < HOUR_MILLIS) {
    timerQuantity = Math.round(reportMillis / MINUTE_MILLIS);
    interval = MINUTE_MILLIS - (reportMillis % MINUTE_MILLIS);
    timerUnits = 'minute';
  } else if (reportMillis < DAY_MILLIS) {
    timerQuantity = Math.round(reportMillis / HOUR_MILLIS);
    interval = HOUR_MILLIS - (reportMillis % HOUR_MILLIS);
    timerUnits = 'hour';
  } else {
    timerQuantity = Math.round(reportMillis / DAY_MILLIS);
    interval = DAY_MILLIS - reportMillis * DAY_MILLIS;
    timerUnits = 'day';
  }
  return {
    quantity: timerQuantity,
    unit: timerUnits,
    interval: interval,
  } as AutomatedAnalysisTimerObject;
};
