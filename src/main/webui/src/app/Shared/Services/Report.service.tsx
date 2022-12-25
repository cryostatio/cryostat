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
import { Observable, from, of, throwError } from 'rxjs';
import { fromFetch } from 'rxjs/fetch';
import { concatMap, first, tap } from 'rxjs/operators';
import { isActiveRecording, RecordingState, Recording } from './Api.service';
import { Notifications } from '@app/Notifications/Notifications';
import { Base64 } from 'js-base64';
import { LoginService } from './Login.service';

export class ReportService {
  constructor(private login: LoginService, private notifications: Notifications) {}

  report(recording: Recording): Observable<string> {
    if (!recording.reportUrl) {
      return throwError(() => new Error('No recording report URL'));
    }
    let stored = sessionStorage.getItem(this.key(recording));
    if (!!stored) {
      return of(stored);
    }
    return this.login.getHeaders().pipe(
      concatMap((headers) =>
        fromFetch(recording.reportUrl, {
          method: 'GET',
          mode: 'cors',
          credentials: 'include',
          headers,
        })
      ),
      concatMap((resp) => {
        if (resp.ok) {
          return from(resp.text());
        } else {
          const ge: GenerationError = {
            name: `Report Failure (${recording.name})`,
            message: resp.statusText,
            messageDetail: from(resp.text()),
            status: resp.status,
          };
          throw ge;
        }
      }),
      tap({
        next: (report) => {
          const isArchived = !isActiveRecording(recording);
          const isActiveStopped = !isArchived && recording.state === RecordingState.STOPPED;
          if (isArchived || isActiveStopped) {
            try {
              sessionStorage.setItem(this.key(recording), report);
            } catch (error) {
              this.notifications.warning('Report Caching Failed', (error as any).message);
              this.delete(recording);
            }
          }
        },
        error: (err) => {
          if (isGenerationError(err) && err.status >= 500) {
            err.messageDetail.pipe(first()).subscribe((detail) => {
              this.notifications.warning(`Report generation failure: ${detail}`);
              sessionStorage.setItem(this.key(recording), `<p>${detail}</p>`);
            });
          } else {
            this.notifications.danger(err.name, err.message);
          }
        },
      })
    );
  }

  reportJson(recording: Recording, connectUrl: string): Observable<RuleEvaluation[]> {
    if (!recording.reportUrl) {
      return throwError(() => new Error('No recording report URL'));
    }
    return this.login.getHeaders().pipe(
      concatMap((headers) => {
        headers.append('Accept', 'application/json');
        return fromFetch(recording.reportUrl, {
          method: 'GET',
          mode: 'cors',
          credentials: 'include',
          headers,
        });
      }),
      concatMap((resp) => {
        if (resp.ok) {
          return from(
            resp
              .text()
              .then(JSON.parse)
              .then((obj) => Object.values(obj) as RuleEvaluation[])
          );
        } else {
          const ge: GenerationError = {
            name: `Report Failure (${recording.name})`,
            message: resp.statusText,
            messageDetail: from(resp.text()),
            status: resp.status,
          };
          throw ge;
        }
      }),
      tap({
        next: (report) => {
          if (isActiveRecording(recording)) {
            try {
              sessionStorage.setItem(this.analysisKey(connectUrl), JSON.stringify(report));
              sessionStorage.setItem(this.analysisKeyTimestamp(connectUrl), Date.now().toString());
            } catch (error) {
              this.notifications.warning('Report Caching Failed', (error as any).message);
              this.deleteCachedAnalysisReport(connectUrl);
            }
          }
        },
        error: (err) => {
          if (isGenerationError(err) && err.status >= 500) {
            err.messageDetail.pipe(first()).subscribe((detail) => {
              this.notifications.warning(`Report generation failure: ${detail}`);
              this.deleteCachedAnalysisReport(connectUrl);
            });
          } else {
            this.notifications.danger(err.name, err.message);
          }
        },
      })
    );
  }

  getCachedAnalysisReport(connectUrl: string): CachedReportValue {
    let stored = sessionStorage.getItem(this.analysisKey(connectUrl));
    let storedTimestamp = Number(sessionStorage.getItem(this.analysisKeyTimestamp(connectUrl)));
    if (!!stored) {
      return {
        report: JSON.parse(stored),
        timestamp: storedTimestamp || 0,
      };
    }
    return {
      report: [],
      timestamp: 0,
    };
  }

  delete(recording: Recording): void {
    sessionStorage.removeItem(this.key(recording));
  }

  deleteCachedAnalysisReport(connectUrl: string): void {
    sessionStorage.removeItem(this.analysisKey(connectUrl));
    sessionStorage.removeItem(this.analysisKeyTimestamp(connectUrl));
  }

  private key(recording: Recording): string {
    return Base64.encode(`report.${recording.reportUrl}`);
  }

  private analysisKey(connectUrl: string): string {
    return Base64.encode(`${connectUrl}.latestReport`);
  }

  private analysisKeyTimestamp(connectUrl: string): string {
    return Base64.encode(`${connectUrl}.latestReportTimestamp`);
  }
}

export interface CachedReportValue {
  report: RuleEvaluation[];
  timestamp: number;
}

export type CategorizedRuleEvaluations = [string, RuleEvaluation[]];

export type GenerationError = Error & {
  status: number;
  messageDetail: Observable<string>;
};

export const isGenerationError = (toCheck: any): toCheck is GenerationError => {
  if ((toCheck as GenerationError).name === undefined) {
    return false;
  }
  if ((toCheck as GenerationError).message === undefined) {
    return false;
  }
  if ((toCheck as GenerationError).messageDetail === undefined) {
    return false;
  }
  if ((toCheck as GenerationError).status === undefined) {
    return false;
  }
  return true;
};

export interface RuleEvaluation {
  name: string;
  description: string;
  score: number;
  topic: string;
}

export enum AutomatedAnalysisScore {
  NA_SCORE = -1,
  ORANGE_SCORE_THRESHOLD = 50,
  RED_SCORE_THRESHOLD = 75,
}

export const FAILED_REPORT_MESSAGE = 'Failed to load report from snapshot. Request entity too large.';
export const NO_RECORDINGS_MESSAGE = 'No active or archived recordings available.';
export const RECORDING_FAILURE_MESSAGE = 'Failed to start recording for analysis.';
export const TEMPLATE_UNSUPPORTED_MESSAGE = 'Template type unsupported on this JVM.';
